package com.ai.operations.gateway.infrastructure.config;

import com.ai.operations.gateway.infrastructure.enums.NacosDataIdEnum;
import com.alibaba.nacos.api.NacosFactory;
import com.alibaba.nacos.api.config.ConfigService;
import com.alibaba.nacos.api.config.listener.Listener;
import com.alibaba.nacos.api.exception.NacosException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class NacosConfigTest {

    private static final String INDEX = "gateway-index.properties";
    private static final String GROUP = "gateway";
    private static final NacosDataIdEnum BUSINESS = NacosDataIdEnum.AI_GATEWAY_ROUTES;
    private final ConfigService service = mock(ConfigService.class);
    private final NacosConfig config = new NacosConfig();

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(config, "configService", service);
        ReflectionTestUtils.setField(config, "indexDataId", INDEX);
        ReflectionTestUtils.setField(config, "group", GROUP);
    }

    @Test
    void initialIndexGetFailureStillInstallsListenerAndRecovers() throws Exception {
        when(service.getConfig(INDEX, GROUP, 5000)).thenThrow(new NacosException(500, "get failed"));
        ReflectionTestUtils.invokeMethod(config, "listenIndexDataId");
        Listener indexListener = listenerFor(INDEX);
        when(service.getConfig(BUSINESS.dataId(), GROUP, 5000)).thenReturn("valid");

        indexListener.receiveConfigInfo(BUSINESS.dataId() + "=true");

        listenerFor(BUSINESS.dataId());
        assertEquals("valid", config.getRawContent(BUSINESS));
    }

    @Test
    void initialBusinessGetFailureStillInstallsListenerAndKeepsRegistration() throws Exception {
        when(service.getConfig(BUSINESS.dataId(), GROUP, 5000))
                .thenThrow(new NacosException(500, "get failed"));
        ReflectionTestUtils.invokeMethod(config, "listenBusinessDataId", BUSINESS.dataId());
        Listener listener = listenerFor(BUSINESS.dataId());
        assertTrue(registeredDataIds().contains(BUSINESS.dataId()));
        ReflectionTestUtils.invokeMethod(config, "listenBusinessDataId", BUSINESS.dataId());
        verify(service, times(1)).addListener(eq(BUSINESS.dataId()), eq(GROUP), any(Listener.class));
        List<String> updates = new ArrayList<>();
        config.registerConfigHandler(BUSINESS, updates::add);

        listener.receiveConfigInfo("recovered");

        assertEquals(List.of("recovered"), updates);
    }

    @Test
    void indexAddListenerFailureThrows() throws Exception {
        doThrow(new NacosException(500, "add failed"))
                .when(service).addListener(eq(INDEX), eq(GROUP), any(Listener.class));
        assertThrows(IllegalStateException.class,
                () -> ReflectionTestUtils.invokeMethod(config, "listenIndexDataId"));
    }

    @Test
    void businessAddListenerFailureThrowsAndRemovesRegistration() throws Exception {
        doThrow(new NacosException(500, "add failed"))
                .when(service).addListener(eq(BUSINESS.dataId()), eq(GROUP), any(Listener.class));
        assertThrows(IllegalStateException.class,
                () -> ReflectionTestUtils.invokeMethod(config, "listenBusinessDataId", BUSINESS.dataId()));
        assertFalse(registeredDataIds().contains(BUSINESS.dataId()));
    }

    @Test
    void configServiceCreationFailureThrowsAtStartup() {
        ReflectionTestUtils.setField(config, "serverAddr", "mock-server");
        ReflectionTestUtils.setField(config, "namespace", "test");
        try (var factory = mockStatic(NacosFactory.class)) {
            factory.when(() -> NacosFactory.createConfigService(any(Properties.class)))
                    .thenThrow(new NacosException(500, "creation failed"));
            assertThrows(IllegalStateException.class, config::init);
        }
    }

    @Test
    void initialBusinessContentIsReplayedToLateHandler() throws Exception {
        when(service.getConfig(BUSINESS.dataId(), GROUP, 5000)).thenReturn("valid");
        ReflectionTestUtils.invokeMethod(config, "listenBusinessDataId", BUSINESS.dataId());
        List<String> updates = new ArrayList<>();
        config.registerConfigHandler(BUSINESS, updates::add);
        assertEquals(List.of("valid"), updates);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = " \n\t")
    void initialEmptyBusinessContentIsCachedAndReplayed(String content) throws Exception {
        when(service.getConfig(BUSINESS.dataId(), GROUP, 5000)).thenReturn(content);
        ReflectionTestUtils.invokeMethod(config, "listenBusinessDataId", BUSINESS.dataId());
        List<String> updates = new ArrayList<>();
        config.registerConfigHandler(BUSINESS, updates::add);
        assertEquals("", config.getRawContent(BUSINESS));
        assertEquals(List.of(""), updates);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = " \n\t")
    void emptyBusinessUpdateWithdrawsAndIsReplayedToLateHandler(String content) throws Exception {
        when(service.getConfig(BUSINESS.dataId(), GROUP, 5000)).thenReturn("valid");
        ReflectionTestUtils.invokeMethod(config, "listenBusinessDataId", BUSINESS.dataId());
        List<String> updates = new ArrayList<>();
        config.registerConfigHandler(BUSINESS, updates::add);

        listenerFor(BUSINESS.dataId()).receiveConfigInfo(content);

        assertEquals(List.of("valid", ""), updates);
        List<String> lateUpdates = new ArrayList<>();
        config.registerConfigHandler(BUSINESS, lateUpdates::add);
        assertEquals(List.of(""), lateUpdates);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" \n\t", "unsupported=true"})
    void indexRemovalUnsubscribesAndCachesWithdrawal(String content) throws Exception {
        when(service.getConfig(BUSINESS.dataId(), GROUP, 5000)).thenReturn("valid");
        ReflectionTestUtils.invokeMethod(config, "listenBusinessDataId", BUSINESS.dataId());
        Listener businessListener = listenerFor(BUSINESS.dataId());
        List<String> updates = new ArrayList<>();
        config.registerConfigHandler(BUSINESS, updates::add);

        ReflectionTestUtils.invokeMethod(config, "onIndexChanged", content);

        assertTrue(registeredDataIds().isEmpty());
        verify(service).removeListener(BUSINESS.dataId(), GROUP, businessListener);
        assertEquals(List.of("valid", ""), updates);
        List<String> lateUpdates = new ArrayList<>();
        config.registerConfigHandler(BUSINESS, lateUpdates::add);
        assertEquals(List.of(""), lateUpdates);
    }

    private Listener listenerFor(String dataId) throws Exception {
        ArgumentCaptor<Listener> listener = ArgumentCaptor.forClass(Listener.class);
        verify(service).addListener(eq(dataId), eq(GROUP), listener.capture());
        return listener.getValue();
    }

    @SuppressWarnings("unchecked")
    private Set<String> registeredDataIds() {
        return (Set<String>) ReflectionTestUtils.getField(config, "registeredDataIds");
    }
}

