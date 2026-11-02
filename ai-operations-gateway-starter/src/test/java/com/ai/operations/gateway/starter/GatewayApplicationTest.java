package com.ai.operations.gateway.starter;

import com.ai.operations.gateway.application.provider.GatewayRoutesConfigProvider;
import com.ai.operations.gateway.infrastructure.config.NacosConfig;
import com.ai.operations.gateway.infrastructure.enums.NacosDataIdEnum;
import com.ai.operations.gateway.starter.filter.DownstreamHeaderGlobalFilter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.mock.mockito.MockReset;
import org.springframework.boot.web.reactive.context.ReactiveWebServerApplicationContext;
import org.springframework.cloud.gateway.filter.headers.HttpHeadersFilter;
import org.springframework.cloud.gateway.filter.headers.XForwardedHeadersFilter;
import org.springframework.cloud.gateway.route.Route;
import org.springframework.cloud.gateway.support.ConfigurationService;
import org.springframework.cloud.gateway.support.ServerWebExchangeUtils;
import org.springframework.core.annotation.AnnotationAwareOrderComparator;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.test.util.ReflectionTestUtils;
import reactor.core.publisher.Flux;

import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@SpringBootTest(classes = GatewayApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "spring.config.import=",
                "spring.cloud.nacos.config.enabled=false",
                "spring.cloud.nacos.config.import-check.enabled=false",
                "spring.cloud.nacos.discovery.enabled=false",
                "spring.cloud.vault.enabled=false",
                "app.environment=test",
                "logging.file.path=target/test-logs",
                "management.server.port=0"
        })
class GatewayApplicationTest {
    @Autowired
    private ReactiveWebServerApplicationContext context;

    @MockBean(reset = MockReset.NONE)
    private NacosConfig nacosConfig;

    @MockBean
    private LettuceConnectionFactory redisConnectionFactory;

    @MockBean(name = "reactiveStringRedisTemplate")
    private ReactiveStringRedisTemplate redisTemplate;

    @BeforeEach
    void stubRedisExecution() {
        when(redisTemplate.execute(any(), anyList(), anyList())).thenReturn(Flux.empty());
    }

    @Test
    void startsHttpServerAndInjectsRealConfigurationServiceIntoRoutesProvider() {
        assertThat(context.isActive()).isTrue();
        assertThat(context.getWebServer()).isNotNull();
        assertThat(context.getWebServer().getPort()).isPositive();
        GatewayRoutesConfigProvider provider = context.getBean(GatewayRoutesConfigProvider.class);
        assertThat(ReflectionTestUtils.getField(provider, "configurationService"))
                .isSameAs(context.getBean(ConfigurationService.class));
        assertThat(ReflectionTestUtils.getField(provider, "applicationReady")).isEqualTo(true);
        assertThat(provider.isAuthWhiteListed("/ai-operations/api/access/login")).isTrue();
        assertThat(provider.isAuthWhiteListed("/ai-agent/api/private")).isFalse();
        verify(nacosConfig).registerConfigHandler(eq(NacosDataIdEnum.AI_GATEWAY_ROUTES), any());
        verify(nacosConfig).registerConfigHandler(eq(NacosDataIdEnum.AI_GATEWAY_DOWNSTREAM_HEADERS), any());
    }

    @Test
    void sortedFrameworkHeadersKeepOnlyRemoteIpForDefaultAllowedApplication() {
        HttpHeaders result = filterRequest("ai-operations");

        assertThat(result.get("X-Forwarded-For")).containsExactly("192.0.2.10");
        assertThat(result.getFirst("X-Internal-Session-Id")).isEqualTo("session-1");
        assertThat(result).doesNotContainKeys("X-Real-IP", "Forwarded");
    }

    @Test
    void sortedFrameworkHeadersRemoveForwardedForForDisallowedApplication() {
        HttpHeaders result = filterRequest("ai-agent");

        assertThat(result).doesNotContainKeys("X-Forwarded-For", "X-Internal-Session-Id", "X-Real-IP", "Forwarded");
    }

    private HttpHeaders filterRequest(String application) {
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("http://gateway.example/api/test")
                        .remoteAddress(new InetSocketAddress("192.0.2.10", 12345))
                        .header("X-Forwarded-For", "198.51.100.1")
                        .header("X-Real-IP", "198.51.100.2")
                        .header("Forwarded", "for=198.51.100.3")
                        .header("X-Internal-Session-Id", "session-1"));
        exchange.getAttributes().put(ServerWebExchangeUtils.GATEWAY_ROUTE_ATTR,
                Route.async().id("test-route").uri("lb://" + application).predicate(value -> true).build());
        List<HttpHeadersFilter> filters = new ArrayList<>(context.getBeansOfType(HttpHeadersFilter.class).values());
        AnnotationAwareOrderComparator.sort(filters);
        XForwardedHeadersFilter frameworkFilter = context.getBean(XForwardedHeadersFilter.class);
        assertThat(filters.indexOf(frameworkFilter)).isLessThan(filters.indexOf(context.getBean(DownstreamHeaderGlobalFilter.class)));
        assertThat(frameworkFilter.filter(exchange.getRequest().getHeaders(), exchange).getFirst("X-Forwarded-For"))
                .contains("198.51.100.1", "192.0.2.10");
        return HttpHeadersFilter.filterRequest(filters, exchange);
    }
}

