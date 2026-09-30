package com.example.gsb.chaos;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/** 一键关闭：关闭后所有注入点透传，行为与未接入框架时一致。 */
class KillSwitchTest {

    private static final Instant START = Instant.parse("2026-09-01T00:00:00Z");

    private final MutableChaosClock clock = MutableChaosClock.startingAt(START);
    private final InMemoryInjectionRecorder recorder = new InMemoryInjectionRecorder();
    private final ChaosEngine chaos = ChaosEngine.builder()
            .clock(clock)
            .recorder(recorder)
            .rules(
                    ChaosRule.named("point.exception").fault(Fault.exception("boom")),
                    ChaosRule.named("point.latency").fault(Fault.latency(Duration.ofSeconds(60))),
                    ChaosRule.named("point.corrupt").fault(Fault.corruptResult("wrong")))
            .build();

    @Test
    void disabledEngineBehavesExactlyLikeRawBusinessCalls() {
        chaos.setEnabled(false);

        try (ChaosScope ignored = chaos.openScope()) {
            // 与“未接入框架时直接执行业务逻辑”逐点对比
            assertThat(chaos.inject("point.exception", () -> "a"))
                    .isEqualTo("a");
            assertThat(chaos.inject("point.latency", () -> "b"))
                    .isEqualTo("b");
            assertThat(chaos.inject("point.corrupt", () -> "real-data"))
                    .isEqualTo("real-data");
            chaos.inject("point.latency", () -> {
            });
        }

        // 时钟没有被任何延迟故障推动
        assertThat(clock.now()).isEqualTo(START);
        // 没有任何注入记录
        assertThat(recorder.records()).isEmpty();
    }

    @Test
    void injectionResumesAfterReEnabling() {
        chaos.setEnabled(false);
        try (ChaosScope ignored = chaos.openScope()) {
            assertThat(chaos.inject("point.exception", () -> "ok")).isEqualTo("ok");
        }

        chaos.setEnabled(true);
        try (ChaosScope ignored = chaos.openScope()) {
            assertThat(chaos.inject("point.exception", () -> "ok",
                    new FaultHandler<>() {
                        @Override
                        public String onError(RuntimeException error) {
                            return "recovered";
                        }
                    })).isEqualTo("recovered");
        }

        assertThat(recorder.records()).hasSize(1);
        assertThat(recorder.records().get(0).caughtByBusiness()).isTrue();
    }
}
