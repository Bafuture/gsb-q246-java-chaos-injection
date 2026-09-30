# 混沌故障注入框架

Pair-wise GSB 标注任务仓库（第 16 批 / 246）。

| 项目 | 内容 |
|------|------|
| 任务类型 | Feature 迭代 |
| 任务难度 | 困难 |
| 语言/框架 | Java, Maven, JUnit 5 |
| 环境可复现等级 | 无外部依赖 |
| 构建方式 | Maven（含 mvnw wrapper，无需本机安装 Maven） |

> 本仓库是**初始环境快照**：只有工程骨架，不含任何实现代码。
> 分支说明：`main` 为初始环境；`A`、`B` 为两次独立执行各自的工作分支，均从 `main` 的同一个提交拉出。

## 运行方式

```bash
./mvnw -q verify
```

## 框架使用文档

一个零外部依赖的混沌故障注入框架，包名 `com.example.chaos`。

### 1. 在业务代码中标记注入点

业务代码只依赖静态门面 `Chaos`，注入点通过名字标识（支持中文，如“读存储前”“发消息后”）：

```java
// 有返回值：返回业务结果，或被故障替换 / 抛异常
String data = Chaos.call("read-storage", () -> storage.read());

// 无返回值
Chaos.run("after-send-message", () -> metrics.increment());

// 纯探针：只可能触发异常 / 延迟，不包裹业务逻辑
Chaos.touch("before-read-storage");

// 随调用携带上下文属性，供规则匹配
Chaos.call("charge", Map.of("tenant", "gold"), () -> payment.charge());
```

### 2. 故障类型（`Faults` 工厂）

| 工厂方法 | 行为 |
|---------|------|
| `Faults.exception(Supplier<Throwable>)` / `exception(Throwable)` / `exception(String)` | 在注入点抛出异常 |
| `Faults.delay(Duration)` | 延迟指定时长后让业务继续执行 |
| `Faults.timeout(Duration)` | 延迟指定时长后抛出 `ChaosTimeoutException` |
| `Faults.returnError(Object)` | 不执行业务，直接返回错误结果（仅 `call` 点生效） |

“只对一定比例生效”通过规则的 `probability(double, RandomGenerator)` 配置，见下文。

### 3. 时间控制（可注入时钟）

延迟与超时都走 `ChaosClock`，不直接 `Thread.sleep`：

- `SystemChaosClock`（默认）：真实等待。
- `TestClock`：虚拟时钟，`sleep` 只推进虚拟时间、不做真实等待，测试瞬时完成。

```java
TestClock clock = TestClock.startingAt(Instant.parse("2026-10-01T08:00:00Z"));
Chaos.useClock(clock);
// 注入 30s 延迟后，clock.now() 前进 30s，但墙钟几乎不流逝
```

### 4. 规则匹配

规则由 `Rule.builder()` 构建，加入会话后按**顺序**匹配：第一条通过全部确定性匹配
**且概率判定成功**的规则生效；概率未命中会继续尝试后续规则。

| 匹配维度 | 构建方法 | 说明 |
|---------|---------|------|
| 注入点名称 | `point(String)` / `pointMatches(Predicate<String>)` | 精确或自定义（前缀/正则等）匹配 |
| 调用次数区间 | `invocations(from, to)` / `invocation(n)` / `fromInvocation(n)` | 次数从 1 开始，闭区间；按“会话 + 注入点”分别计数，所有调用都计数（含未命中） |
| 概率 | `probability(double)` / `probability(double, RandomGenerator)` | 取值 [0,1]；测试可传固定种子 `Random` 保证确定性 |
| 自定义条件 | `when(Predicate<InjectionContext>)` | 可读调用次数、线程、业务上下文属性 |
| 线程 | `forThread(Predicate<Thread>)` | 例如只对名为 `chaos-target` 的线程生效 |

```java
Rule rule = Rule.onPoint("read-storage")
        .invocations(2, 5)                        // 第 2~5 次调用
        .probability(0.5, new Random(42))        // 50% 概率
        .when(ctx -> "gold".equals(ctx.attribute("tenant"))) // 仅 gold 租户
        .forThread(t -> t.getName().equals("worker-1"))      // 仅指定线程
        .fault(Faults.exception(new IOException("io")))
        .build();
```

### 5. 作用域隔离

规则放在会话（`ChaosSession`）中激活，会话实现 `AutoCloseable`：

- **线程作用域**（默认）：`session.activate()` 只影响激活它的线程，其它线程完全无感。
- **全局作用域**：`session.activateGlobally()` 对所有线程生效，可再用 `forThread` /
  `when` 收窄。线程作用域优先于全局作用域（线程有本地会话时不回退全局）。
- **上下文作用域**：`try (ChaosContext c = Chaos.openContext("tenant", "gold"))` 内
  携带业务属性，规则通过 `when(ctx -> ...)` 实现“只对指定上下文生效”。
- 会话 `close()` 或 `Chaos.reset()` 后立即失效，避免污染其它测试。

### 6. 注入记录与捕获状态

每次**实际注入**产生一条 `InjectionRecord`，包含：序号、时间（来自可注入时钟）、
注入点、该点调用次数、故障类型、故障描述、延迟时长、抛出的异常实例、替换返回值、
线程、上下文属性快照、捕获状态（`CaptureState`）。

捕获状态三态：

- `UNKNOWN`（默认）：尚未观察到业务处理；
- `HANDLED`：业务正确捕获，通过 `Chaos.markHandled("注入点名")`（标记该点最近一次）
  或 `Chaos.markHandled(异常实例)` 标记；
- `ESCAPED`：未被捕获，通过 `Chaos.markEscaped("注入点名")` 标记。

### 7. 一键关闭

`Chaos.disable()` 后所有注入点直接透传：不匹配规则、不推进时钟、不产生记录，
行为与未接入框架完全一致（见 `KillSwitchTest` 的等价性验证）。
`Chaos.enable()` 恢复，`Chaos.reset()` 会清理全部会话、计数、记录并恢复启用。

### 8. 代码结构

| 类 | 职责 |
|----|------|
| `Chaos` | 静态门面，业务代码唯一依赖入口 |
| `ChaosEngine` | 规则评估、计数、故障执行、记录存储 |
| `ChaosSession` | 规则集合 + 线程/全局作用域 |
| `Rule` | 名称/次数/概率/条件/线程匹配规则 |
| `Fault` / `Faults` / `FaultOutcome` | 故障抽象、内建工厂、执行结果 |
| `ChaosClock` / `SystemChaosClock` / `TestClock` | 可注入时钟（真实/虚拟） |
| `ChaosContext` | 基于线程栈的上下文属性 |
| `InjectionRecord` / `CaptureState` / `FaultType` | 记录与状态模型 |

## 任务提示词

以下为本题完整的 User Prompt 原文，两次执行必须使用完全相同的文本。

我们要验证系统的容错能力，需要在可控的位置注入故障并观察恢复过程。请从零实现一个混沌故障注入框架。仓库目前只有一个空的 Maven 工程（pom.xml 只声明 JUnit 5 与 AssertJ）。要求：1) 支持在代码中标记注入点（例如"读存储前""发消息后"），框架按配置在这些点触发故障；2) 支持多种故障类型：抛异常、延迟、返回错误结果、只对一定比例生效；3) 支持时间控制：延迟与超时使用可注入时钟，测试不依赖真实等待；4) 支持规则匹配：按注入点名称、调用次数区间、概率或自定义条件决定是否注入，规则写进 README；5) 支持作用域隔离：注入只对指定线程或指定上下文生效，避免污染其它测试；6) 支持注入记录：记录每次注入的时间、注入点、故障类型与是否被业务正确捕获；7) 支持一键关闭：关闭后所有注入点直接透传，需验证关闭状态下行为与未接入框架时一致；8) 测试覆盖各故障类型、规则匹配、作用域隔离、记录内容与关闭后透传；`mvn -q verify` 一条命令跑通。

## 提交要求

1. 在本仓库中完成提示词要求的全部内容。
2. `./mvnw -q verify` 必须通过。
3. 完成后在所属分支（A 或 B）上提交，产物快照的父提交必须是初始环境快照。
