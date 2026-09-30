package com.example.gsb.chaos;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 默认的内存记录器，线程安全，测试可直接断言其中的记录。
 */
public final class InMemoryInjectionRecorder implements InjectionRecorder {
    private final List<InjectionRecord> records = new CopyOnWriteArrayList<>();

    @Override
    public void record(InjectionRecord record) {
        records.add(Objects.requireNonNull(record, "record"));
    }

    public List<InjectionRecord> records() {
        return List.copyOf(records);
    }

    public void clear() {
        records.clear();
    }
}
