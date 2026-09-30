package com.example.gsb.chaos;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/** 作用域隔离：无作用域 / 其它线程 / 不匹配的上下文都不注入。 */
class ScopeIsolationTest {

    private static final Instant START = Instant.parse("2026-01-01T00:00:00Z");

    private final MutableChaosClock clock = MutableChaosClock.startingAt(START);
    private final InMemoryInjectionRecorder recorder = new InMemoryInjectionRecorder();
    private final ChaosEngine chaos = ChaosEngine.builder()
            .clock(clock)
            .recorder(recorder)
            .rule(ChaosRule.named("storage.read").fault(Fault.exception("boom")))
            .build();

    @Test
    void withoutScopeEngineIsFullyTransparent() {
        // 没有打开任何作用域：即使规则存在也透传
        assertThat(chaos.inject("storage.read", () -> "real")).isEqualTo("real");
        assertThat(recorder.records()).isEmpty();
    }

    @Test
    void injectionOnlyAffectsTheThreadThatOpenedTheScope() throws InterruptedException {
        AtomicReference<String> otherThreadResult = new AtomicReference<>();
        AtomicReference<Throwable> otherThreadError = new AtomicReference<>();
        Thread worker = new Thread(() -> {
            try {
                otherThreadResult.set(chaos.inject("storage.read", () -> "clean"));
            } catch (Throwable t) {
                otherThreadError.set(t);
            }
        }, "non-scoped-worker");

        try (ChaosScope ignored = chaos.openScope()) {
            worker.start();
            worker.join();
            // 打开作用域的当前线程会触发故障
            assertThatThrownBy(() -> chaos.inject("storage.read", () -> "x"))
                    .isInstanceOf(ChaosInjectedException.class);
        }

        assertThat(otherThreadError.get()).isNull();
        assertThat(otherThreadResult.get()).isEqualTo("clean");
    }

    @Test
    void rulesCanMatchOnContextAttributes() {
        ChaosEngine contextAware = ChaosEngine.builder()
                .clock(clock)
                .recorder(recorder)
                .rule(ChaosRule.named("storage.read")
                        .inContext("tenant", "test")
                        .fault(Fault.exception("test tenant fails")))
                .build();

        try (ChaosScope ignored = contextAware.openScope(ChaosContext.of("tenant", "prod"))) {
            assertThat(contextAware.inject("storage.read", () -> "prod-data")).isEqualTo("prod-data");
        }
        try (ChaosScope ignored = contextAware.openScope(ChaosContext.of("tenant", "test"))) {
            assertThatThrownBy(() -> contextAware.inject("storage.read", () -> "test-data"))
                    .isInstanceOf(ChaosInjectedException.class);
        }
    }

    @Test
    void threadRuleRestrictsFaultToNamedThreads() throws InterruptedException {
        ChaosEngine threadScoped = ChaosEngine.builder()
                .clock(clock)
                .rule(ChaosRule.named("storage.read")
                        .onThreadMatching("chaos-worker-\\d+")
                        .fault(Fault.exception("only chaos workers")))
                .build();

        AtomicReference<Throwable> workerError = new AtomicReference<>();
        Thread worker = new Thread(() -> {
            try (ChaosScope ignored = threadScoped.openScope()) {
                try {
                    threadScoped.inject("storage.read", () -> "x");
                } catch (Throwable t) {
                    workerError.set(t);
                }
            }
        }, "chaos-worker-1");
        worker.start();
        worker.join();

        assertThat(workerError.get()).isInstanceOf(ChaosInjectedException.class);

        try (ChaosScope ignored = threadScoped.openScope()) {
            assertThat(threadScoped.inject("storage.read", () -> "main-data")).isEqualTo("main-data");
        }
    }
}
