package com.example.chaos.demo;

import com.example.chaos.Chaos;

import java.util.ArrayList;
import java.util.List;

/**
 * 演示业务：订单服务。在读存储前、发消息后标记了注入点，
 * 并对存储读取失败做了重试 + 降级的容错处理。
 */
public class OrderService {

    public static final String BEFORE_READ_STORAGE = "读存储前";
    public static final String AFTER_SEND_MESSAGE = "发消息后";

    private final Storage storage;
    private final List<String> sentMessages = new ArrayList<>();

    public OrderService(Storage storage) {
        this.storage = storage;
    }

    /** 读取订单：注入点在读存储前；失败时重试一次，仍失败则降级返回默认值。 */
    public String loadOrder(String orderId) {
        try {
            return readWithRetry(orderId);
        } catch (RuntimeException e) {
            Chaos.markHandled(BEFORE_READ_STORAGE);
            return "FALLBACK-ORDER";
        }
    }

    private String readWithRetry(String orderId) {
        RuntimeException lastError = null;
        for (int attempt = 0; attempt < 2; attempt++) {
            try {
                Chaos.touch(BEFORE_READ_STORAGE);
                return storage.read(orderId);
            } catch (RuntimeException e) {
                lastError = e;
            }
        }
        throw lastError;
    }

    /** 发送通知：注入点在发消息后；消息发送失败只记录，不影响主流程。 */
    public void notifyUser(String orderId) {
        Chaos.run(AFTER_SEND_MESSAGE, () -> sentMessages.add("notified:" + orderId));
    }

    public List<String> sentMessages() {
        return List.copyOf(sentMessages);
    }

    /** 模拟底层存储。 */
    public interface Storage {
        String read(String orderId);
    }
}
