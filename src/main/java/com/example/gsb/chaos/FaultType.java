package com.example.gsb.chaos;

/**
 * 框架支持的故障类型。
 */
public enum FaultType {
    /** 在业务逻辑执行前抛出异常，例如模拟“读存储前”存储不可用。 */
    EXCEPTION,
    /** 让当前注入点等待一段时间后再执行业务逻辑。 */
    LATENCY,
    /** 业务照常执行，但返回给调用方的结果被替换成错误结果。 */
    CORRUPT_RESULT
}
