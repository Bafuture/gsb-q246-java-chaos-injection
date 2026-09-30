package com.example.chaos;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RuleMatchingTest {

    @AfterEach
    void tearDown() {
        Chaos.reset();
    }

    @Test
    void matchesByNameOnly() {
        try (ChaosSession session = Chaos.newSession(
                Rule.onPoint("read-storage")
                        .fault(Faults.exception("io error")).build(),
                Rule.onPoint("other")
                        .fault(Faults.exception("other error")).build()).activate()) {
            assertThatThrownBy(() -> Chaos.touch("read-storage"))
                    .hasMessage("io error");
            Chaos.touch("unknown-point");
            assertThatThrownBy(() -> Chaos.touch("other")).hasMessage("other error");
        }
        assertThat(Chaos.records()).extracting(InjectionRecord::point)
                .containsExactly("read-storage", "other");
    }

    @Test
    void matchesInvocationRange() {
        AtomicInteger executed = new AtomicInteger();
        try (ChaosSession session = Chaos.newSession(
                Rule.onPoint("p").invocations(2, 3)
                        .fault(Faults.exception("fail")).build()).activate()) {
            // 1st: 正常；2nd、3rd: 注入；4th: 正常
            Chaos.run("p", executed::incrementAndGet);
            assertThatThrownBy(() -> Chaos.run("p", executed::incrementAndGet)).hasMessage("fail");
            assertThatThrownBy(() -> Chaos.run("p", executed::incrementAndGet)).hasMessage("fail");
            Chaos.run("p", executed::incrementAndGet);
        }
        assertThat(executed).hasValue(2);
        assertThat(Chaos.recordsOf("p")).extracting(InjectionRecord::invocation)
                .containsExactly(2L, 3L);
    }

    @Test
    void matchesByCustomConditionOnContextAttribute() {
        Rule rule = Rule.builder()
                .point("query")
                .when(ctx -> "gold".equals(ctx.attribute("tenant")))
                .fault(Faults.exception("tenant fault"))
                .build();
        try (ChaosSession session = Chaos.newSession(rule).activate()) {
            Chaos.run("query", () -> {
            });
            assertThatThrownBy(() ->
                    Chaos.run("query", Map.of("tenant", "gold"), () -> {
                    }))
                    .hasMessage("tenant fault");
            Chaos.run("query", Map.of("tenant", "silver"), () -> {
            });
        }
        assertThat(Chaos.recordsOf("query")).hasSize(1);
    }

    @Test
    void customConditionCanReadInvocationCount() {
        Rule rule = Rule.builder()
                .point("p")
                .when(ctx -> ctx.invocation() % 2 == 0)
                .fault(Faults.exception("even"))
                .build();
        try (ChaosSession session = Chaos.newSession(rule).activate()) {
            Chaos.run("p", () -> {
            });
            assertThatThrownBy(() -> Chaos.run("p", () -> {
            })).hasMessage("even");
            Chaos.run("p", () -> {
            });
            assertThatThrownBy(() -> Chaos.run("p", () -> {
            })).hasMessage("even");
        }
        assertThat(Chaos.recordsOf("p")).hasSize(2);
    }

    @Test
    void firstMatchingRuleWins() {
        Rule first = Rule.onPoint("p").fromInvocation(2)
                .fault(Faults.returnError("first")).build();
        Rule second = Rule.builder().pointMatches(name -> name.startsWith("p"))
                .fault(Faults.exception("second")).build();
        try (ChaosSession session = Chaos.newSession(first, second).activate()) {
            // 第 1 次：first 次数不匹配，second 名称匹配 -> 异常
            assertThatThrownBy(() -> Chaos.call("p", () -> "real")).hasMessage("second");
            // 第 2 次：first 先命中 -> 返回错误值
            assertThat(Chaos.<String>call("p", () -> "real")).isEqualTo("first");
        }
    }

    @Test
    void failedProbabilityFallsThroughToNextRule() {
        Rule never = Rule.onPoint("p").probability(0.0)
                .fault(Faults.exception("never")).build();
        Rule always = Rule.onPoint("p")
                .fault(Faults.delay(Duration.ofMillis(1))).build();
        try (ChaosSession session = Chaos.newSession(never, always).activate()) {
            Chaos.touch("p");
        }
        assertThat(Chaos.recordsOf("p")).hasSize(1);
        assertThat(Chaos.recordsOf("p").get(0).faultType()).isEqualTo(FaultType.DELAY);
    }
}
