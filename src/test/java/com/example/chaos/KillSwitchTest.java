package com.example.chaos;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 一键关闭：关闭后所有注入点直接透传，行为与未接入框架时一致。
 */
class KillSwitchTest {

    @AfterEach
    void tearDown() {
        Chaos.reset();
    }

    /** 未接入框架的原始业务逻辑。 */
    private List<String> businessWithoutChaos(int times) {
        List<String> results = new ArrayList<>();
        for (int i = 0; i < times; i++) {
            results.add("value-" + i);
        }
        return results;
    }

    /** 接入框架的同一业务逻辑。 */
    private List<String> businessWithChaos(int times) {
        List<String> results = new ArrayList<>();
        for (int i = 0; i < times; i++) {
            int index = i;
            results.add(Chaos.call("produce", () -> "value-" + index));
        }
        return results;
    }

    @Test
    void disabledFrameworkPassesThroughExactlyLikeNoFramework() {
        try (ChaosSession session = Chaos.newSession(
                Rule.onPoint("produce").fault(Faults.exception("boom")).build(),
                Rule.onPoint("produce").fault(Faults.returnError("error")).build())
                .activate()) {
            Chaos.disable();

            List<String> withChaos = businessWithChaos(5);
            List<String> withoutChaos = businessWithoutChaos(5);

            assertThat(withChaos).isEqualTo(withoutChaos);
            assertThat(Chaos.records()).isEmpty();
        }
    }

    @Test
    void enableRestoresInjection() {
        AtomicInteger executed = new AtomicInteger();
        try (ChaosSession session = Chaos.newSession(
                Rule.onPoint("p").fault(Faults.exception("boom")).build()).activate()) {
            Chaos.disable();
            Chaos.run("p", executed::incrementAndGet);
            assertThat(executed).hasValue(1);

            Chaos.enable();
            try {
                Chaos.run("p", executed::incrementAndGet);
            } catch (ChaosInjectionException expected) {
                // 注入恢复
            }
            assertThat(executed).hasValue(1);
        }
        assertThat(Chaos.recordsOf("p")).hasSize(1);
    }

    @Test
    void disabledFrameworkDoesNotAdvanceInjectedClock() {
        TestClock clock = TestClock.startingAt(java.time.Instant.EPOCH);
        Chaos.useClock(clock);
        try (ChaosSession session = Chaos.newSession(
                Rule.onPoint("p").fault(Faults.delay(Duration.ofHours(1))).build()).activate()) {
            Chaos.disable();
            Chaos.touch("p");
            assertThat(clock.now()).isEqualTo(java.time.Instant.EPOCH);
        }
    }

    @Test
    void resetReEnablesFramework() {
        Chaos.disable();
        Chaos.reset();
        assertThat(Chaos.enabled()).isTrue();
    }
}
