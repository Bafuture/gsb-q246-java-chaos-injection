package com.example.gsb.chaos;

import java.time.Duration;
import java.time.Instant;

/**
 * 可注入时钟。延迟与超时都基于它计算：
 * 生产环境用 {@link SystemChaosClock}（真实睡眠），
 * 测试用 {@link MutableChaosClock}（只推进逻辑时间，不真实等待）。
 */
public interface ChaosClock {
    Instant now();

    void sleep(Duration duration);
}
