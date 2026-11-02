package com.ai.operations.gateway.application.provider;

import com.ai.operations.gateway.application.model.GatewayGovernanceConfig.RateLimitRule;
import com.ai.operations.gateway.infrastructure.config.NacosConfig;
import com.ai.operations.gateway.infrastructure.enums.NacosDataIdEnum;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.*;

class GatewayGovernanceConfigProvidersTest {

    private final NacosConfig nacos = new NacosConfig();
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void rateLimitRejectsZeroTtlDefaultAndRouteWithoutChangingSnapshot() {
        GatewayRateLimitConfigProvider provider = new GatewayRateLimitConfigProvider(nacos, mapper);
        provider.registerConfigHandlers();
        publish(NacosDataIdEnum.AI_GATEWAY_RATE_LIMIT,
                "{\"default\":{\"replenishRate\":20,\"burstCapacity\":40},\"routes\":{\"route\":{\"burstCapacity\":30}}}");
        RateLimitRule defaultRule = provider.ruleFor("other");
        RateLimitRule routeRule = provider.ruleFor("route");

        for (String invalid : new String[]{
                "{\"default\":{\"replenishRate\":21,\"burstCapacity\":10}}",
                "{\"default\":{\"replenishRate\":50,\"burstCapacity\":100},\"routes\":{\"route\":{\"replenishRate\":21,\"burstCapacity\":10}}}",
                "{invalid"}) {
            publish(NacosDataIdEnum.AI_GATEWAY_RATE_LIMIT, invalid);
            assertSame(defaultRule, provider.ruleFor("other"));
            assertSame(routeRule, provider.ruleFor("route"));
        }
    }

    @Test
    void rateLimitAcceptsHalfRateCapacityAndLargeCapacityWithoutOverflow() {
        GatewayRateLimitConfigProvider provider = new GatewayRateLimitConfigProvider(nacos, mapper);
        provider.registerConfigHandlers();
        publish(NacosDataIdEnum.AI_GATEWAY_RATE_LIMIT,
                "{\"default\":{\"replenishRate\":20,\"burstCapacity\":10},\"routes\":{\"large\":{\"replenishRate\":2147483647,\"burstCapacity\":2147483647}}}");
        assertEquals(new RateLimitRule(true, 20, 10, 1), provider.ruleFor("other"));
        assertEquals(new RateLimitRule(true, Integer.MAX_VALUE, Integer.MAX_VALUE, 1), provider.ruleFor("large"));
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = " \n\t")
    void rateLimitBlankResetsDefaultAndRemovesRouteOverrides(String blank) {
        GatewayRateLimitConfigProvider provider = new GatewayRateLimitConfigProvider(nacos, mapper);
        provider.registerConfigHandlers();
        publish(NacosDataIdEnum.AI_GATEWAY_RATE_LIMIT,
                "{\"default\":{\"enabled\":false},\"routes\":{\"route\":{\"burstCapacity\":300}}}");
        assertEquals(300, provider.ruleFor("route").burstCapacity());
        publish(NacosDataIdEnum.AI_GATEWAY_RATE_LIMIT, blank);
        RateLimitRule defaults = new RateLimitRule(true, 100, 200, 1);
        assertEquals(defaults, provider.ruleFor("other"));
        assertEquals(defaults, provider.ruleFor("route"));
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = " \n\t")
    void downstreamHeadersRetainInvalidAndResetBlankToGatewayDefaults(String blank) {
        GatewayDownstreamHeadersConfigProvider provider = new GatewayDownstreamHeadersConfigProvider(nacos, mapper);
        provider.registerConfigHandlers();
        publish(NacosDataIdEnum.AI_GATEWAY_DOWNSTREAM_HEADERS,
                "{\"custom\":[\"X-Internal-Session-Id\"]}");
        assertFalse(provider.shouldRemove("custom", "X-Internal-Session-Id"));
        assertTrue(provider.shouldRemove("ai-operations", "X-Internal-Session-Id"));
        publish(NacosDataIdEnum.AI_GATEWAY_DOWNSTREAM_HEADERS, "{invalid");
        assertFalse(provider.shouldRemove("custom", "X-Internal-Session-Id"));
        assertTrue(provider.shouldRemove("ai-operations", "X-Internal-Session-Id"));

        publish(NacosDataIdEnum.AI_GATEWAY_DOWNSTREAM_HEADERS, blank);

        assertTrue(provider.shouldRemove("custom", "X-Internal-Session-Id"));
        assertFalse(provider.shouldRemove("ai-operations", "X-Internal-Session-Id"));
        assertFalse(provider.shouldRemove("ai-operations", "X-Forwarded-For"));
        assertTrue(provider.shouldRemove("ai-base", "X-Internal-Session-Id"));
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = " \n\t")
    void callChainRetainsInvalidAndResetsBlankToDefaultLimits(String blank) {
        GatewayCallChainConfigProvider provider = new GatewayCallChainConfigProvider(nacos, mapper);
        provider.registerConfigHandlers();
        publish(NacosDataIdEnum.AI_GATEWAY_CALL_CHAIN,
                "{\"maxCallChainHops\":8,\"maxCallChainLength\":128}");
        assertEquals(8, provider.maxCallChainHops());
        assertEquals(128, provider.maxCallChainLength());
        for (String invalid : new String[]{"{invalid", "{\"maxCallChainHops\":16,\"maxCallChainLength\":63}"}) {
            publish(NacosDataIdEnum.AI_GATEWAY_CALL_CHAIN, invalid);
            assertEquals(8, provider.maxCallChainHops());
            assertEquals(128, provider.maxCallChainLength());
        }

        publish(NacosDataIdEnum.AI_GATEWAY_CALL_CHAIN, blank);

        assertEquals(64, provider.maxCallChainHops());
        assertEquals(2048, provider.maxCallChainLength());
    }

    private void publish(NacosDataIdEnum dataId, String content) {
        ReflectionTestUtils.invokeMethod(nacos, "notifyConfigHandlers", dataId.dataId(), content);
    }
}

