package com.example.chaos;

/**
 * 一种具体故障行为。规则命中后由引擎执行。
 * 内建故障请使用 {@link Faults} 工厂创建。
 */
public interface Fault {

    /** 故障类型。 */
    FaultType type();

    /**
     * 执行故障。
     *
     * @param context 当前注入点上下文
     * @param clock   可注入时钟（延迟 / 超时使用）
     */
    FaultOutcome activate(InjectionContext context, ChaosClock clock);

    /** 便于记录与日志展示的描述。 */
    default String describe() {
        return type().name().toLowerCase();
    }
}
