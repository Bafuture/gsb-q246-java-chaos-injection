package com.example.gsb.chaos;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import org.junit.jupiter.api.Test;

/** 结果替换故障：业务照常执行，但调用方拿到错误结果。 */
class CorruptResultFaultTest {

    private static final Instant START = Instant.parse("2026-01-01T00:00:00Z");

    private final MutableChaosClock clock = MutableChaosClock.startingAt(START);
    private final InMemoryInjectionRecorder recorder = new InMemoryInjectionRecorder();

    private ChaosEngine corruptingEngine() {
        return ChaosEngine.builder()
                .clock(clock)
                .recorder(recorder)
                .rule(ChaosRule.named("storage.read")
                        .fault(Fault.corruptResult("corrupted-bytes")))
                .build();
    }

    @Test
    void corruptResultReplacesBusinessResult() {
        ChaosEngine chaos = corruptingEngine();

        String result;
        try (ChaosScope ignored = chaos.openScope()) {
            result = chaos.inject("storage.read", () -> "real-data");
        }

        assertThat(result).isEqualTo("corrupted-bytes");
        assertThat(recorder.records()).hasSize(1);
        InjectionRecord record = recorder.records().get(0);
        assertThat(record.faultType()).isEqualTo(FaultType.CORRUPT_RESULT);
        // 业务未做校验，错误结果被直接吞掉
        assertThat(record.caughtByBusiness()).isFalse();
    }

    @Test
    void businessValidationCatchesCorruptResult() {
        ChaosEngine chaos = corruptingEngine();
        FaultHandler<String> validating = new FaultHandler<>() {
            @Override
            public String onResult(String result) {
                if (!result.startsWith("real")) {
                    throw new IllegalStateException("checksum mismatch");
                }
                return result;
            }
        };

        try (ChaosScope ignored = chaos.openScope()) {
            assertThatThrownBy(() -> chaos.inject("storage.read", () -> "real-data", validating))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("checksum mismatch");
        }

        assertThat(recorder.records()).hasSize(1);
        // 业务校验拒绝了错误结果，视为正确捕获
        assertThat(recorder.records().get(0).caughtByBusiness()).isTrue();
    }
}
