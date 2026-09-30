package com.example.chaos;

/**
 * 注入点评估结果。{@link #injected()} 为 true 时包含一条注入记录与故障执行结果。
 */
final class ChaosDecision {

    private final boolean injected;
    private final InjectionRecord record;
    private final FaultOutcome outcome;

    private ChaosDecision(boolean injected, InjectionRecord record, FaultOutcome outcome) {
        this.injected = injected;
        this.record = record;
        this.outcome = outcome;
    }

    static ChaosDecision none() {
        return new ChaosDecision(false, null, null);
    }

    static ChaosDecision injected(InjectionRecord record, FaultOutcome outcome) {
        return new ChaosDecision(true, record, outcome);
    }

    boolean injected() {
        return injected;
    }

    InjectionRecord record() {
        return record;
    }

    FaultOutcome outcome() {
        return outcome;
    }
}
