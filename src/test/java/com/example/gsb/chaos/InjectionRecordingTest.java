package com.example.gsb.chaos;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/** 注入记录内容：时间、注入点、故障类型、是否被业务正确捕获。 */
class InjectionRecordingTest {

    private static final Instant START = Instant.parse("2026-06-01T08:00:00Z");

    @Test
    void recordsTimePointTypeAndCaughtFlagForExceptionFault() {
        MutableChaosClock clock = MutableChaosClock.startingAt(START);
        InMemoryInjectionRecorder recorder = new InMemoryInjectionRecorder();
        ChaosEngine chaos = ChaosEngine.builder()
                .clock(clock)
                .recorder(recorder)
                .rule(ChaosRule.named("storage.read.before").fault(Fault.exception("io error")))
                .build();

        try (ChaosScope ignored = chaos.openScope()) {
            chaos.inject("storage.read.before", () -> "ignored",
                    new FaultHandler<>() {
                        @Override
                        public String onError(RuntimeException error) {
                            return "recovered";
                        }
                    });
        }

        assertThat(recorder.records()).hasSize(1);
        InjectionRecord record = recorder.records().get(0);
        assertThat(record.occurredAt()).isEqualTo(START);
        assertThat(record.pointName()).isEqualTo("storage.read.before");
        assertThat(record.faultType()).isEqualTo(FaultType.EXCEPTION);
        assertThat(record.caughtByBusiness()).isTrue();
        assertThat(record.detail()).contains("io error");
    }

    @Test
    void recordsLatencyWithElapsedLogicalTime() {
        MutableChaosClock clock = MutableChaosClock.startingAt(START);
        InMemoryInjectionRecorder recorder = new InMemoryInjectionRecorder();
        ChaosEngine chaos = ChaosEngine.builder()
                .clock(clock)
                .recorder(recorder)
                .rule(ChaosRule.named("rpc.call").fault(Fault.latency(Duration.ofSeconds(15))))
                .build();

        try (ChaosScope ignored = chaos.openScope()) {
            chaos.inject("rpc.call", () -> "ok");
        }

        InjectionRecord record = recorder.records().get(0);
        assertThat(record.occurredAt()).isEqualTo(START);
        assertThat(record.faultType()).isEqualTo(FaultType.LATENCY);
        assertThat(record.caughtByBusiness()).isTrue();
        assertThat(record.detail()).contains("PT15S");
    }

    @Test
    void noRecordWhenNoFaultInjected() {
        MutableChaosClock clock = MutableChaosClock.startingAt(START);
        InMemoryInjectionRecorder recorder = new InMemoryInjectionRecorder();
        ChaosEngine chaos = ChaosEngine.builder()
                .clock(clock)
                .recorder(recorder)
                .rule(ChaosRule.named("storage.read").fault(Fault.exception("boom")))
                .build();

        try (ChaosScope ignored = chaos.openScope()) {
            chaos.inject("other.point", () -> "ok");
        }
        assertThat(recorder.records()).isEmpty();
    }
}
