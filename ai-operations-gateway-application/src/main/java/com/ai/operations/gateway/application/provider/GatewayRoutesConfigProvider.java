package com.ai.operations.gateway.application.provider;

import com.ai.operations.gateway.application.locator.NacosRouteDefinitionLocator;
import com.ai.operations.gateway.application.model.GatewayGovernanceConfig.*;
import com.ai.operations.gateway.infrastructure.config.NacosConfig;
import com.ai.operations.gateway.infrastructure.enums.NacosDataIdEnum;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.cloud.gateway.event.RefreshRoutesEvent;
import org.springframework.cloud.gateway.filter.FilterDefinition;
import org.springframework.cloud.gateway.filter.factory.GatewayFilterFactory;
import org.springframework.cloud.gateway.handler.predicate.PredicateDefinition;
import org.springframework.cloud.gateway.handler.predicate.RoutePredicateFactory;
import org.springframework.cloud.gateway.route.RouteDefinition;
import org.springframework.cloud.gateway.support.ConfigurationService;
import org.springframework.cloud.gateway.support.HasRouteId;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.net.URI;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 加载并应用网关路由和熔断治理配置。
 *
 * @author HUANGcong
 * @since 2026-09-24
 */
@Slf4j
@Component
public class GatewayRoutesConfigProvider {

    private static final List<String> DEFAULT_AUTH_WHITELIST = List.of(
            "/ai-operations/api/access/login", "/ai-operations/api/access/sms-code/send");
    private static final CircuitBreakerRule DEFAULT_CIRCUIT_BREAKER = new CircuitBreakerRule(10, 10, 50F, 10, 3);

    private final NacosConfig nacosConfig;
    private final ObjectMapper objectMapper;
    private final NacosRouteDefinitionLocator routeDefinitionLocator;
    private final ApplicationEventPublisher eventPublisher;
    private final CircuitBreakerRegistry circuitBreakerRegistry;
    private final Map<String, RoutePredicateFactory<?>> predicateFactories;
    private final Map<String, GatewayFilterFactory<?>> filterFactories;
    @Autowired
    private ConfigurationService configurationService;
    private final AtomicReference<RouteSnapshot> routeSnapshot =
            new AtomicReference<>(new RouteSnapshot(DEFAULT_AUTH_WHITELIST));
    private final AtomicReference<CircuitSnapshot> circuitSnapshot =
            new AtomicReference<>(new CircuitSnapshot(DEFAULT_CIRCUIT_BREAKER, Map.of()));
    private final AtomicReference<Routes> validRoutesConfig = new AtomicReference<>();
    private volatile boolean applicationReady;

    public GatewayRoutesConfigProvider(NacosConfig nacosConfig, ObjectMapper objectMapper,
                                       NacosRouteDefinitionLocator routeDefinitionLocator,
                                       ApplicationEventPublisher eventPublisher,
                                       CircuitBreakerRegistry circuitBreakerRegistry,
                                       List<RoutePredicateFactory<?>> predicateFactories,
                                       List<GatewayFilterFactory<?>> filterFactories) {
        this.nacosConfig = nacosConfig;
        this.objectMapper = objectMapper;
        this.routeDefinitionLocator = routeDefinitionLocator;
        this.eventPublisher = eventPublisher;
        this.circuitBreakerRegistry = circuitBreakerRegistry;
        this.predicateFactories = predicateFactories.stream()
                .collect(java.util.stream.Collectors.toUnmodifiableMap(RoutePredicateFactory::name, factory -> factory));
        this.filterFactories = filterFactories.stream()
                .collect(java.util.stream.Collectors.toUnmodifiableMap(GatewayFilterFactory::name, factory -> factory));
    }

    @PostConstruct
    void registerConfigHandlers() {
        nacosConfig.registerConfigHandler(NacosDataIdEnum.AI_GATEWAY_CIRCUIT_BREAKER, this::applyCircuitBreakerConfig);
        nacosConfig.registerConfigHandler(NacosDataIdEnum.AI_GATEWAY_ROUTES, this::applyRoutesConfig);
    }

    public boolean isAuthWhiteListed(String path) {
        return routeSnapshot.get().authWhitelist().stream().anyMatch(pattern -> matches(path, pattern));
    }

    @EventListener(ApplicationReadyEvent.class)
    void markApplicationReady() {
        applicationReady = true;
    }

    private void applyRoutesConfig(String content) {
        try {
            Routes config = StringUtils.hasText(content) ? objectMapper.readValue(content, Routes.class)
                    : new Routes(List.of(), List.of());
            replaceManagedRoutes(config);
        } catch (Exception exception) {
            log.error("[GatewayRoutes] Ignoring invalid routes config and retaining the last valid snapshot", exception);
        }
    }

    private void applyCircuitBreakerConfig(String content) {
        try {
            CircuitBreaker config = StringUtils.hasText(content) ? objectMapper.readValue(content, CircuitBreaker.class)
                    : new CircuitBreaker(null, Map.of());
            CircuitBreakerRule defaultRule = mergeCircuitBreaker(DEFAULT_CIRCUIT_BREAKER, config.defaultRule());
            Map<String, CircuitBreakerRule> instances = new HashMap<>();
            if (config.instances() != null) {
                config.instances().forEach((routeId, rule) -> {
                    validateRouteId(routeId);
                    instances.put(routeId, mergeCircuitBreaker(defaultRule, rule));
                });
            }
            CircuitSnapshot previous = circuitSnapshot.get();
            previous.instances().keySet().stream().filter(routeId -> !instances.containsKey(routeId))
                    .forEach(circuitBreakerRegistry::remove);
            instances.forEach(this::replaceCircuitBreaker);
            circuitSnapshot.set(new CircuitSnapshot(defaultRule, Map.copyOf(instances)));
            Routes routes = validRoutesConfig.get();
            if (routes != null) {
                replaceManagedRoutes(routes);
            }
        } catch (Exception exception) {
            log.error("[GatewayCircuitBreaker] Ignoring invalid circuit breaker config and retaining the last valid snapshot", exception);
        }
    }

    private List<String> validateWhitelist(List<String> whitelist) {
        if (whitelist == null) {
            throw new IllegalArgumentException("authWhitelist must not be null");
        }
        List<String> result = new ArrayList<>();
        for (String pattern : whitelist) {
            if (!StringUtils.hasText(pattern) || !pattern.startsWith("/")
                    || (pattern.contains("*") && !pattern.endsWith("/**"))
                    || pattern.substring(0, Math.max(0, pattern.length() - 3)).contains("*")) {
                throw new IllegalArgumentException("unsupported auth whitelist pattern: " + pattern);
            }
            result.add(pattern);
        }
        return List.copyOf(result);
    }

    private List<RouteDefinition> toRouteDefinitions(List<Route> routes) {
        if (routes == null) {
            throw new IllegalArgumentException("routes must not be null");
        }
        List<RouteDefinition> definitions = new ArrayList<>();
        Set<String> routeIds = new HashSet<>();
        for (Route route : routes) {
            validateRouteId(route.id());
            if (!routeIds.add(route.id())) {
                throw new IllegalArgumentException("duplicate route id: " + route.id());
            }
            if (!StringUtils.hasText(route.uri())) {
                throw new IllegalArgumentException("route uri must not be blank");
            }
            URI uri = URI.create(route.uri());
            if (!"lb".equalsIgnoreCase(uri.getScheme()) && !uri.isAbsolute()) {
                throw new IllegalArgumentException("route uri must be absolute: " + route.uri());
            }
            RouteDefinition definition = new RouteDefinition();
            definition.setId(route.id());
            definition.setUri(uri);
            definition.setPredicates(toPredicates(route.predicates()));
            definition.setFilters(toFilters(route.id(), route.filters()));
            definitions.add(definition);
        }
        return definitions;
    }

    private List<PredicateDefinition> toPredicates(List<Predicate> predicates) {
        if (predicates == null || predicates.isEmpty()) {
            throw new IllegalArgumentException("route predicates must not be empty");
        }
        List<PredicateDefinition> definitions = new ArrayList<>();
        for (Predicate predicate : predicates) {
            if (!StringUtils.hasText(predicate.name()) || !predicateFactories.containsKey(predicate.name())) {
                throw new IllegalArgumentException("unsupported predicate: " + predicate.name());
            }
            PredicateDefinition definition = new PredicateDefinition();
            definition.setName(predicate.name());
            definition.setArgs(copyArgs(predicate.args()));
            definitions.add(definition);
        }
        return definitions;
    }

    private List<FilterDefinition> toFilters(String routeId, List<Filter> filters) {
        List<FilterDefinition> definitions = new ArrayList<>();
        if (filters != null) {
            for (Filter filter : filters) {
                if (!StringUtils.hasText(filter.name()) || !filterFactories.containsKey(filter.name())) {
                    throw new IllegalArgumentException("unsupported filter: " + filter.name());
                }
                FilterDefinition definition = new FilterDefinition();
                definition.setName(filter.name());
                Map<String, String> args = new HashMap<>(copyArgs(filter.args()));
                if ("CircuitBreaker".equals(filter.name())
                        && circuitSnapshot.get().instances().containsKey(routeId)) {
                    args.put("name", routeId);
                }
                definition.setArgs(args);
                definitions.add(definition);
            }
        }
        if (circuitSnapshot.get().instances().containsKey(routeId)
                && definitions.stream().noneMatch(filter -> "CircuitBreaker".equals(filter.getName()))) {
            if (!filterFactories.containsKey("CircuitBreaker")) {
                throw new IllegalArgumentException("CircuitBreaker filter factory is not available");
            }
            FilterDefinition circuitBreaker = new FilterDefinition();
            circuitBreaker.setName("CircuitBreaker");
            circuitBreaker.setArgs(Map.of("name", routeId, "statusCodes", "500,502,503,504"));
            definitions.add(circuitBreaker);
        }
        return definitions;
    }

    private Map<String, String> copyArgs(Map<String, String> args) {
        if (args == null) {
            return Map.of();
        }
        Map<String, String> result = new HashMap<>();
        args.forEach((key, value) -> {
            if (!StringUtils.hasText(key) || value == null) {
                throw new IllegalArgumentException("filter and predicate args must contain non-empty keys and values");
            }
            result.put(key, value);
        });
        return result;
    }

    private synchronized void replaceManagedRoutes(Routes config) {
        List<String> whitelist = validateWhitelist(config.authWhitelist());
        List<RouteDefinition> definitions = toRouteDefinitions(config.routes());
        for (RouteDefinition definition : definitions) {
            for (PredicateDefinition predicate : definition.getPredicates()) {
                validatePredicate(predicateFactories.get(predicate.getName()), predicate.getArgs());
            }
            for (FilterDefinition filter : definition.getFilters()) {
                validateFilter(filterFactories.get(filter.getName()), filter.getArgs(), definition.getId());
            }
        }
        routeDefinitionLocator.replace(definitions);
        validRoutesConfig.set(config);
        routeSnapshot.set(new RouteSnapshot(whitelist));
        if (applicationReady) {
            eventPublisher.publishEvent(new RefreshRoutesEvent(this));
        }
        log.info("[GatewayRoutes] Applied {} dynamic routes", definitions.size());
    }

    private <C> void validatePredicate(RoutePredicateFactory<C> factory, Map<String, String> args) {
        C config = configurationService.with(factory).name(factory.name()).properties(args).bind();
        factory.applyAsync(config);
    }

    private <C> void validateFilter(GatewayFilterFactory<C> factory, Map<String, String> args, String routeId) {
        C config = configurationService.with(factory).name(factory.name()).properties(args).bind();
        if (config instanceof HasRouteId hasRouteId) {
            hasRouteId.setRouteId(routeId);
        }
        factory.apply(config);
    }

    private void replaceCircuitBreaker(String routeId, CircuitBreakerRule rule) {
        CircuitBreakerConfig config = CircuitBreakerConfig.custom()
                .slidingWindowSize(rule.slidingWindowSize())
                .minimumNumberOfCalls(rule.minimumNumberOfCalls())
                .failureRateThreshold(rule.failureRateThreshold())
                .waitDurationInOpenState(Duration.ofSeconds(rule.waitDurationInOpenStateSeconds()))
                .permittedNumberOfCallsInHalfOpenState(rule.permittedNumberOfCallsInHalfOpenState())
                .build();
        circuitBreakerRegistry.remove(routeId);
        circuitBreakerRegistry.circuitBreaker(routeId, config);
    }

    private CircuitBreakerRule mergeCircuitBreaker(CircuitBreakerRule base, CircuitBreakerRule override) {
        CircuitBreakerRule merged = new CircuitBreakerRule(
                override != null && override.slidingWindowSize() != null ? override.slidingWindowSize() : base.slidingWindowSize(),
                override != null && override.minimumNumberOfCalls() != null ? override.minimumNumberOfCalls() : base.minimumNumberOfCalls(),
                override != null && override.failureRateThreshold() != null ? override.failureRateThreshold() : base.failureRateThreshold(),
                override != null && override.waitDurationInOpenStateSeconds() != null ? override.waitDurationInOpenStateSeconds() : base.waitDurationInOpenStateSeconds(),
                override != null && override.permittedNumberOfCallsInHalfOpenState() != null ? override.permittedNumberOfCallsInHalfOpenState() : base.permittedNumberOfCallsInHalfOpenState());
        if (merged.slidingWindowSize() < 1 || merged.minimumNumberOfCalls() < 1
                || merged.minimumNumberOfCalls() > merged.slidingWindowSize()
                || merged.failureRateThreshold() <= 0 || merged.failureRateThreshold() > 100
                || merged.waitDurationInOpenStateSeconds() < 1
                || merged.permittedNumberOfCallsInHalfOpenState() < 1) {
            throw new IllegalArgumentException("invalid circuit breaker rule");
        }
        return merged;
    }

    private void validateRouteId(String routeId) {
        if (!StringUtils.hasText(routeId) || !routeId.matches("[A-Za-z0-9._-]+")) {
            throw new IllegalArgumentException("invalid route id: " + routeId);
        }
    }

    private boolean matches(String path, String pattern) {
        if (pattern.endsWith("/**")) {
            String prefix = pattern.substring(0, pattern.length() - 3);
            return path.equals(prefix) || path.startsWith(prefix + "/");
        }
        return path.equals(pattern);
    }

    private record RouteSnapshot(List<String> authWhitelist) {
    }

    private record CircuitSnapshot(CircuitBreakerRule defaultRule, Map<String, CircuitBreakerRule> instances) {
    }
}
