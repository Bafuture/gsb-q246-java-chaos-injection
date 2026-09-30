package com.example.chaos;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.Random;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FaultsTest {

    private TestClock clock;

    @BeforeEach
    void setUp() {
        clock = TestClock.startingAt(Instant.parse("2026-01-01T00:00:00Z"));
        Chaos.useClock(clock);
        Chaos.clearRecords();
    }

    @AfterEach
    void tearDown() {
        Chaos.reset();
    }

    @Test
    void exceptionFaultThrowsConfiguredException() {
        try (ChaosSession session = Chaos.newSession(
                Rule.onPoint("p").fault(Faults.exception(new IllegalStateException("boom"))).build())
                .activate()) {
            assertThatThrownBy(() -> Chaos.run("p", () -> {
            }))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("boom");
        }
        assertThat(Chaos.recordsOf("p")).hasSize(1);
    }

    @Test
    void delayFaultWaitsOnInjectedClockWithoutRealSleep() {
        try (ChaosSession session = Chaos.newSession(
                Rule.onPoint("p").fault(Faults.delay(Duration.ofSeconds(30))).build()).activate()) {
            long start = System.nanoTime();
            String result = Chaos.call("p", () -> "real");
            long realElapsedMs = (System.nanoTime() - start) / 1_000_000;

            assertThat(result).isEqualTo("real");
            assertThat(clock.now()).isEqualTo(Instant.parse("2026-01-01T00:00:30Z"));
            assertThat(realElapsedMs).isLessThan(2_000L);
        }
        InjectionRecord record = Chaos.recordsOf("p").get(0);
        assertThat(record.faultType()).isEqualTo(FaultType.DELAY);
        assertThat(record.delay()).isEqualTo(Duration.ofSeconds(30));
        assertThat(record.thrown()).isNull();
    }

    @Test
    void timeoutFaultAdvancesClockThenThrows() {
        try (ChaosSession session = Chaos.newSession(
                Rule.onPoint("p").fault(Faults.timeout(Duration.ofSeconds(5))).build()).activate()) {
            assertThatThrownBy(() -> Chaos.touch("p"))
                    .isInstanceOf(ChaosTimeoutException.class)
                    .satisfies(e -> assertThat(((ChaosTimeoutException) e).timeout())
                            .isEqualTo(Duration.ofSeconds(5)));
        }
        assertThat(clock.now()).isEqualTo(Instant.parse("2026-01-01T00:00:05Z"));
        assertThat(Chaos.recordsOf("p").get(0).faultType()).isEqualTo(FaultType.TIMEOUT);
    }

    @Test
    void returnErrorFaultSkipsBusinessAction() {
        AtomicInteger executed = new AtomicInteger();
        try (ChaosSession session = Chaos.newSession(
                Rule.onPoint("p").fault(Faults.returnError("error-result")).build()).activate()) {
            String result = Chaos.call("p", () -> {
                executed.incrementAndGet();
                return "real";
            });
            assertThat(result).isEqualTo("error-result");
        }
        assertThat(executed).hasValue(0);
        InjectionRecord record = Chaos.recordsOf("p").get(0);
        assertThat(record.faultType()).isEqualTo(FaultType.RETURN_ERROR);
        assertThat(record.returned()).isEqualTo("error-result");
    }

    @Test
    void probabilityZeroNeverFires() {
        AtomicInteger executed = new AtomicInteger();
        try (ChaosSession session = Chaos.newSession(
                Rule.onPoint("p").probability(0.0)
                        .fault(Faults.exception("boom")).build()).activate()) {
            for (int i = 0; i < 100; i++) {
                Chaos.run("p", executed::incrementAndGet);
            }
        }
        assertThat(executed).hasValue(100);
        assertThat(Chaos.records()).isEmpty();
    }

    @Test
    void probabilityFiresForExpectedRatioWithSeededRandom() {
        AtomicInteger injected = new AtomicInteger();
        try (ChaosSession session = Chaos.newSession(
                Rule.onPoint("p").probability(0.3, new Random(42))
                        .fault(Faults.exception("boom")).build()).activate()) {
            for (int i = 0; i < 2000; i++) {
                try {
                    Chaos.touch("p");
                } catch (ChaosInjectionException e) {
                    injected.incrementAndGet();
                }
            }
        }
        double ratio = injected.get() / 2000.0;
        assertThat(ratio).isBetween(0.25, 0.35);
    }
}
