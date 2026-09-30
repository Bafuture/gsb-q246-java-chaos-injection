package com.example.chaos;

/** 被注入故障的捕获状态。 */
public enum CaptureState {
    /** 尚未观察到业务处理该故障（默认）。 */
    UNKNOWN,
    /** 业务通过 {@link Chaos#markHandled} 明确标记已正确处理。 */
    HANDLED,
    /** 故障在记录窗口内未被标记（例如异常逃逸出业务边界）。 */
    ESCAPED
}
