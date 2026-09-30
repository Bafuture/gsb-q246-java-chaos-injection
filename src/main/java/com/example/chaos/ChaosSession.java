package com.example.chaos;

import java.util.List;

/**
 * 一次注入配置的会话：包含一组有序规则，可在线程作用域或全局作用域激活。
 * 线程作用域的会话只对激活它的线程生效；全局会话对所有线程生效，
 * 但规则仍可用 {@code forThread} / {@code when} 进一步收窄。
 */
public final class ChaosSession implements AutoCloseable {

    private final ChaosEngine engine;
    private final List<Rule> rules;
    private volatile boolean active;
    private volatile boolean global;

    ChaosSession(ChaosEngine engine, List<Rule> rules) {
        this.engine = engine;
        this.rules = List.copyOf(rules);
    }

    public List<Rule> rules() {
        return rules;
    }

    public boolean active() {
        return active;
    }

    public boolean global() {
        return global;
    }

    /** 在当前线程激活（只影响当前线程）。 */
    public ChaosSession activate() {
        engine.activateLocal(Thread.currentThread(), this);
        active = true;
        global = false;
        return this;
    }

    /** 全局激活（影响所有线程，规则可再用线程谓词收窄）。 */
    public ChaosSession activateGlobally() {
        engine.activateGlobal(this);
        active = true;
        global = true;
        return this;
    }

    /** 停用并从引擎注销。可重复调用。 */
    public void deactivate() {
        if (!active) {
            return;
        }
        if (global) {
            engine.deactivateGlobal(this);
        } else {
            engine.deactivateLocal(Thread.currentThread(), this);
        }
        active = false;
    }

    @Override
    public void close() {
        deactivate();
    }
}
