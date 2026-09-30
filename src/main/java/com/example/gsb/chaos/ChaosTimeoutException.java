package com.example.gsb.chaos;

import java.time.Duration;

/**
 * 注入点执行耗时（基于可注入时钟计算）超过 {@code withTimeout} 配置的超时时长时抛出。
 */
public final class ChaosTimeoutException extends RuntimeException {
    private final String pointName;
    private final Duration timeout;
    private final Duration elapsed;

    public ChaosTimeoutException(String pointName, Duration timeout, Duration elapsed) {
        super("injection point '" + pointName + "' exceeded timeout "
                + timeout.toMillis() + "ms (elapsed " + elapsed.toMillis() + "ms)");
        this.pointName = pointName;
        this.timeout = timeout;
        this.elapsed = elapsed;
    }

    public String pointName() {
        return pointName;
    }

    public Duration timeout() {
        return timeout;
    }

    public Duration elapsed() {
        return elapsed;
    }
}
