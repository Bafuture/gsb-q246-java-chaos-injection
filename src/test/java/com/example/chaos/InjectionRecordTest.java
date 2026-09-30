package com.example.chaos;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class InjectionRecordTest {

    private TestClock clock;

    @BeforeEach
    void setUp() {
        clock = TestClock.startingAt(Instant.parse("2026-06-01T12:00:00Z"));
        Chaos.useClock(clock);
        Chaos.clearRecords();
    }

    @AfterEach
    void tearDown() {
        Chaos.reset();
    }

    @Test
    void recordContainsTimePointFaultTypeAndCaptureState() {
        IllegalStateException fault = new IllegalStateException("io");
        try (ChaosSession session = Chaos.newSession(
                Rule.onPoint("read-storage").fault(Faults.exception(fault)).build()).activate()) {
            try {
                Chaos.run("read-storage", Map.of("tenant", "gold"), () -> {
                });
            } catch (IllegalStateException e) {
                Chaos.markHandled(e);
            }
        }
        assertThat(Chaos.records()).hasSize(1);
        InjectionRecord record = Chaos.records().get(0);
        assertThat(record.time()).isEqualTo(Instant.parse("2026-06-01T12:00:00Z"));
        assertThat(record.point()).isEqualTo("read-storage");
        assertThat(record.faultType()).isEqualTo(FaultType.EXCEPTION);
        assertThat(record.thrown()).isSameAs(fault);
        assertThat(record.invocation()).isEqualTo(1);
        assertThat(record.sequence()).isEqualTo(1);
        assertThat(record.thread()).isSameAs(Thread.currentThread());
        assertThat(record.attributes()).containsEntry("tenant", "gold");
        assertThat(record.capture()).isEqualTo(CaptureState.HANDLED);
    }

    @Test
    void captureStateIsUnknownUntilMarked() {
        try (ChaosSession session = Chaos.newSession(
                Rule.onPoint("p").fault(Faults.exception("boom")).build()).activate()) {
            assertThatThrownBy(() -> Chaos.touch("p")).hasMessage("boom");
            assertThat(Chaos.recordsOf("p").get(0).capture()).isEqualTo(CaptureState.UNKNOWN);

            assertThatThrownBy(() -> Chaos.touch("p")).hasMessage("boom");
            Chaos.markEscaped("p");
        }
        assertThat(Chaos.recordsOf("p")).extracting(InjectionRecord::capture)
                .containsExactly(CaptureState.UNKNOWN, CaptureState.ESCAPED);
    }

    @Test
    void markHandledByPointNameMarksLatestInjection() {
        try (ChaosSession session = Chaos.newSession(
                Rule.onPoint("p").fault(Faults.exception("boom")).build()).activate()) {
            for (int i = 0; i < 2; i++) {
                try {
                    Chaos.touch("p");
                } catch (ChaosInjectionException e) {
                    Chaos.markHandled("p");
                }
            }
        }
        assertThat(Chaos.recordsOf("p")).extracting(InjectionRecord::capture)
                .containsExactly(CaptureState.HANDLED, CaptureState.HANDLED);
    }

    @Test
    void delayRecordCarriesDelayDuration() {
        try (ChaosSession session = Chaos.newSession(
                Rule.onPoint("p").fault(Faults.delay(Duration.ofSeconds(2))).build()).activate()) {
            Chaos.run("p", () -> {
            });
        }
        InjectionRecord record = Chaos.recordsOf("p").get(0);
        assertThat(record.faultType()).isEqualTo(FaultType.DELAY);
        assertThat(record.delay()).isEqualTo(Duration.ofSeconds(2));
        assertThat(record.time()).isEqualTo(Instant.parse("2026-06-01T12:00:02Z"));
    }

    @Test
    void noInjectionProducesNoRecord() {
        try (ChaosSession session = Chaos.newSession(
                Rule.onPoint("p").probability(0.0)
                        .fault(Faults.exception("never")).build()).activate()) {
            Chaos.run("p", () -> {
            });
            Chaos.run("unrelated", () -> {
            });
        }
        assertThat(Chaos.records()).isEmpty();
    }
}
