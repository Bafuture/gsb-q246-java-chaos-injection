package com.example.gsb.chaos;

import java.time.Instant;
import java.util.Objects;

/**
 * 一次故障注入的记录。
 *
 * @param occurredAt       注入发生时间（来自注入的时钟）
 * @param pointName        注入点名称
 * @param faultType        故障类型
 * @param caughtByBusiness 故障是否被业务正确捕获（见 {@link FaultHandler} 的语义；
 *                         延迟故障业务正常完成时记为 {@code true}）
 * @param detail           附加信息（异常消息、延迟时长等）
 */
public record InjectionRecord(Instant occurredAt,
                              String pointName,
                              FaultType faultType,
                              boolean caughtByBusiness,
                              String detail) {
    public InjectionRecord {
        Objects.requireNonNull(occurredAt, "occurredAt");
        Objects.requireNonNull(pointName, "pointName");
        Objects.requireNonNull(faultType, "faultType");
    }
}
