package com.example.chaos;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;

/**
 * 一次实际注入的记录。
 *
 * @param sequence   引擎内的全局序号（从 1 开始）
 * @param time       注入发生的时间（来自可注入时钟）
 * @param point      注入点名称
 * @param invocation 该注入点在所属会话内的调用次数（从 1 开始）
 * @param faultType  故障类型
 * @param description 故障描述
 * @param delay      延迟 / 超时时长（无延迟为 null）
 * @param thrown     抛出的异常实例（未抛出为 null）
 * @param returned   替换返回值（无替换为 null）
 * @param thread     触发注入的线程
 * @param attributes 触发时的上下文属性快照
 * @param capture    是否被业务正确捕获
 */
public record InjectionRecord(
        long sequence,
        Instant time,
        String point,
        long invocation,
        FaultType faultType,
        String description,
        Duration delay,
        Throwable thrown,
        Object returned,
        Thread thread,
        Map<String, Object> attributes,
        CaptureState capture) {

    InjectionRecord withCapture(CaptureState state) {
        return new InjectionRecord(sequence, time, point, invocation, faultType, description,
                delay, thrown, returned, thread, attributes, state);
    }
}
