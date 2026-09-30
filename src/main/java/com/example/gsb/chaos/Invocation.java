package com.example.gsb.chaos;

import java.time.Instant;

/**
 * 一次注入点调用的完整信息，作为规则匹配（含自定义条件）的输入。
 *
 * @param pointName       注入点名称
 * @param invocationCount 该注入点在本次引擎生命周期内的第几次调用（从 1 开始）
 * @param thread          发起调用的线程
 * @param context         当前作用域携带的上下文
 * @param occurredAt      调用发生时间（来自注入的时钟）
 */
public record Invocation(String pointName,
                         long invocationCount,
                         Thread thread,
                         ChaosContext context,
                         Instant occurredAt) {

    public Object contextAttribute(String key) {
        return context == null ? null : context.get(key);
    }
}
