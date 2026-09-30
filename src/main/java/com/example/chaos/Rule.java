package com.example.chaos;

import java.util.Random;
import java.util.random.RandomGenerator;
import java.util.function.Predicate;

/**
 * 注入规则。规则按加入顺序依次匹配，第一条通过名称 / 次数 / 条件 /
 * 线程匹配且概率判定成功的规则生效。
 *
 * <p>匹配维度：注入点名称、调用次数区间（从 1 开始，闭区间）、
 * 概率（0~1）、自定义条件、线程谓词。
 */
public final class Rule {

    private final String name;
    private final Predicate<String> pointMatcher;
    private final long countFrom; // 0 表示不限制下界
    private final long countTo;   // Long.MAX_VALUE 表示不限制上界
    private final double probability;
    private final RandomGenerator random;
    private final Predicate<InjectionContext> condition;
    private final Predicate<Thread> threadPredicate;
    private final Fault fault;

    private Rule(Builder b) {
        if (b.fault == null) {
            throw new IllegalStateException("规则必须指定 fault");
        }
        this.name = b.name;
        this.pointMatcher = b.pointMatcher;
        this.countFrom = b.countFrom;
        this.countTo = b.countTo;
        this.probability = b.probability;
        this.random = b.random;
        this.condition = b.condition;
        this.threadPredicate = b.threadPredicate;
        this.fault = b.fault;
    }

    /** 为注入点 {@code point} 创建规则构建器。 */
    public static Builder onPoint(String point) {
        return builder().point(point);
    }

    public static Builder builder() {
        return new Builder();
    }

    public String name() {
        return name;
    }

    public Fault fault() {
        return fault;
    }

    /** 确定性匹配：名称、调用次数区间、线程谓词、自定义条件。 */
    public boolean matches(InjectionContext ctx) {
        return pointMatcher.test(ctx.point())
                && ctx.invocation() >= countFrom && ctx.invocation() <= countTo
                && threadPredicate.test(ctx.thread())
                && condition.test(ctx);
    }

    /** 概率判定：未配置概率（=1）时总是成功。 */
    public boolean rollProbability() {
        if (probability >= 1.0) {
            return true;
        }
        if (probability <= 0.0) {
            return false;
        }
        return random.nextDouble() < probability;
    }

    @Override
    public String toString() {
        return "Rule{" + (name != null ? name : fault.describe()) + "}";
    }

    /** 规则构建器。 */
    public static final class Builder {
        private String name;
        private Predicate<String> pointMatcher = p -> true;
        private long countFrom = 0;
        private long countTo = Long.MAX_VALUE;
        private double probability = 1.0;
        private RandomGenerator random = new Random();
        private Predicate<InjectionContext> condition = ctx -> true;
        private Predicate<Thread> threadPredicate = t -> true;
        private Fault fault;

        /** 规则名称（仅用于展示）。 */
        public Builder name(String name) {
            this.name = name;
            return this;
        }

        /** 精确匹配注入点名称。 */
        public Builder point(String point) {
            this.pointMatcher = point::equals;
            return this;
        }

        /** 自定义注入点名称匹配（如前缀 / 正则）。 */
        public Builder pointMatches(Predicate<String> matcher) {
            this.pointMatcher = matcher;
            return this;
        }

        /** 只对第 from 次到第 to 次调用生效（闭区间，次数从 1 开始）。 */
        public Builder invocations(long from, long to) {
            if (from < 1 || to < from) {
                throw new IllegalArgumentException("非法调用次数区间: [" + from + ", " + to + "]");
            }
            this.countFrom = from;
            this.countTo = to;
            return this;
        }

        /** 只对第 n 次调用生效。 */
        public Builder invocation(int n) {
            return invocations(n, n);
        }

        /** 从第 from 次调用起生效。 */
        public Builder fromInvocation(long from) {
            return invocations(from, Long.MAX_VALUE);
        }

        /** 生效概率，[0.0, 1.0]，使用默认随机源。 */
        public Builder probability(double probability) {
            this.probability = checkProbability(probability);
            return this;
        }

        /** 生效概率，并指定随机源（测试可传入固定种子 Random）。 */
        public Builder probability(double probability, RandomGenerator random) {
            this.probability = checkProbability(probability);
            this.random = random;
            return this;
        }

        /** 自定义条件，可基于注入点上下文（含业务属性）判断。 */
        public Builder when(Predicate<InjectionContext> condition) {
            this.condition = condition;
            return this;
        }

        /** 只对满足谓词的线程生效。 */
        public Builder forThread(Predicate<Thread> predicate) {
            this.threadPredicate = predicate;
            return this;
        }

        /** 触发的故障。 */
        public Builder fault(Fault fault) {
            this.fault = fault;
            return this;
        }

        /** 抛异常故障的快捷方式。 */
        public Builder throwIt(Fault exceptionFault) {
            return fault(exceptionFault);
        }

        public Rule build() {
            return new Rule(this);
        }

        private static double checkProbability(double p) {
            if (p < 0.0 || p > 1.0 || Double.isNaN(p)) {
                throw new IllegalArgumentException("概率必须在 [0, 1] 之间: " + p);
            }
            return p;
        }
    }
}
