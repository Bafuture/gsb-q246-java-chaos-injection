package com.example.chaos;

import java.time.Duration;

/** 超时故障抛出的异常。 */
public class ChaosTimeoutException extends RuntimeException {

    private final Duration timeout;

    public ChaosTimeoutException(Duration timeout) {
        super("Injected timeout after " + timeout);
        this.timeout = timeout;
    }

    public ChaosTimeoutException(String message, Duration timeout) {
        super(message);
        this.timeout = timeout;
    }

    public Duration timeout() {
        return timeout;
    }
}
