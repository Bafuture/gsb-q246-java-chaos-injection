package com.example.chaos.demo;

import com.example.chaos.CaptureState;
import com.example.chaos.Chaos;
import com.example.chaos.ChaosSession;
import com.example.chaos.FaultType;
import com.example.chaos.Faults;
import com.example.chaos.InjectionRecord;
import com.example.chaos.Rule;
import com.example.chaos.TestClock;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 端到端演示：在中文命名的注入点上注入故障，观察业务恢复过程。
 */
class OrderServiceChaosTest {

    private TestClock clock;
    private OrderService service;

    @BeforeEach
    void setUp() {
        clock = TestClock.startingAt(Instant.parse("2026-10-01T08:00:00Z"));
        Chaos.useClock(clock);
        Chaos.clearRecords();
        service = new OrderService(id -> "ORDER-" + id);
    }

    @AfterEach
    void tearDown() {
        Chaos.reset();
    }

    @Test
    void transientStorageFailureIsRecoveredByRetry() {
        // 只在第 1 次“读存储前”注入异常，第 2 次（重试）放行
        try (ChaosSession session = Chaos.newSession(
                Rule.onPoint(OrderService.BEFORE_READ_STORAGE)
                        .invocation(1)
                        .fault(Faults.exception("storage unavailable"))
                        .build())
                .activate()) {
            String order = service.loadOrder("42");
            assertThat(order).isEqualTo("ORDER-42");
        }
        InjectionRecord record = Chaos.recordsOf(OrderService.BEFORE_READ_STORAGE).get(0);
        assertThat(record.faultType()).isEqualTo(FaultType.EXCEPTION);
        // 重试成功，但业务没有显式标记捕获 -> 记录保持 UNKNOWN
        assertThat(record.capture()).isEqualTo(CaptureState.UNKNOWN);
    }

    @Test
    void persistentStorageFailureFallsBackAndIsMarkedHandled() {
        try (ChaosSession session = Chaos.newSession(
                Rule.onPoint(OrderService.BEFORE_READ_STORAGE)
                        .fault(Faults.exception("storage down"))
                        .build())
                .activate()) {
            String order = service.loadOrder("42");
            assertThat(order).isEqualTo("FALLBACK-ORDER");
        }
        // 两次尝试（首次 + 重试）都被注入，业务降级后显式标记已捕获
        assertThat(Chaos.recordsOf(OrderService.BEFORE_READ_STORAGE)).hasSize(2);
        InjectionRecord latest = Chaos.recordsOf(OrderService.BEFORE_READ_STORAGE).get(1);
        assertThat(latest.capture()).isEqualTo(CaptureState.HANDLED);
        assertThat(latest.time()).isEqualTo(Instant.parse("2026-10-01T08:00:00Z"));
    }

    @Test
    void slowMessageSendIsObservedThroughDelayFault() {
        try (ChaosSession session = Chaos.newSession(
                Rule.onPoint(OrderService.AFTER_SEND_MESSAGE)
                        .fault(Faults.delay(Duration.ofSeconds(3)))
                        .build())
                .activate()) {
            service.notifyUser("42");
        }
        assertThat(service.sentMessages()).containsExactly("notified:42");
        InjectionRecord record = Chaos.recordsOf(OrderService.AFTER_SEND_MESSAGE).get(0);
        assertThat(record.faultType()).isEqualTo(FaultType.DELAY);
        assertThat(record.delay()).isEqualTo(Duration.ofSeconds(3));
        assertThat(clock.now()).isEqualTo(Instant.parse("2026-10-01T08:00:03Z"));
    }

    @Test
    void disabledFrameworkKeepsBusinessUntouched() {
        try (ChaosSession session = Chaos.newSession(
                Rule.onPoint(OrderService.BEFORE_READ_STORAGE)
                        .fault(Faults.exception("storage down")).build())
                .activate()) {
            Chaos.disable();
            assertThat(service.loadOrder("42")).isEqualTo("ORDER-42");
            service.notifyUser("42");
            assertThat(service.sentMessages()).containsExactly("notified:42");
            assertThat(Chaos.records()).isEmpty();
        }
    }
}
