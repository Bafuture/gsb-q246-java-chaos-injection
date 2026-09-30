package com.example.chaos;

import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * 框架静态门面：业务代码只依赖本类。
 *
 * <pre>{@code
 * // 业务代码中标记注入点
 * String data = Chaos.call("read-storage", () -> storage.read());
 * Chaos.run("after-send-message", () -> metrics.increment());
 * }</pre>
 *
 * <pre>{@code
 * // 测试中配置规则
 * try (ChaosSession s = Chaos.newSession(
 *         Rule.onPoint("read-storage")
 *             .invocations(2, 3)
 *             .probability(0.5, new Random(42))
 *             .fault(Faults.delay(Duration.ofSeconds(2)))
 *             .build())
 *         .activate()) {
 *     ...
 * }
 * }</pre>
 */
public final class Chaos {

    private static final ChaosEngine ENGINE = new ChaosEngine();

    private Chaos() {
    }

    public static ChaosEngine engine() {
        return ENGINE;
    }

    // ------------------------------------------------------------------ 注入点

    /** 无返回值注入点：动作正常执行或被注入故障。 */
    public static void run(String point, Runnable action) {
        ENGINE.run(point, action);
    }

    /** 带调用属性的无返回值注入点。 */
    public static void run(String point, Map<String, Object> attributes, Runnable action) {
        ENGINE.run(point, attributes, action);
    }

    /** 有返回值注入点：返回业务结果或被故障替换。 */
    public static <T> T call(String point, Supplier<T> action) {
        return ENGINE.call(point, action);
    }

    /** 带调用属性的有返回值注入点。 */
    public static <T> T call(String point, Map<String, Object> attributes, Supplier<T> action) {
        return ENGINE.call(point, attributes, action);
    }

    /** 纯探针：仅在该点触发异常 / 延迟，不包裹业务逻辑。 */
    public static void touch(String point) {
        ENGINE.touch(point);
    }

    public static void touch(String point, Map<String, Object> attributes) {
        ENGINE.touch(point, attributes);
    }

    // ------------------------------------------------------------------ 会话

    public static ChaosSession newSession(Rule... rules) {
        return ENGINE.session(rules);
    }

    public static ChaosSession newSession(List<Rule> rules) {
        return ENGINE.session(rules);
    }

    // ------------------------------------------------------------------ 时钟

    /** 注入自定义时钟（如 {@link TestClock}）。 */
    public static void useClock(ChaosClock clock) {
        ENGINE.withClock(clock);
    }

    public static ChaosClock clock() {
        return ENGINE.clock();
    }

    // ------------------------------------------------------------------ 捕获标记

    /** 业务在 catch / 重试成功后调用：指定注入点最近一次注入已被正确处理。 */
    public static void markHandled(String point) {
        ENGINE.markHandled(point);
    }

    /** 按捕获到的异常实例标记已处理。 */
    public static void markHandled(Throwable thrown) {
        ENGINE.markHandled(thrown);
    }

    /** 标记指定注入点最近一次注入未被业务捕获。 */
    public static void markEscaped(String point) {
        ENGINE.markEscaped(point);
    }

    // ------------------------------------------------------------------ 记录

    public static List<InjectionRecord> records() {
        return ENGINE.records();
    }

    public static List<InjectionRecord> recordsOf(String point) {
        return ENGINE.recordsOf(point);
    }

    public static void clearRecords() {
        ENGINE.clearRecords();
    }

    // ------------------------------------------------------------------ 开关与清理

    /** 一键关闭：所有注入点直接透传。 */
    public static void disable() {
        ENGINE.disable();
    }

    public static void enable() {
        ENGINE.enable();
    }

    public static boolean enabled() {
        return ENGINE.enabled();
    }

    /** 清除所有会话、计数、记录并恢复启用状态（测试结束时调用）。 */
    public static void reset() {
        ENGINE.reset();
    }

    /** 在当前线程打开一组上下文属性。 */
    public static ChaosContext openContext(Object... keyValuePairs) {
        return ChaosContext.of(keyValuePairs).open();
    }
}
