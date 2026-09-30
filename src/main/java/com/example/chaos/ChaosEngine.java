package com.example.chaos;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

/**
 * 框架核心：保存规则作用域、按注入点评估规则、执行故障、维护调用计数与注入记录。
 *
 * <p>线程作用域优先于全局作用域：当前线程有自己激活的会话时，只在其中匹配，
 * 不回退到全局会话，保证测试之间互不污染。
 */
public final class ChaosEngine {

    private final ThreadLocal<List<ChaosSession>> localSessions =
            ThreadLocal.withInitial(ArrayList::new);

    private final Map<Long, List<ChaosSession>> foreignThreadSessions = new ConcurrentHashMap<>();

    private final List<ChaosSession> globalSessions = new CopyOnWriteArrayList<>();

    /** 每个会话内、每个注入点的调用次数（含未命中的调用）。 */
    private final Map<ChaosSession, Map<String, AtomicLong>> invocationCounts = new ConcurrentHashMap<>();

    private final List<InjectionRecord> records = Collections.synchronizedList(new ArrayList<>());

    private final AtomicLong sequence = new AtomicLong();

    private volatile ChaosClock clock = ChaosClock.system();
    private volatile boolean enabled = true;

    /** 替换时钟（如虚拟时钟 {@link TestClock}）。 */
    public ChaosEngine withClock(ChaosClock clock) {
        this.clock = clock;
        return this;
    }

    public ChaosClock clock() {
        return clock;
    }

    /** 一键关闭：关闭后所有注入点直接透传，且不再产生任何记录。 */
    public void disable() {
        enabled = false;
    }

    public void enable() {
        enabled = true;
    }

    public boolean enabled() {
        return enabled;
    }

    /** 创建一个尚未激活的规则会话。 */
    public ChaosSession session(Rule... rules) {
        return session(List.of(rules));
    }

    public ChaosSession session(List<Rule> rules) {
        ChaosSession session = new ChaosSession(this, rules);
        invocationCounts.put(session, new ConcurrentHashMap<>());
        return session;
    }

    void activateLocal(Thread thread, ChaosSession session) {
        if (thread == Thread.currentThread()) {
            localSessions.get().add(session);
        } else {
            foreignThreadSessions.computeIfAbsent(thread.getId(), k -> new CopyOnWriteArrayList<>())
                    .add(session);
        }
    }

    void deactivateLocal(Thread thread, ChaosSession session) {
        if (thread == Thread.currentThread()) {
            localSessions.get().remove(session);
        } else {
            List<ChaosSession> list = foreignThreadSessions.get(thread.getId());
            if (list != null) {
                list.remove(session);
            }
        }
        invocationCounts.remove(session);
    }

    void activateGlobal(ChaosSession session) {
        globalSessions.add(session);
    }

    void deactivateGlobal(ChaosSession session) {
        globalSessions.remove(session);
        invocationCounts.remove(session);
    }

    /** 清除全部作用域、计数与记录，并恢复启用状态。 */
    public void reset() {
        localSessions.remove();
        foreignThreadSessions.clear();
        globalSessions.clear();
        invocationCounts.clear();
        synchronized (records) {
            records.clear();
        }
        sequence.set(0);
        enabled = true;
        ChaosContext.clear();
    }

    /** 执行无返回值的业务动作。 */
    public void run(String point, Runnable action) {
        run(point, Map.of(), action);
    }

    public void run(String point, Map<String, Object> attributes, Runnable action) {
        ChaosDecision decision = evaluate(point, attributes);
        if (!decision.injected()) {
            action.run();
            return;
        }
        FaultOutcome outcome = decision.outcome();
        if (outcome.thrown() != null) {
            throw Throwables.sneakyThrow(outcome.thrown());
        }
        if (outcome.alternateValue() != null) {
            // 返回型故障用于 void 注入点时选择直接透传，并把记录标记为已被框架自身处理。
            action.run();
            mark(decision.record(), CaptureState.HANDLED);
            return;
        }
        action.run();
    }

    /** 执行有返回值的业务动作。 */
    public <T> T call(String point, Supplier<T> action) {
        return call(point, Map.of(), action);
    }

    @SuppressWarnings("unchecked")
    public <T> T call(String point, Map<String, Object> attributes, Supplier<T> action) {
        ChaosDecision decision = evaluate(point, attributes);
        if (!decision.injected()) {
            return action.get();
        }
        FaultOutcome outcome = decision.outcome();
        if (outcome.thrown() != null) {
            throw Throwables.sneakyThrow(outcome.thrown());
        }
        if (outcome.alternateValue() != null) {
            return (T) outcome.alternateValue();
        }
        return action.get();
    }

    /** 纯探针：只可能注入异常 / 延迟，不返回替代值。 */
    public void touch(String point) {
        touch(point, Map.of());
    }

    public void touch(String point, Map<String, Object> attributes) {
        ChaosDecision decision = evaluate(point, attributes);
        if (!decision.injected() || decision.outcome().thrown() == null) {
            return;
        }
        throw Throwables.sneakyThrow(decision.outcome().thrown());
    }

    /** 标记指定注入点最近一次注入已被业务正确捕获。 */
    public void markHandled(String point) {
        markLatest(point, CaptureState.HANDLED);
    }

    /** 按实际捕获到的异常实例标记已处理（找不到则忽略）。 */
    public void markHandled(Throwable thrown) {
        InjectionRecord target = null;
        synchronized (records) {
            for (int i = records.size() - 1; i >= 0; i--) {
                InjectionRecord record = records.get(i);
                if (record.thrown() == thrown) {
                    target = record;
                    break;
                }
            }
        }
        if (target != null) {
            mark(target, CaptureState.HANDLED);
        }
    }

    /** 标记指定注入点最近一次注入未被捕获（异常逃逸等）。 */
    public void markEscaped(String point) {
        markLatest(point, CaptureState.ESCAPED);
    }

    private void markLatest(String point, CaptureState state) {
        InjectionRecord target = null;
        synchronized (records) {
            for (int i = records.size() - 1; i >= 0; i--) {
                InjectionRecord record = records.get(i);
                if (record.point().equals(point)) {
                    target = record;
                    break;
                }
            }
        }
        if (target != null) {
            mark(target, state);
        }
    }

    private void mark(InjectionRecord target, CaptureState state) {
        synchronized (records) {
            for (int i = 0; i < records.size(); i++) {
                if (records.get(i).sequence() == target.sequence()) {
                    records.set(i, records.get(i).withCapture(state));
                    return;
                }
            }
        }
    }

    /** 全部注入记录（不可变快照，按发生顺序）。 */
    public List<InjectionRecord> records() {
        synchronized (records) {
            return List.copyOf(records);
        }
    }

    /** 指定注入点的注入记录。 */
    public List<InjectionRecord> recordsOf(String point) {
        synchronized (records) {
            return records.stream().filter(r -> r.point().equals(point)).toList();
        }
    }

    public void clearRecords() {
        synchronized (records) {
            records.clear();
        }
        sequence.set(0);
    }

    private ChaosDecision evaluate(String point, Map<String, Object> perCallAttributes) {
        if (!enabled) {
            return ChaosDecision.none();
        }
        Thread thread = Thread.currentThread();
        List<ChaosSession> sessions = sessionsFor(thread);
        if (sessions.isEmpty()) {
            return ChaosDecision.none();
        }

        Map<String, Object> attributes = mergedAttributes(perCallAttributes);
        for (ChaosSession session : sessions) {
            long invocation = invocationCounts
                    .computeIfAbsent(session, k -> new ConcurrentHashMap<>())
                    .computeIfAbsent(point, k -> new AtomicLong())
                    .incrementAndGet();

            InjectionContext context = new InjectionContext(point, invocation, thread, attributes);
            for (Rule rule : session.rules()) {
                if (!rule.matches(context)) {
                    continue;
                }
                if (!rule.rollProbability()) {
                    // 概率未命中：继续尝试后续规则
                    continue;
                }
                Fault fault = rule.fault();
                FaultOutcome outcome = fault.activate(context, clock);
                InjectionRecord record = new InjectionRecord(
                        sequence.incrementAndGet(),
                        clock.now(),
                        point,
                        invocation,
                        fault.type(),
                        fault.describe(),
                        outcome.delay(),
                        outcome.thrown(),
                        outcome.alternateValue(),
                        thread,
                        Map.copyOf(attributes),
                        CaptureState.UNKNOWN);
                records.add(record);
                return ChaosDecision.injected(record, outcome);
            }
        }
        return ChaosDecision.none();
    }

    private List<ChaosSession> sessionsFor(Thread thread) {
        List<ChaosSession> local = (thread == Thread.currentThread())
                ? localSessions.get()
                : foreignThreadSessions.getOrDefault(thread.getId(), List.of());
        if (!local.isEmpty()) {
            return local;
        }
        return globalSessions;
    }

    private Map<String, Object> mergedAttributes(Map<String, Object> perCallAttributes) {
        if (perCallAttributes.isEmpty()) {
            return ChaosContext.currentAttributes();
        }
        Map<String, Object> merged = new LinkedHashMap<>(ChaosContext.currentAttributes());
        merged.putAll(perCallAttributes);
        return Collections.unmodifiableMap(merged);
    }
}
