package com.ai.operations.gateway.infrastructure.config;

import com.ai.operations.gateway.infrastructure.enums.NacosDataIdEnum;
import com.alibaba.nacos.api.NacosFactory;
import com.alibaba.nacos.api.config.ConfigService;
import com.alibaba.nacos.api.config.listener.Listener;
import com.alibaba.nacos.api.exception.NacosException;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.StringReader;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;
import java.util.function.Consumer;

/**
 * 订阅并分发网关治理相关的 Nacos 配置。
 *
 * @author HUANGcong
 * @since 2026-09-24
 */
@Slf4j
@Component
public class NacosConfig {

    @Value("${spring.cloud.nacos.config.server-addr:}")
    private String serverAddr;

    @Value("${spring.application.name}")
    private String group;

    @Value("${spring.cloud.nacos.config.namespace}")
    private String namespace;

    @Value("${spring.cloud.nacos.config.username:}")
    private String username;

    @Value("${spring.cloud.nacos.config.password:}")
    private String password;

    @Value("${nacos.index-data-id:ai-operations-gateway-index.properties}")
    private String indexDataId;

    private final Map<String, String> rawContentCache = new ConcurrentHashMap<>();
    private final Set<String> registeredDataIds = ConcurrentHashMap.newKeySet();
    private final Map<String, Listener> dataIdListeners = new ConcurrentHashMap<>();
    private final Map<String, CopyOnWriteArrayList<Consumer<String>>> configHandlers = new ConcurrentHashMap<>();
    private final Object subscriptionMonitor = new Object();

    private volatile ConfigService configService;

    @PostConstruct
    public void init() {
        if (serverAddr == null || serverAddr.isBlank()) {
            log.warn("[NacosConfig] Nacos server address is not configured; dynamic governance is disabled");
            return;
        }
        try {
            Properties properties = new Properties();
            properties.put("serverAddr", serverAddr);
            properties.put("namespace", namespace);
            properties.put("appName", group);
            if (username != null && !username.isBlank()) {
                properties.put("username", username);
                properties.put("password", password == null ? "" : password);
            }
            configService = NacosFactory.createConfigService(properties);
            listenIndexDataId();
        } catch (NacosException exception) {
            throw new IllegalStateException("Failed to create Nacos ConfigService, serverAddr=" + serverAddr, exception);
        }
    }

    public void registerConfigHandler(NacosDataIdEnum dataId, Consumer<String> handler) {
        String key = dataId.dataId();
        configHandlers.computeIfAbsent(key, ignored -> new CopyOnWriteArrayList<>()).add(handler);
        String content = rawContentCache.get(key);
        if (content != null) {
            handler.accept(content);
        }
    }

    public String getRawContent(NacosDataIdEnum dataId) {
        return rawContentCache.get(dataId.dataId());
    }

    private void listenIndexDataId() {
        try {
            String content = configService.getConfig(indexDataId, group, 5000);
            if (content != null && !content.isBlank()) {
                parseIndex(content).ifPresent(dataIds -> dataIds.forEach(this::listenBusinessDataId));
            } else {
                log.warn("[NacosConfig] Index DataId is empty, dataId={}", indexDataId);
            }
        } catch (NacosException exception) {
            log.error("[NacosConfig] Failed to get index DataId, dataId={}", indexDataId, exception);
        }
        try {
            configService.addListener(indexDataId, group, new Listener() {
                @Override
                public Executor getExecutor() {
                    return null;
                }

                @Override
                public void receiveConfigInfo(String content) {
                    onIndexChanged(content);
                }
            });
        } catch (NacosException exception) {
            throw new IllegalStateException("Failed to subscribe Nacos index DataId=" + indexDataId, exception);
        }
    }

    private void onIndexChanged(String content) {
        parseIndex(content == null ? "" : content).ifPresent(newDataIds -> {
            synchronized (subscriptionMonitor) {
                newDataIds.forEach(this::listenBusinessDataId);
                Set<String> removedDataIds = new HashSet<>(registeredDataIds);
                removedDataIds.removeAll(newDataIds);
                removedDataIds.forEach(this::removeBusinessDataId);
            }
        });
    }

    private Optional<Set<String>> parseIndex(String content) {
        try {
            Properties properties = new Properties();
            properties.load(new StringReader(content));
            return Optional.of(properties.stringPropertyNames());
        } catch (Exception exception) {
            log.error("[NacosConfig] Failed to parse index DataId; retaining existing subscriptions, dataId={}",
                    indexDataId, exception);
            return Optional.empty();
        }
    }

    private void listenBusinessDataId(String dataId) {
        if (!NacosDataIdEnum.AI_GATEWAY_ROUTES.dataId().equals(dataId)
                && !NacosDataIdEnum.AI_GATEWAY_RATE_LIMIT.dataId().equals(dataId)
                && !NacosDataIdEnum.AI_GATEWAY_CIRCUIT_BREAKER.dataId().equals(dataId)
                && !NacosDataIdEnum.AI_GATEWAY_DOWNSTREAM_HEADERS.dataId().equals(dataId)
                && !NacosDataIdEnum.AI_GATEWAY_CALL_CHAIN.dataId().equals(dataId)) {
            log.warn("[NacosConfig] Ignoring unsupported governance DataId, dataId={}", dataId);
            return;
        }
        if (!registeredDataIds.add(dataId)) {
            return;
        }
        try {
            String content = configService.getConfig(dataId, group, 5000);
            updateRawContent(dataId, content == null || content.isBlank() ? "" : content);
        } catch (NacosException exception) {
            log.error("[NacosConfig] Failed to get business DataId, dataId={}", dataId, exception);
        }
        try {
            Listener listener = new Listener() {
                @Override
                public Executor getExecutor() {
                    return null;
                }

                @Override
                public void receiveConfigInfo(String content) {
                    updateRawContent(dataId, content == null || content.isBlank() ? "" : content);
                }
            };
            dataIdListeners.put(dataId, listener);
            configService.addListener(dataId, group, listener);
        } catch (NacosException exception) {
            registeredDataIds.remove(dataId);
            dataIdListeners.remove(dataId);
            throw new IllegalStateException("Failed to subscribe Nacos business DataId=" + dataId, exception);
        }
    }

    private void removeBusinessDataId(String dataId) {
        registeredDataIds.remove(dataId);
        updateRawContent(dataId, "");
        Listener listener = dataIdListeners.remove(dataId);
        if (listener != null && configService != null) {
            configService.removeListener(dataId, group, listener);
        }
    }

    private void updateRawContent(String dataId, String content) {
        rawContentCache.put(dataId, content);
        notifyConfigHandlers(dataId, content);
    }

    private void notifyConfigHandlers(String dataId, String content) {
        configHandlers.getOrDefault(dataId, new CopyOnWriteArrayList<>()).forEach(handler -> {
            try {
                handler.accept(content);
            } catch (Exception exception) {
                log.error("[NacosConfig] Config handler failed, dataId={}", dataId, exception);
            }
        });
    }
}
