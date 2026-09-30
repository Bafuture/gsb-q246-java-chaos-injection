package com.example.gsb.chaos;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.Random;
import org.junit.jupiter.api.Test;

/** 规则匹配：调用次数区间、名称正则、自定义条件、多规则优先级。 */
class RuleMatchingTest {

    private static final Instant START = Instant.parse("2026-01-01T00:00:00Z");

    private ChaosEngine build(ChaosRule... rules) {
        return ChaosEngine.builder()
                .clock(MutableChaosClock.startingAt(START))
                .random(new Random(1))
                .rules(rules)
                .build();
    }

    private String callOnce(ChaosEngine chaos) {
        return chaos.inject("storage.read", () -> "ok");
    }

    @Test
    void invocationCountRangeFiresOnlyWithinRange() {
        ChaosEngine chaos = build(ChaosRule.named("storage.read")
                .invocationsBetween(2, 3)
                .fault(Fault.exception("fails on 2nd and 3rd call")));

        try (ChaosScope ignored = chaos.openScope()) {
            assertThat(callOnce(chaos)).isEqualTo("ok");
            assertThatThrownBy(() -> callOnce(chaos)).isInstanceOf(ChaosInjectedException.class);
            assertThatThrownBy(() -> callOnce(chaos)).isInstanceOf(ChaosInjectedException.class);
            assertThat(callOnce(chaos)).isEqualTo("ok");
        }
    }

    @Test
    void nameRegexSelectsPointsByPattern() {
        ChaosEngine chaos = build(ChaosRule.matching("storage\\..*")
                .fault(Fault.exception("storage down")));

        try (ChaosScope ignored = chaos.openScope()) {
            assertThatThrownBy(() -> chaos.inject("storage.read", () -> "ok"))
                    .isInstanceOf(ChaosInjectedException.class);
            assertThatCode(() -> chaos.inject("cache.read", () -> "ok")).doesNotThrowAnyException();
        }
    }

    @Test
    void customConditionCanUseInvocationInfo() {
        ChaosEngine chaos = build(ChaosRule.named("storage.read")
                .when(invocation -> invocation.invocationCount() % 2 == 0)
                .fault(Fault.exception("even calls fail")));

        try (ChaosScope ignored = chaos.openScope()) {
            assertThat(callOnce(chaos)).isEqualTo("ok");
            assertThatThrownBy(() -> callOnce(chaos)).isInstanceOf(ChaosInjectedException.class);
            assertThat(callOnce(chaos)).isEqualTo("ok");
            assertThatThrownBy(() -> callOnce(chaos)).isInstanceOf(ChaosInjectedException.class);
        }
    }

    @Test
    void firstMatchingRuleWins() {
        ChaosEngine chaos = build(
                ChaosRule.named("storage.read")
                        .when(invocation -> invocation.invocationCount() == 1)
                        .fault(Fault.corruptResult("corrupt-A")),
                ChaosRule.named("storage.read")
                        .fault(Fault.exception("should never fire")));

        String result;
        try (ChaosScope ignored = chaos.openScope()) {
            result = callOnce(chaos);
            // 第二条规则在第二次调用时才轮到
            assertThatThrownBy(() -> callOnce(chaos)).isInstanceOf(ChaosInjectedException.class);
        }
        assertThat(result).isEqualTo("corrupt-A");
    }

    @Test
    void firstApplicableRuleOwnsTheCallEvenWhenProbabilityMisses() {
        // 第一条规则条件命中但概率为 0：本次调用不注入，且不会回落到第二条规则
        ChaosEngine chaos = build(
                ChaosRule.named("storage.read")
                        .probability(0.0)
                        .fault(Fault.exception("never fires")),
                ChaosRule.named("storage.read")
                        .fault(Fault.exception("must not fire either")));

        try (ChaosScope ignored = chaos.openScope()) {
            assertThat(callOnce(chaos)).isEqualTo("ok");
        }
    }

    @Test
    void rulesWithoutFaultCannotBeRegistered() {
        assertThatThrownBy(() -> build(ChaosRule.named("storage.read")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(".fault(");
    }
}
