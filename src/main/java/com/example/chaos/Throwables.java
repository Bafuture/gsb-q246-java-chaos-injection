package com.example.chaos;

/** 内部工具：允许抛出任意 Throwable 而不在方法签名上声明（sneaky throw）。 */
public final class Throwables {

    private Throwables() {
    }

    public static RuntimeException sneakyThrow(Throwable throwable) {
        Throwables.<RuntimeException>sneaky(throwable);
        return null;
    }

    @SuppressWarnings("unchecked")
    private static <T extends Throwable> void sneaky(Throwable throwable) throws T {
        throw (T) throwable;
    }
}
