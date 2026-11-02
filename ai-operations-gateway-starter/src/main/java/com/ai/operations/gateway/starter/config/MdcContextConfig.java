package com.ai.operations.gateway.starter.config;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.MDC;
import org.springframework.context.annotation.Configuration;
import reactor.core.publisher.Hooks;
import reactor.core.publisher.Operators;
import reactor.util.context.ContextView;

import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 在 Reactor Context 与 MDC 之间传递请求链路信息。
 *
 * @author HUANGcong
 * @since 2026-09-24
 */
@Configuration
public class MdcContextConfig {

    private static final String MDC_HOOK_KEY  = "MDC_CONTEXT_LIFTER";
    private static final String MDC_TRACE_ID = "traceId";
    private static final String MDC_CALL_CHAIN = "callChain";
    private static final AtomicInteger ACTIVE_CONTEXTS = new AtomicInteger();

    private boolean hookRegistered;

    /**
     * 应用启动后注册 Reactor 全局钩子。
     * Hooks.onEachOperator：为每个 Reactor 操作符的订阅者添加 MDC 装饰逻辑。
     */
    @PostConstruct
    public void registerMdcHooks() {
        if (ACTIVE_CONTEXTS.getAndIncrement() == 0) {
            Hooks.onEachOperator(MDC_HOOK_KEY,
                    Operators.lift((scannable, subscriber) -> new MdcContextSubscriber<>(subscriber))
            );
        }
        hookRegistered = true;
    }

    @PreDestroy
    public void resetMdcHooks() {
        if (!hookRegistered) {
            return;
        }
        hookRegistered = false;
        if (ACTIVE_CONTEXTS.decrementAndGet() == 0) {
            Hooks.resetOnEachOperator(MDC_HOOK_KEY);
        }
    }

    /**
     * 在订阅组装阶段执行日志时，临时将 Reactor Context 映射到 MDC。
     * Hooks.onEachOperator 仅覆盖订阅信号回调，此方法用于 deferContextual 等信号触发前的场景。
     */
    public static void withMdc(ContextView context, Runnable action) {
        Map<String, String> previousEntries = new java.util.HashMap<>(2);
        previousEntries.put(MDC_TRACE_ID, MDC.get(MDC_TRACE_ID));
        previousEntries.put(MDC_CALL_CHAIN, MDC.get(MDC_CALL_CHAIN));
        putOrRemove(MDC_TRACE_ID, context.getOrEmpty(MDC_TRACE_ID).map(Object::toString).orElse(null));
        putOrRemove(MDC_CALL_CHAIN, context.getOrEmpty(MDC_CALL_CHAIN).map(Object::toString).orElse(null));
        try {
            action.run();
        } finally {
            previousEntries.forEach(MdcContextConfig::putOrRemove);
        }
    }

    private static void putOrRemove(String key, String value) {
        if (value == null) {
            MDC.remove(key);
        } else {
            MDC.put(key, value);
        }
    }

    /**
     * 自定义 Subscriber 装饰器：在订阅及信号执行前从 Reactor Context 还原 MDC，
     * 执行完后恢复原上下文，防止线程池线程复用时污染。
     */
    private static class MdcContextSubscriber<T>
            implements reactor.core.CoreSubscriber<T> {

        private final reactor.core.CoreSubscriber<? super T> actual;

        MdcContextSubscriber(reactor.core.CoreSubscriber<? super T> actual) {
            this.actual = actual;
        }

        @Override
        public reactor.util.context.Context currentContext() {
            return actual.currentContext();
        }

        @Override
        public void onSubscribe(org.reactivestreams.Subscription s) {
            runWithMdc(() -> actual.onSubscribe(s));
        }

        @Override
        public void onNext(T t) {
            runWithMdc(() -> actual.onNext(t));
        }

        @Override
        public void onError(Throwable throwable) {
            runWithMdc(() -> actual.onError(throwable));
        }

        @Override
        public void onComplete() {
            runWithMdc(actual::onComplete);
        }

        private void runWithMdc(Runnable action) {
            try (MdcScope ignored = withMdc()) {
                action.run();
            }
        }

        /**
         * 从 Reactor Context 读取 traceId / callChain，写入 MDC。
         * Context 缺少某个键时临时清除对应 MDC，防止复用线程遗留旧链路信息。
         */
        private MdcScope withMdc() {
            reactor.util.context.Context ctx = actual.currentContext();
            Map<String, String> previousEntries = new java.util.HashMap<>(2);
            previousEntries.put(MDC_TRACE_ID, MDC.get(MDC_TRACE_ID));
            previousEntries.put(MDC_CALL_CHAIN, MDC.get(MDC_CALL_CHAIN));

            putOrRemove(MDC_TRACE_ID, ctx.getOrEmpty(MDC_TRACE_ID).map(Object::toString).orElse(null));
            putOrRemove(MDC_CALL_CHAIN, ctx.getOrEmpty(MDC_CALL_CHAIN).map(Object::toString).orElse(null));

            return () -> previousEntries.forEach(MdcContextConfig::putOrRemove);
        }

        @FunctionalInterface
        private interface MdcScope extends AutoCloseable {
            @Override
            void close();
        }
    }
}

