package com.example.chaos;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ScopeIsolationTest {

    @AfterEach
    void tearDown() {
        Chaos.reset();
    }

    @Test
    void threadScopedSessionDoesNotAffectOtherThreads() throws Exception {
        AtomicBoolean otherThreadHit = new AtomicBoolean(false);
        AtomicBoolean otherThreadRan = new AtomicBoolean(false);
        CountDownLatch done = new CountDownLatch(1);

        Thread worker = new Thread(() -> {
            try {
                Chaos.run("p", () -> otherThreadRan.set(true));
            } catch (ChaosInjectionException e) {
                otherThreadHit.set(true);
            } finally {
                done.countDown();
            }
        }, "worker-thread");

        try (ChaosSession session = Chaos.newSession(
                Rule.onPoint("p").fault(Faults.exception("main only")).build()).activate()) {
            worker.start();
            assertThat(done.await(5, TimeUnit.SECONDS)).isTrue();
            // 主线程自己仍然被注入
            assertThatThrownBy(() -> Chaos.run("p", () -> {
            })).hasMessage("main only");
        }
        assertThat(otherThreadRan).isTrue();
        assertThat(otherThreadHit).isFalse();
        assertThat(Chaos.recordsOf("p")).hasSize(1);
    }

    @Test
    void globalSessionCanBeRestrictedToNamedThread() throws Exception {
        AtomicInteger workerExecutions = new AtomicInteger();
        CountDownLatch done = new CountDownLatch(1);

        Thread worker = new Thread(() -> {
            try {
                Chaos.run("p", workerExecutions::incrementAndGet);
            } catch (ChaosInjectionException e) {
                // 预期：全局规则只对该线程生效，异常被注入
            } finally {
                done.countDown();
            }
        }, "chaos-target");

        try (ChaosSession session = Chaos.newSession(
                Rule.onPoint("p")
                        .forThread(t -> t.getName().equals("chaos-target"))
                        .fault(Faults.exception("targeted")).build())
                .activateGlobally()) {
            // 主线程不受规则影响
            Chaos.run("p", () -> {
            });
            worker.start();
            assertThat(done.await(5, TimeUnit.SECONDS)).isTrue();
        }
        worker.join(5_000);
        assertThat(workerExecutions).hasValue(0);
        assertThat(Chaos.recordsOf("p")).hasSize(1);
        assertThat(Chaos.recordsOf("p").get(0).thread().getName()).isEqualTo("chaos-target");
    }

    @Test
    void contextAttributesScopeInjectionToMatchingContext() {
        Rule rule = Rule.builder()
                .point("charge")
                .when(ctx -> "gold".equals(ctx.attribute("tenant")))
                .fault(Faults.exception("gold fault"))
                .build();
        AtomicInteger silverRuns = new AtomicInteger();
        try (ChaosSession session = Chaos.newSession(rule).activate()) {
            try (ChaosContext ignored = Chaos.openContext("tenant", "silver")) {
                Chaos.run("charge", silverRuns::incrementAndGet);
            }
            try (ChaosContext ignored = Chaos.openContext("tenant", "gold")) {
                assertThatThrownBy(() -> Chaos.run("charge", () -> {
                })).hasMessage("gold fault");
            }
            // 上下文关闭后不再注入
            Chaos.run("charge", silverRuns::incrementAndGet);
        }
        assertThat(silverRuns).hasValue(2);
        assertThat(Chaos.recordsOf("charge")).hasSize(1);
    }

    @Test
    void closingSessionStopsInjection() {
        ChaosSession session = Chaos.newSession(
                Rule.onPoint("p").fault(Faults.exception("boom")).build()).activate();
        assertThatThrownBy(() -> Chaos.touch("p")).hasMessage("boom");
        session.close();
        AtomicInteger ran = new AtomicInteger();
        Chaos.run("p", ran::incrementAndGet);
        assertThat(ran).hasValue(1);
        assertThat(Chaos.recordsOf("p")).hasSize(1);
    }

    @Test
    void localSessionTakesPrecedenceOverGlobalSession() {
        try (ChaosSession global = Chaos.newSession(
                Rule.onPoint("p").fault(Faults.exception("global")).build()).activateGlobally();
             ChaosSession local = Chaos.newSession(
                     Rule.onPoint("p").fault(Faults.exception("local")).build()).activate()) {
            assertThatThrownBy(() -> Chaos.touch("p")).hasMessage("local");
        }
    }
}
