package com.example.chaos;

/** 默认的异常型故障抛出的运行时异常。 */
public class ChaosInjectionException extends RuntimeException {

    public ChaosInjectionException(String message) {
        super(message);
    }
}
