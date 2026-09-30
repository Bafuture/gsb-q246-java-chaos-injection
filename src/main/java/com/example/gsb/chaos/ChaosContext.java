package com.example.gsb.chaos;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * 注入作用域携带的上下文属性（不可变）。规则可以按上下文属性匹配，
 * 例如只对 {@code tenant=test} 的调用注入故障。
 */
public final class ChaosContext {
    private static final ChaosContext EMPTY = new ChaosContext(Map.of());

    private final Map<String, Object> attributes;

    private ChaosContext(Map<String, Object> attributes) {
        this.attributes = attributes;
    }

    public static ChaosContext empty() {
        return EMPTY;
    }

    public static ChaosContext of(String key, Object value) {
        return empty().with(key, value);
    }

    public static ChaosContext of(String key1, Object value1, String key2, Object value2) {
        return empty().with(key1, value1).with(key2, value2);
    }

    public ChaosContext with(String key, Object value) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(value, "value");
        Map<String, Object> copy = new HashMap<>(attributes);
        copy.put(key, value);
        return new ChaosContext(Map.copyOf(copy));
    }

    public Object get(String key) {
        return attributes.get(key);
    }

    public Map<String, Object> attributes() {
        return attributes;
    }
}
