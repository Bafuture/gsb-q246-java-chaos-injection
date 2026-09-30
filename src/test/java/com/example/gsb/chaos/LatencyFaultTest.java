package com.example.gsb.chaos;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/** 延迟故障：通过可注入时钟推进时间，测试不发生真实等待。 */
class LatencyFaultTest {

    private static final Instant START = Instant.parse("2026-01-01T00:00:00Z");

    @Test
    void latencyFaultAdvancesInjectedClockWithoutRealWaiting() {
        MutableChaosClock clock = MutableChaosClock.startingAt(START);
        InMemoryInjectionRecorder recorder = new InMemoryInjectionRecorder();
        ChaosEngine chaos = ChaosEngine.builder()
                .clock(clock)
                .recorder(recorder)
                .rule(ChaosRule.named("message.send.after")
                        .fault(Fault.latency(Duration.ofSeconds(30))))
                .build();

        long wallStart = System.nanoTime();
        String result;
        try (ChaosScope ignored = chaos.openScope()) {
            result = chaos.inject("message.send.after", () -> "sent");
        }
        long wallMillis = (System.nanoTime() - wallStart) / 1_000_000;

        assertThat(result).isEqualTo("sent");
        // 注入时钟前进了 30 秒……
        assertThat(clock.now()).isEqualTo(START.plusSeconds(30));
        // ……但真实时间几乎没有流逝（远小于 30 秒）
        assertThat(wallMillis).isLessThan(5_000);

        assertThat(recorder.records()).hasSize(1);
        InjectionRecord record = recorder.records().get(0);
        assertThat(record.faultType()).isEqualTo(FaultType.LATENCY);
        assertThat(record.caughtByBusiness()).isTrue();
    }

    @Test
    void latencyFaultOnlyAppliesToMatchedPoint() {
        MutableChaosClock clock = MutableChaosClock.startingAt(START);
        ChaosEngine chaos = ChaosEngine.builder()
                .clock(clock)
                .rule(ChaosRule.named("slow.point").fault(Fault.latency(Duration.ofSeconds(10))))
                .build();

        try (ChaosScope ignored = chaos.openScope()) {
            assertThat(chaos.inject("fast.point", () -> "ok")).isEqualTo("ok");
        }
        assertThat(clock.now()).isEqualTo(START);
    }
}
