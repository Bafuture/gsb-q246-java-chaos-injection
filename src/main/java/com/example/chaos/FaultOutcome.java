package com.example.chaos;

import java.time.Duration;

/**
 * 一次故障执行的结果。
 *
 * @param alternateValue 返回型故障要替换的业务结果
 * @param delay          故障导致的等待时长（延迟 / 超时使用）
 * @param thrown         抛异常型故障实际抛出的异常实例（未抛出为 null）
 */
public record FaultOutcome(Object alternateValue, Duration delay, Throwable thrown) {

    static FaultOutcome proceed(Duration delay) {
        return new FaultOutcome(null, delay, null);
    }

    static FaultOutcome returned(Object alternateValue) {
        return new FaultOutcome(alternateValue, null, null);
    }

    static FaultOutcome raised(Throwable thrown) {
        return new FaultOutcome(null, null, thrown);
    }

    static FaultOutcome raisedAfter(Throwable thrown, Duration delay) {
        return new FaultOutcome(null, delay, thrown);
    }
}
