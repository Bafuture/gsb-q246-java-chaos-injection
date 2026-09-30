package com.example.chaos;

import java.util.Map;

/**
 * 一次注入点调用的上下文，供规则的自定义条件判断使用。
 *
 * @param point       注入点名称
 * @param invocation  该注入点在所属会话内的第几次调用（从 1 开始）
 * @param thread      当前执行线程
 * @param attributes  随调用携带或从 {@link ChaosContext} 继承的上下文属性
 */
public record InjectionContext(String point, long invocation, Thread thread,
                               Map<String, Object> attributes) {

    /** 读取属性，不存在返回 null。 */
    public Object attribute(String key) {
        return attributes.get(key);
    }

    /** 读取指定类型的属性。 */
    @SuppressWarnings("unchecked")
    public <T> T attribute(String key, Class<T> type) {
        return (T) attributes.get(key);
    }
}
