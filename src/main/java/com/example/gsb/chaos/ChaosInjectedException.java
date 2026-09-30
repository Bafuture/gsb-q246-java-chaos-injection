package com.example.gsb.chaos;

/**
 * 使用 {@code Fault.exception(String)} 时默认抛出的异常类型。
 */
public class ChaosInjectedException extends RuntimeException {
    public ChaosInjectedException(String message) {
        super(message);
    }

    public ChaosInjectedException(String message, Throwable cause) {
        super(message, cause);
    }
}
