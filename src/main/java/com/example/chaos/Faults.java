package com.example.chaos;

import java.time.Duration;
import java.util.function.Supplier;

/** 内建故障工厂：抛异常、延迟、超时、返回错误结果。 */
public final class Faults {

    private Faults() {
    }

    /** 每次命中都抛出一个由 supplier 新建的异常。 */
    public static Fault exception(Supplier<? extends Throwable> exceptionSupplier) {
        return new AbstractFault(FaultType.EXCEPTION, "exception") {
            @Override
            public FaultOutcome activate(InjectionContext context, ChaosClock clock) {
                return FaultOutcome.raised(exceptionSupplier.get());
            }
        };
    }

    /** 每次命中都抛出同一个异常实例（注意：实例可被反复抛出）。 */
    public static Fault exception(Throwable throwable) {
        return exception(() -> throwable);
    }

    public static Fault exception(String message) {
        return exception(new ChaosInjectionException(message));
    }

    /** 延迟指定时间后让业务继续执行。 */
    public static Fault delay(Duration duration) {
        return new AbstractFault(FaultType.DELAY, "delay " + duration) {
            @Override
            public FaultOutcome activate(InjectionContext context, ChaosClock clock) {
                clock.sleep(duration);
                return FaultOutcome.proceed(duration);
            }
        };
    }

    /** 延迟指定时间后抛出 {@link ChaosTimeoutException}。 */
    public static Fault timeout(Duration duration) {
        return new AbstractFault(FaultType.TIMEOUT, "timeout " + duration) {
            @Override
            public FaultOutcome activate(InjectionContext context, ChaosClock clock) {
                clock.sleep(duration);
                return FaultOutcome.raisedAfter(new ChaosTimeoutException(duration), duration);
            }
        };
    }

    /** 直接返回指定的错误结果，不执行业务逻辑。 */
    public static Fault returnError(Object errorResult) {
        return new AbstractFault(FaultType.RETURN_ERROR, "return " + errorResult) {
            @Override
            public FaultOutcome activate(InjectionContext context, ChaosClock clock) {
                return FaultOutcome.returned(errorResult);
            }
        };
    }

    private abstract static class AbstractFault implements Fault {
        private final FaultType type;
        private final String description;

        AbstractFault(FaultType type, String description) {
            this.type = type;
            this.description = description;
        }

        @Override
        public FaultType type() {
            return type;
        }

        @Override
        public String describe() {
            return description;
        }
    }
}
