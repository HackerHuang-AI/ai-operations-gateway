package com.ai.operations.gateway.infrastructure.enums;

public enum NacosDataIdEnum {

    AI_GATEWAY_ROUTES("ai-operations-gateway-routes.json"),
    AI_GATEWAY_RATE_LIMIT("ai-operations-gateway-rate-limit.json"),
    AI_GATEWAY_CIRCUIT_BREAKER("ai-operations-gateway-circuit-breaker.json"),
    AI_GATEWAY_DOWNSTREAM_HEADERS("ai-operations-gateway-downstream-headers.json"),
    AI_GATEWAY_CALL_CHAIN("ai-operations-gateway-call-chain.json");

    private final String dataId;

    NacosDataIdEnum(String dataId) {
        this.dataId = dataId;
    }

    public String dataId() {
        return dataId;
    }
}
