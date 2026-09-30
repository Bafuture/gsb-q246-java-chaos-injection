package com.example.gsb.chaos;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

/**
 * 混沌故障注入框架的入口。
 *
 * <p>业务代码通过 {@link #inject(String, Supplier)} 或
 * {@link #inject(String, Supplier, FaultHandler)} 在指定注入点执行业务逻辑；
 * 引擎在有打开的 {@link ChaosScope} 且规则命中时触发对应 {@link Fault}。
 *
 * <p>三种完全透传的情形（行为与未接入框架一致）：
 * <ol>
 *   <li>当前线程没有打开作用域；</li>
 *   <li>没有任何规则命中；</li>
 *   <li>引擎通过 {@link #setEnabled(boolean)} 一键关闭。</li>
 * </ol>
 */
public final class ChaosEngine {
    private final List<ChaosRule> rules;
    private final ChaosClock clock;
    private final Random random;
    private final InjectionRecorder recorder;
    private final Map<String, AtomicLong> invocationCounts = new ConcurrentHashMap<>();
    private final ThreadLocal<ChaosScope> currentScope = new ThreadLocal<>();
    private volatile boolean enabled = true;

    private ChaosEngine(Builder builder) {
        this.rules = List.copyOf(builder.rules);
        this.clock = builder.clock;
        this.random = builder.random;
        this.recorder = builder.recorder;
    }

    public static Builder builder() {
        return new Builder();
    }

    /** 打开一个不带上下文的注入作用域（仅当前线程生效）。 */
    public ChaosScope openScope() {
        return openScope(ChaosContext.empty());
    }

    /** 打开一个携带上下文的注入作用域（仅当前线程生效）。 */
    public ChaosScope openScope(ChaosContext context) {
        Objects.requireNonNull(context, "context");
        ChaosScope scope = new ChaosScope(this, context, currentScope.get());
        currentScope.set(scope);
        return scope;
    }

    void closeScope(ChaosScope scope) {
        if (currentScope.get() == scope) {
            ChaosScope previous = scope.previous();
            if (previous == null) {
                currentScope.remove();
            } else {
                currentScope.set(previous);
            }
        }
    }

    public boolean isEnabled() {
        return enabled;
    }

    /** 一键开关：关闭后所有注入点直接透传，不计数、不记录。 */
    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public ChaosClock clock() {
        return clock;
    }

    public InjectionRecorder recorder() {
        return recorder;
    }

    /** 清空各注入点的调用计数（注入记录不在此方法处理）。 */
    public void reset() {
        invocationCounts.clear();
    }

    /** 无返回值的注入点包裹方法。 */
    public void inject(String pointName, Runnable action) {
        Objects.requireNonNull(action, "action");
        inject(pointName, () -> {
            action.run();
            return null;
        });
    }

    /** 在指定注入点执行业务逻辑，不提供故障处理钩子。 */
    public <T> T inject(String pointName, Supplier<T> action) {
        return inject(pointName, action, FaultHandler.passthrough());
    }

    /**
     * 在指定注入点执行业务逻辑，并提供业务侧的故障处理钩子。
     *
     * @return 业务结果、降级结果或被替换的错误结果
     */
    public <T> T inject(String pointName, Supplier<T> action, FaultHandler<T> handler) {
        Objects.requireNonNull(pointName, "pointName");
        Objects.requireNonNull(action, "action");
        Objects.requireNonNull(handler, "handler");

        ChaosScope scope = currentScope.get();
        if (!enabled || scope == null) {
            return action.get();
        }

        long count = invocationCounts
                .computeIfAbsent(pointName, key -> new AtomicLong())
                .incrementAndGet();
        Invocation invocation = new Invocation(
                pointName, count, Thread.currentThread(), scope.context(), clock.now());

        Fault fault = match(invocation);
        if (fault == null) {
            return action.get();
        }
        return applyFault(invocation, fault, action, handler);
    }

    /**
     * 带超时控制的注入点：用注入时钟测量经过时长，超过超时则抛
     * {@link ChaosTimeoutException}。配合 {@link MutableChaosClock}
     * 与延迟故障时，测试无需真实等待即可覆盖超时分支。
     */
    public <T> T withTimeout(String pointName, Duration timeout, Supplier<T> action) {
        Objects.requireNonNull(timeout, "timeout");
        Instant start = clock.now();
        T result = inject(pointName, action);
        Duration elapsed = Duration.between(start, clock.now());
        if (elapsed.compareTo(timeout) > 0) {
            throw new ChaosTimeoutException(pointName, timeout, elapsed);
        }
        return result;
    }

    private Fault match(Invocation invocation) {
        synchronized (random) {
            for (ChaosRule rule : rules) {
                if (rule.matchesExceptProbability(invocation)) {
                    // 第一条适用规则拥有本次调用：概率采样命中则注入其故障，否则本次不注入
                    return rule.matches(invocation, random) ? rule.fault() : null;
                }
            }
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private <T> T applyFault(Invocation invocation,
                             Fault fault,
                             Supplier<T> action,
                             FaultHandler<T> handler) {
        switch (fault.type()) {
            case EXCEPTION -> {
                RuntimeException injected = fault.createException();
                try {
                    T recovered = handler.onError(injected);
                    recorder.record(new InjectionRecord(invocation.occurredAt(),
                            invocation.pointName(), FaultType.EXCEPTION, true,
                            "exception recovered: " + injected));
                    return recovered;
                } catch (RuntimeException propagated) {
                    recorder.record(new InjectionRecord(invocation.occurredAt(),
                            invocation.pointName(), FaultType.EXCEPTION, false,
                            "exception propagated: " + injected));
                    throw propagated;
                }
            }
            case LATENCY -> {
                clock.sleep(fault.delay());
                T result = action.get();
                recorder.record(new InjectionRecord(invocation.occurredAt(),
                        invocation.pointName(), FaultType.LATENCY, true,
                        "latency injected: " + fault.delay() + " (business completed)"));
                return result;
            }
            case CORRUPT_RESULT -> {
                action.get();
                T corrupt = (T) fault.replacement().get();
                try {
                    T accepted = handler.onResult(corrupt);
                    recorder.record(new InjectionRecord(invocation.occurredAt(),
                            invocation.pointName(), FaultType.CORRUPT_RESULT, false,
                            "corrupt result accepted by business"));
                    return accepted;
                } catch (RuntimeException detected) {
                    recorder.record(new InjectionRecord(invocation.occurredAt(),
                            invocation.pointName(), FaultType.CORRUPT_RESULT, true,
                            "corrupt result rejected by business: " + detected));
                    throw detected;
                }
            }
            default -> throw new IllegalStateException("unknown fault type: " + fault.type());
        }
    }

    /** 引擎构造器。 */
    public static final class Builder {
        private final List<ChaosRule> rules = new ArrayList<>();
        private ChaosClock clock = new SystemChaosClock();
        private Random random = new Random();
        private InjectionRecorder recorder = new InMemoryInjectionRecorder();

        public Builder clock(ChaosClock clock) {
            this.clock = Objects.requireNonNull(clock, "clock");
            return this;
        }

        public Builder random(Random random) {
            this.random = Objects.requireNonNull(random, "random");
            return this;
        }

        public Builder recorder(InjectionRecorder recorder) {
            this.recorder = Objects.requireNonNull(recorder, "recorder");
            return this;
        }

        public Builder rule(ChaosRule rule) {
            Objects.requireNonNull(rule, "rule");
            if (rule.fault() == null) {
                throw new IllegalArgumentException("rule has no fault; call .fault(...) before registering");
            }
            rules.add(rule);
            return this;
        }

        public Builder rules(ChaosRule... rules) {
            for (ChaosRule rule : rules) {
                rule(rule);
            }
            return this;
        }

        public ChaosEngine build() {
            return new ChaosEngine(this);
        }
    }
}
