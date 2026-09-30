package com.example.gsb.chaos;

/**
 * 业务侧对故障的处理钩子，用于观察“故障是否被业务正确捕获”。
 *
 * <ul>
 *   <li>{@link #onError}：异常故障触发时调用。若实现返回了降级结果（不再抛出），
 *       视为业务正确捕获；若异常继续向外传播，视为未捕获。</li>
 *   <li>{@link #onResult}：结果替换故障触发时调用，业务可在此校验结果。
 *       若实现抛出异常（拒绝错误结果），视为业务正确捕获；若正常返回，视为未察觉。</li>
 * </ul>
 */
public interface FaultHandler<T> {

    FaultHandler<Object> PASSTHROUGH = new FaultHandler<>() {
    };

    /** 异常故障触发时的降级逻辑；默认原样抛出。 */
    default T onError(RuntimeException error) {
        throw error;
    }

    /** 结果替换故障触发时的结果校验；默认原样接受。 */
    default T onResult(T result) {
        return result;
    }

    @SuppressWarnings("unchecked")
    static <T> FaultHandler<T> passthrough() {
        return (FaultHandler<T>) PASSTHROUGH;
    }
}
