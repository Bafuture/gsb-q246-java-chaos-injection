package com.example.gsb.chaos;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.Random;
import org.junit.jupiter.api.Test;

/** 比例生效：通过规则概率控制故障只对一定比例的调用生效。 */
class ProbabilityFaultTest {

    private static final Instant START = Instant.parse("2026-01-01T00:00:00Z");

    private ChaosEngine engineWithProbability(double probability, InMemoryInjectionRecorder recorder) {
        return ChaosEngine.builder()
                .clock(MutableChaosClock.startingAt(START))
                .random(new Random(42))
                .recorder(recorder)
                .rule(ChaosRule.named("flaky.point")
                        .probability(probability)
                        .fault(Fault.exception("flaky failure")))
                .build();
    }

    private int countFailures(ChaosEngine chaos, int invocations) {
        int failures = 0;
        try (ChaosScope ignored = chaos.openScope()) {
            for (int i = 0; i < invocations; i++) {
                try {
                    chaos.inject("flaky.point", () -> "ok");
                } catch (ChaosInjectedException expected) {
                    failures++;
                }
            }
        }
        return failures;
    }

    @Test
    void probabilityZeroNeverInjects() {
        InMemoryInjectionRecorder recorder = new InMemoryInjectionRecorder();
        ChaosEngine chaos = engineWithProbability(0.0, recorder);

        assertThat(countFailures(chaos, 500)).isZero();
        assertThat(recorder.records()).isEmpty();
    }

    @Test
    void probabilityOneAlwaysInjects() {
        InMemoryInjectionRecorder recorder = new InMemoryInjectionRecorder();
        ChaosEngine chaos = engineWithProbability(1.0, recorder);

        assertThat(countFailures(chaos, 100)).isEqualTo(100);
        assertThat(recorder.records()).hasSize(100);
    }

    @Test
    void probabilityHalfAffectsRoughlyHalfOfCalls() {
        InMemoryInjectionRecorder recorder = new InMemoryInjectionRecorder();
        ChaosEngine chaos = engineWithProbability(0.5, recorder);

        int failures = countFailures(chaos, 2_000);

        // 种子固定，结果确定；留出足够宽的统计区间
        assertThat(failures).isBetween(800, 1_200);
        assertThat(recorder.records()).hasSize(failures);
    }
}
