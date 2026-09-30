# 混沌故障注入框架（Chaos Injection Framework）

一个零外部依赖的 Java 混沌工程小框架（包名 `com.example.gsb.chaos`），用于在业务代码的
可控位置（注入点）按配置注入故障，并观察业务的恢复过程。仅需 Java 17 + JUnit 5。

- **注入点**：业务代码用一个字符串名称（如 `storage.read.before`、`message.send.after`）标记。
- **故障类型**：抛异常、延迟、返回错误结果；另支持“只对一定比例调用生效”。
- **可注入时钟**：延迟与超时基于 `ChaosClock`；测试用 `MutableChaosClock` 只推进逻辑时间，不真实等待。
- **规则匹配**：按注入点名称 / 调用次数区间 / 概率 / 线程 / 上下文 / 自定义条件决定是否注入。
- **作用域隔离**：注入只在显式打开的 `ChaosScope`（线程级）内生效，不污染其它测试与线程。
- **注入记录**：记录每次注入的时间、注入点、故障类型，以及是否被业务正确捕获。
- **一键关闭**：`setEnabled(false)` 后所有注入点直接透传，行为与未接入框架时完全一致。

## 快速开始

```java
InMemoryInjectionRecorder recorder = new InMemoryInjectionRecorder();

ChaosEngine chaos = ChaosEngine.builder()
        .clock(MutableChaosClock.startingAt(Instant.parse("2026-01-01T00:00:00Z"))) // 测试时钟
        .random(new Random(42))
        .recorder(recorder)
        .rule(ChaosRule.named("storage.read.before")
                .invocationsBetween(2, 4)          // 第 2~4 次调用
                .probability(0.5)                  // 其中 50% 概率
                .fault(Fault.exception("storage unavailable")))
        .build();

try (ChaosScope ignored = chaos.openScope(ChaosContext.of("tenant", "test"))) {
    String result = chaos.inject("storage.read.before",
            () -> storage.read(key),               // 正常业务逻辑
            new FaultHandler<>() {                 // 可选：业务侧恢复 / 校验钩子
                @Override
                public String onError(RuntimeException error) {
                    return readFromCache(key);      // 降级成功 -> 记录为“被业务正确捕获”
                }
            });
}

recorder.records(); // 查看所有注入记录
```

## 注入点

在希望施加控制的位置，用名称包裹业务调用：

```java
chaos.inject("storage.read.before", () -> storage.read(key)); // 有返回值
chaos.inject("message.send.after", () -> messageBus.send(msg)); // 无返回值
```

引擎只有在以下三种情况下会改变行为：当前线程打开了 `ChaosScope`、规则命中、引擎处于开启状态；
其余情况一律直接执行业务逻辑。

## 故障类型

| 工厂方法 | 语义 | 业务逻辑是否执行 | 记录中 `caughtByBusiness` 的判定 |
|---|---|---|---|
| `Fault.exception(Supplier)` / `Fault.exception(String)` | 在注入点抛出异常（默认 `ChaosInjectedException`） | 否 | 业务通过 `FaultHandler.onError` 恢复返回 → `true`；异常继续传播 → `false` |
| `Fault.latency(Duration)` | 先通过 `ChaosClock.sleep` 延迟，再正常执行业务 | 是 | 业务正常完成 → `true` |
| `Fault.corruptResult(Object)` / `corruptResult(Supplier)` | 业务照常执行，返回值被替换成错误结果 | 是（结果丢弃） | `FaultHandler.onResult` 校验后抛出拒绝 → `true`；直接接受错误结果 → `false` |
| 规则的 `.probability(double)` | 不改变故障本身，只控制命中比例 | — | — |

## 可注入时钟与超时

- `SystemChaosClock`（默认）：`sleep` 真实睡眠，生产环境使用。
- `MutableChaosClock`：`sleep`/`advance` 只把逻辑时间向前推进，测试瞬时完成、确定性可复现。

```java
// 30 秒延迟故障：真实时间几乎不流逝
chaos.rule(...).fault(Fault.latency(Duration.ofSeconds(30)));
```

超时同样基于注入时钟测量经过时长，超时抛 `ChaosTimeoutException`：

```java
chaos.withTimeout("rpc.call", Duration.ofSeconds(3), () -> rpcClient.call());
```

延迟故障把逻辑时钟推过超时阈值后，`withTimeout` 即抛异常，测试无需真实等待。

## 规则匹配（ChaosRule）

所有匹配条件之间是 **AND** 关系；多条规则按注册顺序匹配，**第一条命中的规则生效**
（first match wins）；若某条规则除概率外的条件全部命中但本次概率采样未中，则直接判定
本次不注入，不再继续看后续规则。调用次数按注入点名称分别计数，从 1 开始。

| 匹配方法 | 含义 |
|---|---|
| `ChaosRule.named(String)` | 精确匹配注入点名称 |
| `ChaosRule.matching(String)` | 注入点名称正则匹配（须整体匹配，如 `storage\\..*`） |
| `.invocationsBetween(min, max)` | 仅在第 `min`～`max` 次调用（含端点）时生效，如第 2、3 次调用失败 |
| `.probability(p)` | `0.0~1.0`，只对该比例的调用生效；随机源由 builder 注入，可固定种子 |
| `.onThreadMatching(String)` | 线程名正则，如只对 `chaos-worker-\d+` 生效 |
| `.inContext(key, value)` | 要求当前作用域上下文属性等于指定值，如 `tenant=test` |
| `.when(Predicate<Invocation>)` | 自定义条件，可读取注入点、调用次数、线程、上下文、发生时间 |
| `.fault(Fault)` | 必填：命中后触发的故障；未设置故障的规则无法注册 |

示例：

```java
ChaosRule.named("storage.read")
        .invocationsBetween(2, 4)
        .inContext("tenant", "test")
        .when(inv -> inv.invocationCount() % 2 == 0)
        .probability(0.5)
        .fault(Fault.exception("boom"));
```

## 作用域隔离

注入只在显式打开的 `ChaosScope` 内、且仅对打开它的线程生效（基于 `ThreadLocal`），
作用域可嵌套，`close()` 后恢复上一层。没有作用域时 `inject` 完全透传，不计数、不记录。

```java
try (ChaosScope scope = chaos.openScope(ChaosContext.of("tenant", "test"))) {
    // 仅此线程、此上下文受规则影响
    chaos.inject("storage.read", () -> storage.read(key));
}
// 作用域外恢复透传
```

配合规则的 `.inContext(...)` 与 `.onThreadMatching(...)`，可以把故障精确限定在指定上下文
或指定线程，其它测试天然不受污染。

## 注入记录（InjectionRecord）

每条记录包含：

| 字段 | 内容 |
|---|---|
| `occurredAt` | 注入发生时间（取自注入的时钟） |
| `pointName` | 注入点名称 |
| `faultType` | `EXCEPTION` / `LATENCY` / `CORRUPT_RESULT` |
| `caughtByBusiness` | 故障是否被业务正确捕获（判定见故障类型表） |
| `detail` | 附加信息（异常内容、延迟时长、接受/拒绝说明） |

默认使用线程安全的 `InMemoryInjectionRecorder`（`records()` / `clear()`）；
也可实现 `InjectionRecorder` 接口把记录输出到日志或指标系统。

## 一键关闭

```java
chaos.setEnabled(false); // 全局开关（volatile），关闭后所有注入点直接透传
```

关闭状态下：不计数、不匹配规则、不推进时钟、不写记录，`inject` 的返回值与异常行为与
直接执行业务逻辑完全一致（见 `KillSwitchTest` 逐点对比）；重新 `setEnabled(true)` 后恢复。

## 运行测试

```bash
./mvnw -q verify
```

## 测试覆盖

| 测试类 | 覆盖内容 |
|---|---|
| `ExceptionFaultTest` | 异常注入、业务降级恢复、未命中注入点透传 |
| `LatencyFaultTest` | 延迟推进逻辑时钟且不真实等待 |
| `CorruptResultFaultTest` | 错误结果替换；业务校验拒绝即视为捕获 |
| `ProbabilityFaultTest` | 概率 0 / 1 / 0.5（固定种子的比例区间） |
| `RuleMatchingTest` | 调用次数区间、名称正则、自定义条件、多规则优先级、规则校验 |
| `ScopeIsolationTest` | 无作用域透传、线程隔离、上下文匹配、线程名匹配 |
| `InjectionRecordingTest` | 记录时间/注入点/类型/捕获标记/详情 |
| `TimeoutTest` | 延迟触发超时、正常通过、超时异常字段 |
| `KillSwitchTest` | 关闭后行为与裸业务调用一致；重新开启恢复注入 |
