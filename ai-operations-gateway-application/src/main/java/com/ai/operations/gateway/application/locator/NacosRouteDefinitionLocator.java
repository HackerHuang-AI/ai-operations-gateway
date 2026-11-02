package com.ai.operations.gateway.application.locator;

import org.springframework.cloud.gateway.route.RouteDefinition;
import org.springframework.cloud.gateway.route.RouteDefinitionLocator;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 维护来自 Nacos 的网关路由定义。
 *
 * @author HUANGcong
 * @since 2026-09-24
 */
@Component
public class NacosRouteDefinitionLocator implements RouteDefinitionLocator {

    private final AtomicReference<Map<String, RouteDefinition>> definitions =
            new AtomicReference<>(Map.of());

    public void replace(List<RouteDefinition> routeDefinitions) {
        Map<String, RouteDefinition> next = new LinkedHashMap<>();
        routeDefinitions.forEach(definition -> next.put(definition.getId(), definition));
        definitions.set(Collections.unmodifiableMap(next));
    }

    @Override
    public Flux<RouteDefinition> getRouteDefinitions() {
        return Flux.fromIterable(definitions.get().values());
    }
}

