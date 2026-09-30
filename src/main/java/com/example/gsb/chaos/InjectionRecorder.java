package com.example.gsb.chaos;

/**
 * 注入记录的去向。可实现该接口把记录接入日志、指标系统等。
 */
public interface InjectionRecorder {
    void record(InjectionRecord record);
}
