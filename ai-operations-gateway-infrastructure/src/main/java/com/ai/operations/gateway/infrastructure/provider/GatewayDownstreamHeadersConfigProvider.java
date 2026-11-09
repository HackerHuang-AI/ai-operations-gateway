package com.ai.operations.gateway.infrastructure.provider;

import com.ai.operations.gateway.application.governance.DownstreamHeadersPolicy;
import com.ai.operations.gateway.infrastructure.config.NacosConfig;
import com.ai.operations.gateway.infrastructure.enums.NacosDataIdEnum;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * 订阅 Nacos 下游扩展请求头配置，解析后交由策略服务校验并应用。
 *
 * @author HUANGcong
 * @since 2026-09-24
 */
@Slf4j
@Component
public class GatewayDownstreamHeadersConfigProvider {

    private final NacosConfig nacosConfig;
    private final ObjectMapper objectMapper;
    private final DownstreamHeadersPolicy headersPolicy;

    public GatewayDownstreamHeadersConfigProvider(NacosConfig nacosConfig, ObjectMapper objectMapper,
                                                  DownstreamHeadersPolicy headersPolicy) {
        this.nacosConfig = nacosConfig;
        this.objectMapper = objectMapper;
        this.headersPolicy = headersPolicy;
    }

    @PostConstruct
    void registerConfigHandlers() {
        nacosConfig.registerConfigHandler(NacosDataIdEnum.AI_GATEWAY_DOWNSTREAM_HEADERS, this::applyConfig);
    }

    private void applyConfig(String content) {
        if (content == null || content.isBlank()) {
            headersPolicy.replace(null);
            log.info("[GatewayDownstreamHeaders] Reset to default config");
            return;
        }
        try {
            Map<String, List<String>> config = objectMapper.readValue(content, new TypeReference<>() { });
            headersPolicy.replace(config);
            log.info("[GatewayDownstreamHeaders] Applied config for {} applications", config.size());
        } catch (Exception exception) {
            log.error("[GatewayDownstreamHeaders] Ignoring invalid config and retaining the last valid snapshot", exception);
        }
    }
}
