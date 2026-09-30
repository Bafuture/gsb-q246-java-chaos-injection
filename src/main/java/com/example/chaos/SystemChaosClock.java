package com.example.chaos;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

/** 基于系统时钟的实现：sleep 会真实阻塞当前线程。 */
public final class SystemChaosClock implements ChaosClock {

    static final SystemChaosClock INSTANCE = new SystemChaosClock(Clock.systemUTC());

    private final Clock clock;

    public SystemChaosClock(Clock clock) {
        this.clock = clock;
    }

    @Override
    public Instant now() {
        return clock.instant();
    }

    @Override
    public void sleep(Duration duration) {
        try {
            Thread.sleep(duration.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw Throwables.sneakyThrow(e);
        }
    }
}
