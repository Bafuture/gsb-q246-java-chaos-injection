package com.example.gsb.chaos;

import java.time.Duration;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * 一次故障的描述：故障类型与对应的执行内容（异常工厂、延迟时长或替换结果）。
 *
 * <ul>
 *   <li>{@link #exception} —— 抛异常，业务逻辑不会执行；</li>
 *   <li>{@link #latency(Duration)} —— 延迟一段时间后再正常执行业务逻辑；</li>
 *   <li>{@link #corruptResult} —— 业务照常执行，但结果被替换为错误值。</li>
 * </ul>
 *
 * “只对一定比例生效”属于规则维度，通过 {@link ChaosRule#probability(double)} 配置。
 */
public final class Fault {
    private final FaultType type;
    private final Supplier<? extends RuntimeException> exceptionFactory;
    private final Duration delay;
    private final Supplier<?> resultReplacement;

    private Fault(FaultType type,
                  Supplier<? extends RuntimeException> exceptionFactory,
                  Duration delay,
                  Supplier<?> resultReplacement) {
        this.type = Objects.requireNonNull(type, "type");
        this.exceptionFactory = exceptionFactory;
        this.delay = delay;
        this.resultReplacement = resultReplacement;
    }

    /** 在注入点抛出由工厂创建的异常。 */
    public static Fault exception(Supplier<? extends RuntimeException> exceptionFactory) {
        Objects.requireNonNull(exceptionFactory, "exceptionFactory");
        return new Fault(FaultType.EXCEPTION, exceptionFactory, null, null);
    }

    /** 在注入点抛出默认的 {@link ChaosInjectedException}。 */
    public static Fault exception(String message) {
        Objects.requireNonNull(message, "message");
        return exception(() -> new ChaosInjectedException(message));
    }

    /** 在注入点注入指定时长的延迟，通过 {@link ChaosClock#sleep(Duration)} 实现。 */
    public static Fault latency(Duration delay) {
        Objects.requireNonNull(delay, "delay");
        if (delay.isNegative()) {
            throw new IllegalArgumentException("delay must not be negative: " + delay);
        }
        return new Fault(FaultType.LATENCY, null, delay, null);
    }

    /** 把注入点的返回结果固定替换成一个错误值。 */
    public static Fault corruptResult(Object value) {
        return corruptResult(() -> value);
    }

    /** 每次触发时动态生成一个错误结果替换业务返回值。 */
    public static Fault corruptResult(Supplier<?> replacement) {
        Objects.requireNonNull(replacement, "replacement");
        return new Fault(FaultType.CORRUPT_RESULT, null, null, replacement);
    }

    public FaultType type() {
        return type;
    }

    public RuntimeException createException() {
        return Objects.requireNonNull(exceptionFactory.get(), "exceptionFactory returned null");
    }

    public Duration delay() {
        return delay;
    }

    public Supplier<?> replacement() {
        return resultReplacement;
    }
}
