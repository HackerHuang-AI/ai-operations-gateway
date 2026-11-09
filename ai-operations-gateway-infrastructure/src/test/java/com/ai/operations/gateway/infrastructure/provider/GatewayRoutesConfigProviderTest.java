package com.ai.operations.gateway.infrastructure.provider;

import com.ai.operations.gateway.application.governance.AuthWhitelistService;
import com.ai.operations.gateway.application.model.GatewayGovernanceConfig.*;
import com.ai.operations.gateway.infrastructure.config.NacosConfig;
import com.ai.operations.gateway.infrastructure.locator.NacosRouteDefinitionLocator;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.boot.convert.ApplicationConversionService;
import org.springframework.cloud.client.circuitbreaker.ReactiveCircuitBreakerFactory;
import org.springframework.cloud.gateway.event.RefreshRoutesEvent;
import org.springframework.cloud.gateway.filter.FilterDefinition;
import org.springframework.cloud.gateway.filter.factory.RewritePathGatewayFilterFactory;
import org.springframework.cloud.gateway.filter.factory.SpringCloudCircuitBreakerFilterFactory;
import org.springframework.cloud.gateway.filter.factory.SpringCloudCircuitBreakerResilience4JFilterFactory;
import org.springframework.cloud.gateway.filter.factory.StripPrefixGatewayFilterFactory;
import org.springframework.cloud.gateway.handler.predicate.PathRoutePredicateFactory;
import org.springframework.cloud.gateway.route.RouteDefinition;
import org.springframework.cloud.gateway.support.ConfigurationService;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;
import org.springframework.web.reactive.DispatcherHandler;

import java.net.URI;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class GatewayRoutesConfigProviderTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final NacosRouteDefinitionLocator locator = new NacosRouteDefinitionLocator();
    private final AuthWhitelistService authWhitelistService = new AuthWhitelistService();
    private final CircuitBreakerRegistry registry = CircuitBreakerRegistry.ofDefaults();
    private final ApplicationEventPublisher publisher = mock(ApplicationEventPublisher.class);
    private final LocalValidatorFactoryBean validator = new LocalValidatorFactoryBean();
    private SpringCloudCircuitBreakerResilience4JFilterFactory circuitFactory;
    private ConfigurationService configurationService;
    private GatewayRoutesConfigProvider provider;

    @BeforeEach
    void setUp() {
        validator.afterPropertiesSet();
        DefaultListableBeanFactory beanFactory = new DefaultListableBeanFactory();
        configurationService = new ConfigurationService(beanFactory,
                () -> ApplicationConversionService.getSharedInstance(), () -> validator);
        circuitFactory = new SpringCloudCircuitBreakerResilience4JFilterFactory(
                mock(ReactiveCircuitBreakerFactory.class), beanFactory.getBeanProvider(DispatcherHandler.class));
        provider = new GatewayRoutesConfigProvider(mock(NacosConfig.class), objectMapper, locator,
                authWhitelistService, publisher, registry, List.of(new PathRoutePredicateFactory()),
                List.of(new StripPrefixGatewayFilterFactory(), new RewritePathGatewayFilterFactory(), circuitFactory));
        ReflectionTestUtils.setField(provider, "configurationService", configurationService);
        provider.markApplicationReady();
    }

    @AfterEach
    void tearDown() {
        validator.close();
    }

    @Test
    void invalidPartsRetainsRoutesAndWhitelist() throws Exception {
        assertRejected(route("new", "/new/**", List.of(new Filter("StripPrefix", Map.of("parts", "not-an-integer")))));
    }

    @Test
    void invalidRegexRetainsRoutesAndWhitelist() throws Exception {
        assertRejected(route("new", "/new/**", List.of(new Filter("RewritePath",
                Map.of("regexp", "[", "replacement", "/new")))));
    }

    @Test
    void invalidPathPatternRetainsRoutesAndWhitelist() throws Exception {
        assertRejected(route("new", "/**/invalid", List.of()));
    }

    private void assertRejected(Route invalidRoute) throws Exception {
        applyRoutes(new Routes(List.of("/old"), List.of(route("old", "/old/**", List.of()))));
        List<RouteDefinition> previous = definitions();
        assertThat(previous).hasSize(1);
        clearInvocations(publisher);

        applyRoutes(new Routes(List.of("/new"), List.of(route("valid", "/valid/**", List.of()), invalidRoute)));

        assertThat(definitions()).containsExactlyElementsOf(previous);
        assertThat(authWhitelistService.isAuthWhiteListed("/old")).isTrue();
        assertThat(authWhitelistService.isAuthWhiteListed("/new")).isFalse();
        verify(publisher, never()).publishEvent(any(RefreshRoutesEvent.class));
    }

    @Test
    void validRoutesReplaceRoutesAndWhitelist() throws Exception {
        applyRoutes(new Routes(List.of("/old"), List.of(route("old", "/old/**", List.of()))));
        clearInvocations(publisher);

        applyRoutes(new Routes(List.of("/new/**"), List.of(route("new", "/new/**",
                List.of(new Filter("StripPrefix", Map.of("parts", "1")))))));

        assertThat(definitions()).extracting(RouteDefinition::getId).containsExactly("new");
        assertThat(definitions().get(0).getFilters().get(0).getArgs()).containsEntry("parts", "1");
        assertThat(authWhitelistService.isAuthWhiteListed("/old")).isFalse();
        assertThat(authWhitelistService.isAuthWhiteListed("/new/child")).isTrue();
        verify(publisher).publishEvent(any(RefreshRoutesEvent.class));
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"  \n\t"})
    void withdrawnRoutesClearRoutesAndWhitelistAndRefresh(String content) throws Exception {
        applyRoutes(new Routes(List.of("/old"), List.of(route("old", "/old/**", List.of()))));
        clearInvocations(publisher);

        ReflectionTestUtils.invokeMethod(provider, "applyRoutesConfig", (Object) content);

        assertThat(definitions()).isEmpty();
        assertThat(authWhitelistService.isAuthWhiteListed("/old")).isFalse();
        assertThat(authWhitelistService.isAuthWhiteListed("/ai-base/api/access/login")).isFalse();
        verify(publisher).publishEvent(any(RefreshRoutesEvent.class));
    }

    @Test
    void nullWhitelistAndInvalidJsonRetainLatestRoutes() throws Exception {
        applyRoutes(new Routes(List.of("/old"), List.of(route("old", "/old/**", List.of()))));
        List<RouteDefinition> previous = definitions();
        assertThat(previous).hasSize(1);
        clearInvocations(publisher);

        applyRoutes(new Routes(null, List.of()));
        ReflectionTestUtils.invokeMethod(provider, "applyRoutesConfig", "{invalid");

        assertThat(definitions()).containsExactlyElementsOf(previous);
        assertThat(authWhitelistService.isAuthWhiteListed("/old")).isTrue();
        verify(publisher, never()).publishEvent(any(RefreshRoutesEvent.class));
    }

    @Test
    void explicitCircuitBreakerRetainsArgsAndOnlyManagedNameChanges() throws Exception {
        Map<String, String> args = Map.of("name", "explicit", "fallbackUri", "forward:/fallback",
                "statusCodes", "502,504", "resumeWithoutError", "true");
        applyRoutes(new Routes(List.of("/old"), List.of(route("old", "/old/**",
                List.of(new Filter("CircuitBreaker", args))))));
        assertThat(circuitDefinition().getArgs()).isEqualTo(args);

        applyCircuit("{\"instances\":{\"old\":{}}}");

        assertThat(definitions().get(0).getFilters()).hasSize(1);
        assertThat(circuitDefinition().getArgs()).containsAllEntriesOf(Map.of("name", "old",
                "fallbackUri", "forward:/fallback", "statusCodes", "502,504", "resumeWithoutError", "true"));
        SpringCloudCircuitBreakerFilterFactory.Config config = boundCircuit();
        assertThat(config.getFallbackUri()).isEqualTo(URI.create("forward:/fallback"));
        assertThat(config.getStatusCodes()).containsExactlyInAnyOrder("502", "504");
        assertThat(config.isResumeWithoutError()).isTrue();

        applyCircuit("");
        assertThat(circuitDefinition().getArgs()).isEqualTo(args);
    }

    @Test
    void automaticCircuitBreakerBindsDefaultServerErrorStatusCodes() throws Exception {
        applyRoutes(new Routes(List.of("/old"), List.of(route("old", "/old/**", List.of()))));

        applyCircuit("{\"instances\":{\"old\":{}}}");

        assertThat(definitions().get(0).getFilters()).hasSize(1);
        SpringCloudCircuitBreakerFilterFactory.Config config = boundCircuit();
        assertThat(config.getName()).isEqualTo("old");
        assertThat(config.getStatusCodes()).containsExactlyInAnyOrder("500", "502", "503", "504");
        assertThat(registry.find("old")).isPresent();
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"  \n\t"})
    void withdrawnCircuitResetsDefaultsRemovesInstancesAndRebuildsRoutes(String content) throws Exception {
        applyRoutes(new Routes(List.of("/old"), List.of(route("old", "/old/**", List.of()))));
        applyCircuit("{\"default\":{\"slidingWindowSize\":20},\"instances\":{\"old\":{}}}");
        assertThat(registry.circuitBreaker("old").getCircuitBreakerConfig().getSlidingWindowSize()).isEqualTo(20);
        clearInvocations(publisher);

        applyCircuit(content);

        assertThat(registry.find("old")).isEmpty();
        assertThat(definitions().get(0).getFilters()).isEmpty();
        assertThat(authWhitelistService.isAuthWhiteListed("/old")).isTrue();
        verify(publisher).publishEvent(any(RefreshRoutesEvent.class));
        applyCircuit("{\"instances\":{\"old\":{}}}");
        assertThat(registry.circuitBreaker("old").getCircuitBreakerConfig().getSlidingWindowSize()).isEqualTo(10);
    }

    @Test
    void invalidCircuitJsonRetainsLatestInstancesAndRoutes() throws Exception {
        applyRoutes(new Routes(List.of("/old"), List.of(route("old", "/old/**", List.of()))));
        applyCircuit("{\"instances\":{\"old\":{}}}");
        List<RouteDefinition> previous = definitions();
        assertThat(previous).hasSize(1);
        clearInvocations(publisher);

        applyCircuit("{invalid");

        assertThat(registry.find("old")).isPresent();
        assertThat(definitions()).containsExactlyElementsOf(previous);
        assertThat(authWhitelistService.isAuthWhiteListed("/old")).isTrue();
        verify(publisher, never()).publishEvent(any(RefreshRoutesEvent.class));
    }

    @Test
    void keepsUserDomainDefaultsBeforeAnyConfig() {
        assertThat(authWhitelistService.isAuthWhiteListed("/ai-base/api/access/login")).isTrue();
        assertThat(authWhitelistService.isAuthWhiteListed("/ai-operations/api/access/login")).isFalse();
    }

    private Route route(String id, String pattern, List<Filter> filters) {
        return new Route(id, "lb://service", List.of(new Predicate("Path", Map.of("patterns", pattern))), filters);
    }

    private void applyRoutes(Routes routes) throws Exception {
        ReflectionTestUtils.invokeMethod(provider, "applyRoutesConfig", objectMapper.writeValueAsString(routes));
    }

    private void applyCircuit(String content) {
        ReflectionTestUtils.invokeMethod(provider, "applyCircuitBreakerConfig", (Object) content);
    }

    private List<RouteDefinition> definitions() {
        return locator.getRouteDefinitions().collectList().block();
    }

    private FilterDefinition circuitDefinition() {
        return definitions().get(0).getFilters().get(0);
    }

    private SpringCloudCircuitBreakerFilterFactory.Config boundCircuit() {
        return configurationService.with(circuitFactory).name(circuitFactory.name())
                .properties(circuitDefinition().getArgs()).bind();
    }
}
