package com.example.gsb.chaos;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import org.junit.jupiter.api.Test;

/** 异常故障：在注入点抛出异常，业务逻辑不执行。 */
class ExceptionFaultTest {

    private static final Instant START = Instant.parse("2026-01-01T00:00:00Z");

    private final MutableChaosClock clock = MutableChaosClock.startingAt(START);
    private final InMemoryInjectionRecorder recorder = new InMemoryInjectionRecorder();

    private ChaosEngine engineWith(ChaosRule rule) {
        return ChaosEngine.builder()
                .clock(clock)
                .recorder(recorder)
                .rule(rule)
                .build();
    }

    @Test
    void throwsConfiguredExceptionAtInjectionPoint() {
        ChaosEngine chaos = engineWith(ChaosRule.named("storage.read.before")
                .fault(Fault.exception(() -> new IllegalStateException("disk on fire"))));

        try (ChaosScope ignored = chaos.openScope()) {
            assertThatThrownBy(() -> chaos.inject("storage.read.before", () -> "data"))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("disk on fire");
        }

        assertThat(recorder.records()).hasSize(1);
        InjectionRecord record = recorder.records().get(0);
        assertThat(record.pointName()).isEqualTo("storage.read.before");
        assertThat(record.faultType()).isEqualTo(FaultType.EXCEPTION);
        assertThat(record.caughtByBusiness()).isFalse();
    }

    @Test
    void businessRecoveryIsRecordedAsCaught() {
        ChaosEngine chaos = engineWith(ChaosRule.named("storage.read.before")
                .fault(Fault.exception("storage unavailable")));

        String result;
        try (ChaosScope ignored = chaos.openScope()) {
            result = chaos.inject("storage.read.before",
                    () -> "data",
                    new FaultHandler<>() {
                        @Override
                        public String onError(RuntimeException error) {
                            return "fallback-from-cache";
                        }
                    });
        }

        assertThat(result).isEqualTo("fallback-from-cache");
        assertThat(recorder.records()).hasSize(1);
        assertThat(recorder.records().get(0).caughtByBusiness()).isTrue();
    }

    @Test
    void pointsWithoutMatchingRulePassThrough() {
        ChaosEngine chaos = engineWith(ChaosRule.named("storage.read.before")
                .fault(Fault.exception("boom")));

        try (ChaosScope ignored = chaos.openScope()) {
            assertThat(chaos.inject("cache.read", () -> "cached")).isEqualTo("cached");
        }
        assertThat(recorder.records()).isEmpty();
    }
}
