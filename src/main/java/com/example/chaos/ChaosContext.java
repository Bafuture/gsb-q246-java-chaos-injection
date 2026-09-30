package com.example.chaos;

import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 基于线程栈的上下文属性容器。业务代码可在作用域内放入租户、请求 ID
 * 等属性，规则通过 {@code ctx.attribute(...)} 实现“只对指定上下文生效”。
 *
 * <pre>{@code
 * try (ChaosContext ctx = ChaosContext.of("tenant", "gold").open()) {
 *     business.run();
 * }
 * }</pre>
 */
public final class ChaosContext implements AutoCloseable {

    private static final ThreadLocal<Map<String, Object>> CURRENT = ThreadLocal.withInitial(HashMap::new);

    private final Map<String, Object> attributes;
    private boolean opened;

    public ChaosContext(Map<String, Object> attributes) {
        this.attributes = new LinkedHashMap<>(attributes);
    }

    public static ChaosContext of(Object... keyValuePairs) {
        if (keyValuePairs.length % 2 != 0) {
            throw new IllegalArgumentException("参数必须为 key/value 对");
        }
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i < keyValuePairs.length; i += 2) {
            map.put(String.valueOf(keyValuePairs[i]), keyValuePairs[i + 1]);
        }
        return new ChaosContext(map);
    }

    /** 压入当前线程的上下文栈，返回自身以便 try-with-resources。 */
    public ChaosContext open() {
        CURRENT.get().putAll(attributes);
        opened = true;
        return this;
    }

    /** 在已打开的上下文里追加属性。 */
    public ChaosContext put(String key, Object value) {
        attributes.put(key, value);
        if (opened) {
            CURRENT.get().put(key, value);
        }
        return this;
    }

    public Map<String, Object> attributes() {
        return Collections.unmodifiableMap(attributes);
    }

    @Override
    public void close() {
        if (opened) {
            CURRENT.remove();
            opened = false;
        }
    }

    /** 当前线程生效中的上下文属性（合并后的快照）。 */
    public static Map<String, Object> currentAttributes() {
        return Map.copyOf(CURRENT.get());
    }

    /** 清除当前线程的上下文（测试清理）。 */
    public static void clear() {
        CURRENT.remove();
    }
}
