package com.example.chaos;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 虚拟时钟：sleep 只推进虚拟时间，不做真实等待，
 * 因此延迟 / 超时故障的测试可以瞬时完成。
 */
public final class TestClock implements ChaosClock {

    private final AtomicReference<Instant> now;

    public TestClock(Instant start) {
        this.now = new AtomicReference<>(start);
    }

    public static TestClock startingAt(Instant start) {
        return new TestClock(start);
    }

    @Override
    public Instant now() {
        return now.get();
    }

    @Override
    public void sleep(Duration duration) {
        advance(duration);
    }

    /** 手动推进虚拟时间。 */
    public Instant advance(Duration duration) {
        return now.updateAndGet(t -> t.plus(duration));
    }
}
