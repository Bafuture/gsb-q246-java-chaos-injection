package com.example.gsb.chaos;

import java.time.Duration;
import java.time.Instant;

/**
 * 基于真实时间的时钟：{@code sleep} 会真正让当前线程睡眠。
 */
public final class SystemChaosClock implements ChaosClock {
    @Override
    public Instant now() {
        return Instant.now();
    }

    @Override
    public void sleep(Duration duration) {
        try {
            Thread.sleep(duration.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ChaosInjectedException("interrupted while injecting latency", e);
        }
    }
}
