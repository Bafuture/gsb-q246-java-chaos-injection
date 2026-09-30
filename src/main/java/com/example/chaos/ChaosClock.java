package com.example.chaos;

import java.time.Duration;
import java.time.Instant;

/**
 * 可注入时钟。延迟 / 超时类故障通过它推进时间与等待，
 * 测试中可替换为 {@link TestClock}，从而不依赖真实等待。
 */
public interface ChaosClock {

    /** 当前时刻，用于注入记录的时间戳。 */
    Instant now();

    /** 等待指定时长（虚拟时钟实现只推进虚拟时间，不做真实等待）。 */
    void sleep(Duration duration);

    /** 基于系统时间的真实时钟（默认）。 */
    static ChaosClock system() {
        return SystemChaosClock.INSTANCE;
    }
}
