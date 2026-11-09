package com.ai.operations.gateway.infrastructure.provider;

import com.ai.operations.gateway.application.governance.CallChainPolicy;
import com.ai.operations.gateway.application.governance.DownstreamHeadersPolicy;
import com.ai.operations.gateway.application.governance.GatewayRateLimitPolicy;
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
        GatewayRateLimitPolicy policy = new GatewayRateLimitPolicy();
        GatewayRateLimitConfigProvider provider = new GatewayRateLimitConfigProvider(nacos, mapper, policy);
        provider.registerConfigHandlers();
        publish(NacosDataIdEnum.AI_GATEWAY_RATE_LIMIT,
                "{\"default\":{\"replenishRate\":20,\"burstCapacity\":40},\"routes\":{\"route\":{\"burstCapacity\":30}}}");
        RateLimitRule defaultRule = policy.ruleFor("other");
        RateLimitRule routeRule = policy.ruleFor("route");

        for (String invalid : new String[]{
                "{\"default\":{\"replenishRate\":21,\"burstCapacity\":10}}",
                "{\"default\":{\"replenishRate\":50,\"burstCapacity\":100},\"routes\":{\"route\":{\"replenishRate\":21,\"burstCapacity\":10}}}",
                "{invalid"}) {
            publish(NacosDataIdEnum.AI_GATEWAY_RATE_LIMIT, invalid);
            assertSame(defaultRule, policy.ruleFor("other"));
            assertSame(routeRule, policy.ruleFor("route"));
        }
    }

    @Test
    void rateLimitAcceptsHalfRateCapacityAndLargeCapacityWithoutOverflow() {
        GatewayRateLimitPolicy policy = new GatewayRateLimitPolicy();
        GatewayRateLimitConfigProvider provider = new GatewayRateLimitConfigProvider(nacos, mapper, policy);
        provider.registerConfigHandlers();
        publish(NacosDataIdEnum.AI_GATEWAY_RATE_LIMIT,
                "{\"default\":{\"replenishRate\":20,\"burstCapacity\":10},\"routes\":{\"large\":{\"replenishRate\":2147483647,\"burstCapacity\":2147483647}}}");
        assertEquals(new RateLimitRule(true, 20, 10, 1), policy.ruleFor("other"));
        assertEquals(new RateLimitRule(true, Integer.MAX_VALUE, Integer.MAX_VALUE, 1), policy.ruleFor("large"));
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = " \n\t")
    void rateLimitBlankResetsDefaultAndRemovesRouteOverrides(String blank) {
        GatewayRateLimitPolicy policy = new GatewayRateLimitPolicy();
        GatewayRateLimitConfigProvider provider = new GatewayRateLimitConfigProvider(nacos, mapper, policy);
        provider.registerConfigHandlers();
        publish(NacosDataIdEnum.AI_GATEWAY_RATE_LIMIT,
                "{\"default\":{\"enabled\":false},\"routes\":{\"route\":{\"burstCapacity\":300}}}");
        assertEquals(300, policy.ruleFor("route").burstCapacity());
        publish(NacosDataIdEnum.AI_GATEWAY_RATE_LIMIT, blank);
        RateLimitRule defaults = new RateLimitRule(true, 100, 200, 1);
        assertEquals(defaults, policy.ruleFor("other"));
        assertEquals(defaults, policy.ruleFor("route"));
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = " \n\t")
    void downstreamHeadersRetainInvalidAndResetBlankToGatewayDefaults(String blank) {
        DownstreamHeadersPolicy policy = new DownstreamHeadersPolicy();
        GatewayDownstreamHeadersConfigProvider provider =
                new GatewayDownstreamHeadersConfigProvider(nacos, mapper, policy);
        provider.registerConfigHandlers();
        publish(NacosDataIdEnum.AI_GATEWAY_DOWNSTREAM_HEADERS,
                "{\"custom\":[\"X-Internal-Session-Id\"]}");
        assertFalse(policy.shouldRemove("custom", "X-Internal-Session-Id"));
        assertTrue(policy.shouldRemove("ai-base", "X-Internal-Session-Id"));
        publish(NacosDataIdEnum.AI_GATEWAY_DOWNSTREAM_HEADERS, "{invalid");
        assertFalse(policy.shouldRemove("custom", "X-Internal-Session-Id"));
        assertTrue(policy.shouldRemove("ai-base", "X-Internal-Session-Id"));

        publish(NacosDataIdEnum.AI_GATEWAY_DOWNSTREAM_HEADERS, blank);

        assertTrue(policy.shouldRemove("custom", "X-Internal-Session-Id"));
        assertFalse(policy.shouldRemove("ai-base", "X-Internal-Session-Id"));
        assertFalse(policy.shouldRemove("ai-base", "X-Forwarded-For"));
        assertTrue(policy.shouldRemove("ai-operations", "X-Internal-Session-Id"));
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = " \n\t")
    void callChainRetainsInvalidAndResetsBlankToDefaultLimits(String blank) {
        CallChainPolicy policy = new CallChainPolicy();
        GatewayCallChainConfigProvider provider = new GatewayCallChainConfigProvider(nacos, mapper, policy);
        provider.registerConfigHandlers();
        publish(NacosDataIdEnum.AI_GATEWAY_CALL_CHAIN,
                "{\"maxCallChainHops\":8,\"maxCallChainLength\":128}");
        assertEquals(8, policy.maxCallChainHops());
        assertEquals(128, policy.maxCallChainLength());
        for (String invalid : new String[]{"{invalid", "{\"maxCallChainHops\":16,\"maxCallChainLength\":63}"}) {
            publish(NacosDataIdEnum.AI_GATEWAY_CALL_CHAIN, invalid);
            assertEquals(8, policy.maxCallChainHops());
            assertEquals(128, policy.maxCallChainLength());
        }

        publish(NacosDataIdEnum.AI_GATEWAY_CALL_CHAIN, blank);

        assertEquals(64, policy.maxCallChainHops());
        assertEquals(2048, policy.maxCallChainLength());
    }

    private void publish(NacosDataIdEnum dataId, String content) {
        ReflectionTestUtils.invokeMethod(nacos, "notifyConfigHandlers", dataId.dataId(), content);
    }
}
