package com.example.chaos;

/** 故障类型标识。 */
public enum FaultType {
    /** 抛出异常（业务可捕获恢复）。 */
    EXCEPTION,
    /** 延迟指定时间后让业务继续执行。 */
    DELAY,
    /** 延迟指定时间后抛出超时异常。 */
    TIMEOUT,
    /** 不执行业务，直接返回错误结果。 */
    RETURN_ERROR
}
