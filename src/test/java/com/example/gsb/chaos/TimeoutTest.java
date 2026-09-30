package com.example.gsb.chaos;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/** 超时控制：基于可注入时钟测量，延迟故障可确定性地触发超时，无需真实等待。 */
class TimeoutTest {

    private static final Instant START = Instant.parse("2026-03-01T00:00:00Z");

    @Test
    void injectedLatencyBeyondTimeoutRaisesChaosTimeoutException() {
        MutableChaosClock clock = MutableChaosClock.startingAt(START);
        ChaosEngine chaos = ChaosEngine.builder()
                .clock(clock)
                .rule(ChaosRule.named("rpc.call")
                        .fault(Fault.latency(Duration.ofSeconds(10))))
                .build();

        long wallStart = System.nanoTime();
        try (ChaosScope ignored = chaos.openScope()) {
            assertThatThrownBy(() -> chaos.withTimeout(
                    "rpc.call", Duration.ofSeconds(3), () -> "ok"))
                    .isInstanceOf(ChaosTimeoutException.class)
                    .hasMessageContaining("exceeded timeout");
        }
        long wallMillis = (System.nanoTime() - wallStart) / 1_000_000;

        assertThat(clock.now()).isEqualTo(START.plusSeconds(10));
        assertThat(wallMillis).isLessThan(5_000);
    }

    @Test
    void fastCallCompletesWithinTimeout() {
        MutableChaosClock clock = MutableChaosClock.startingAt(START);
        ChaosEngine chaos = ChaosEngine.builder()
                .clock(clock)
                .rule(ChaosRule.named("rpc.call")
                        .fault(Fault.latency(Duration.ofMillis(500))))
                .build();

        try (ChaosScope ignored = chaos.openScope()) {
            String result = chaos.withTimeout(
                    "rpc.call", Duration.ofSeconds(2), () -> "ok");
            assertThat(result).isEqualTo("ok");
        }
    }

    @Test
    void timeoutExceptionExposesPointTimeoutAndElapsed() {
        MutableChaosClock clock = MutableChaosClock.startingAt(START);
        ChaosEngine chaos = ChaosEngine.builder()
                .clock(clock)
                .rule(ChaosRule.named("rpc.call")
                        .fault(Fault.latency(Duration.ofSeconds(8))))
                .build();

        try (ChaosScope ignored = chaos.openScope()) {
            assertThatThrownBy(() -> chaos.withTimeout(
                    "rpc.call", Duration.ofSeconds(1), () -> "ok"))
                    .isInstanceOfSatisfying(ChaosTimeoutException.class, error -> {
                        assertThat(error.pointName()).isEqualTo("rpc.call");
                        assertThat(error.timeout()).isEqualTo(Duration.ofSeconds(1));
                        assertThat(error.elapsed()).isEqualTo(Duration.ofSeconds(8));
                    });
        }
    }
}
