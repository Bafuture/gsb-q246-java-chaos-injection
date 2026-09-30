package com.example.gsb.chaos;

/**
 * 注入作用域：故障只在打开了作用域的线程上生效（基于 {@link ThreadLocal}），
 * 其它线程、其它测试完全不受影响。作用域可嵌套，关闭后恢复上一层。
 *
 * <pre>{@code
 * try (ChaosScope scope = chaos.openScope(ChaosContext.of("tenant", "test"))) {
 *     chaos.inject("storage.read.before", () -> storage.read(key));
 * }
 * }</pre>
 */
public final class ChaosScope implements AutoCloseable {
    private final ChaosEngine engine;
    private final ChaosContext context;
    private final ChaosScope previous;
    private boolean closed;

    ChaosScope(ChaosEngine engine, ChaosContext context, ChaosScope previous) {
        this.engine = engine;
        this.context = context;
        this.previous = previous;
    }

    public ChaosContext context() {
        return context;
    }

    ChaosScope previous() {
        return previous;
    }

    @Override
    public void close() {
        if (!closed) {
            closed = true;
            engine.closeScope(this);
        }
    }
}
