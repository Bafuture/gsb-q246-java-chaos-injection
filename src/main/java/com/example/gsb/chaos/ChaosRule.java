package com.example.gsb.chaos;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Random;
import java.util.function.Predicate;
import java.util.regex.Pattern;

/**
 * 故障规则：所有匹配条件之间是 AND 关系；同一个引擎配置多条规则时，
 * 按注册顺序取第一条命中的规则（first match wins）。
 *
 * <p>支持的匹配条件：
 * <ul>
 *   <li>注入点名称：精确匹配（{@link #named(String)}）或正则（{@link #matching(String)}）；</li>
 *   <li>调用次数区间：{@link #invocationsBetween(long, long)}，计数按注入点名称分别维护，从 1 开始；</li>
 *   <li>概率：{@link #probability(double)}，仅对一定比例的调用生效；</li>
 *   <li>线程名正则：{@link #onThreadMatching(String)}；</li>
 *   <li>上下文属性：{@link #inContext(String, Object)}；</li>
 *   <li>自定义条件：{@link #when(Predicate)}。</li>
 * </ul>
 */
public final class ChaosRule {
    private final Pattern pointPattern;
    private final long minInvocations;
    private final long maxInvocations;
    private final double probability;
    private final Pattern threadPattern;
    private final Map<String, Object> contextRequirements;
    private final Predicate<Invocation> condition;
    private final Fault fault;

    private ChaosRule(Pattern pointPattern,
                      long minInvocations,
                      long maxInvocations,
                      double probability,
                      Pattern threadPattern,
                      Map<String, Object> contextRequirements,
                      Predicate<Invocation> condition,
                      Fault fault) {
        this.pointPattern = pointPattern;
        this.minInvocations = minInvocations;
        this.maxInvocations = maxInvocations;
        this.probability = probability;
        this.threadPattern = threadPattern;
        this.contextRequirements = contextRequirements;
        this.condition = condition;
        this.fault = fault;
    }

    /** 精确匹配注入点名称。 */
    public static ChaosRule named(String pointName) {
        Objects.requireNonNull(pointName, "pointName");
        return matching(Pattern.quote(pointName));
    }

    /** 按正则匹配注入点名称（整个名称须匹配）。 */
    public static ChaosRule matching(String pointNameRegex) {
        Objects.requireNonNull(pointNameRegex, "pointNameRegex");
        return new ChaosRule(Pattern.compile(pointNameRegex),
                1, Long.MAX_VALUE, 1.0, null, Map.of(), null, null);
    }

    /** 仅在第 {@code min} 到第 {@code max} 次调用（含端点）之间生效。 */
    public ChaosRule invocationsBetween(long min, long max) {
        if (min < 1 || max < min) {
            throw new IllegalArgumentException("expected 1 <= min <= max, got min=" + min + " max=" + max);
        }
        return new ChaosRule(pointPattern, min, max, probability,
                threadPattern, contextRequirements, condition, fault);
    }

    /** 仅对一定比例（{@code 0.0 ~ 1.0}）的调用生效。 */
    public ChaosRule probability(double probability) {
        if (probability < 0.0 || probability > 1.0) {
            throw new IllegalArgumentException("probability must be within [0.0, 1.0]: " + probability);
        }
        return new ChaosRule(pointPattern, minInvocations, maxInvocations, probability,
                threadPattern, contextRequirements, condition, fault);
    }

    /** 只对线程名匹配正则的调用生效。 */
    public ChaosRule onThreadMatching(String threadNameRegex) {
        Objects.requireNonNull(threadNameRegex, "threadNameRegex");
        return new ChaosRule(pointPattern, minInvocations, maxInvocations, probability,
                Pattern.compile(threadNameRegex), contextRequirements, condition, fault);
    }

    /** 要求当前作用域上下文中的属性等于指定值。 */
    public ChaosRule inContext(String key, Object value) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(value, "value");
        Map<String, Object> copy = new HashMap<>(contextRequirements);
        copy.put(key, value);
        return new ChaosRule(pointPattern, minInvocations, maxInvocations, probability,
                threadPattern, Map.copyOf(copy), condition, fault);
    }

    /** 自定义匹配条件。 */
    public ChaosRule when(Predicate<Invocation> condition) {
        Objects.requireNonNull(condition, "condition");
        return new ChaosRule(pointPattern, minInvocations, maxInvocations, probability,
                threadPattern, contextRequirements, condition, fault);
    }

    /** 规则命中后要触发的故障（必填）。 */
    public ChaosRule fault(Fault fault) {
        Objects.requireNonNull(fault, "fault");
        return new ChaosRule(pointPattern, minInvocations, maxInvocations, probability,
                threadPattern, contextRequirements, condition, fault);
    }

    public Fault fault() {
        return fault;
    }

    boolean matches(Invocation invocation, Random random) {
        return matchesExceptProbability(invocation) && probabilityPasses(random);
    }

    /**
     * 除概率采样外的所有条件是否命中。引擎用它实现“第一条适用规则拥有本次调用”：
     * 若该规则条件命中但概率采样未中，本次调用直接不注入，不再回落到后续规则。
     */
    boolean matchesExceptProbability(Invocation invocation) {
        if (!pointPattern.matcher(invocation.pointName()).matches()) {
            return false;
        }
        if (invocation.invocationCount() < minInvocations
                || invocation.invocationCount() > maxInvocations) {
            return false;
        }
        if (threadPattern != null
                && !threadPattern.matcher(invocation.thread().getName()).matches()) {
            return false;
        }
        for (Map.Entry<String, Object> requirement : contextRequirements.entrySet()) {
            if (!Objects.equals(invocation.contextAttribute(requirement.getKey()), requirement.getValue())) {
                return false;
            }
        }
        return condition == null || condition.test(invocation);
    }

    private boolean probabilityPasses(Random random) {
        return probability >= 1.0 || random.nextDouble() < probability;
    }
}
