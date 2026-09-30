package com.example.gsb.chaos;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/**
 * 测试用可控时钟：{@code sleep} 只把逻辑时间向前推进，不发生真实等待，
 * 因此延迟与超时相关的测试都是确定性的、瞬时的。
 */
public final class MutableChaosClock implements ChaosClock {
    private Instant current;

    public MutableChaosClock(Instant start) {
        this.current = Objects.requireNonNull(start, "start");
    }

    public static MutableChaosClock startingAt(Instant start) {
        return new MutableChaosClock(start);
    }

    @Override
    public synchronized Instant now() {
        return current;
    }

    public synchronized void advance(Duration duration) {
        current = current.plus(duration);
    }

    @Override
    public void sleep(Duration duration) {
        advance(duration);
    }
}
