# Polymarket WSS 大规模订阅阶段汇报

**汇报日期：** 2026-10-14  
**项目：** `polymarket-wss-benchmark`  
**结论口径：** 本文只陈述现有项目文档、`reports/` 报告/日志可支持的事实。推断均显式标注；**当前没有证据证明 20 万 Token 已稳定运行。**

## 1. 执行摘要

1. 单连接 5,000 Token 已完成两轮各约 10 分钟的有效测试：均完整提交订阅、持续收到数据、`dropped=0`、期末队列为 0，未记录异常 close/failure。该结果支持“5,000 Token/连接是下一阶段生产候选起点”，不等价于长期生产容量认证。
2. 单连接 10,000 Token 已完成两轮各约 15 分钟的测试，但两轮均出现连接中断并自动恢复，不能判为稳定容量。第二轮保留了明确服务端 Close：`1013: slow consumer: send buffer full`。
3. 1013 的直接事实是服务端报告发送缓冲无法继续排空；现有数据不支持把它归因于 Java 业务队列、持续 CPU 饱和、Full GC，亦不支持断言 Polymarket 存在固定 10,000 Token 官方硬上限。
4. 新一轮 2C2G、2 条连接各 5,000 Token（等效 `2×5,000`）因 TCP Connect Timeout 未建立有效数据连接，最终为 **0 订阅、0 Frame**。该轮属于网络建连失败，必须判为 **无效实验（INCONCLUSIVE/INVALID）**，不得用于证明或否定多连接容量。
5. 20 万 Token 当前仅有候选架构：优先以 4,000—5,000 Token/连接分片，多节点部署并保留 N+1 接管能力。必须完成多轮高活跃窗口、24 小时运行和故障接管演练后，才能形成生产容量结论。

## 2. 问题与目标

### 2.1 问题

项目需要接收约 218,634 个 asset ID 的 Polymarket Market WebSocket 数据。核心问题不是寻找一个孤立的“最大订阅数”，而是确认在给定硬件、JVM、网络和市场活跃度下，客户端能否持续完成：

- 订阅提交与恢复；
- WebSocket 解帧和 `onMessage` 回调；
- 事件分类、排队与消费；
- PING/PONG 保活；
- 异常连接的受控恢复；
- 在单节点或单连接故障时维持总体覆盖和可接受的数据新鲜度。

### 2.2 阶段目标

- 建立 5,000 与 10,000 Token/连接的事实基线；
- 解释 10,000 Token 现场的 1013 证据链；
- 排除明显客户端瓶颈，并列出仍缺失的网络/时间窗口证据；
- 给出 20 万 Token 的候选拓扑、恢复策略和下一轮验证门槛；
- 坚持“实验观测不是平台官方限制，也不是生产稳定性承诺”。

## 3. 实验环境、版本与 JVM

| 项目 | 已有证据 |
|---|---|
| 应用版本 | `polymarket-wss-benchmark-1.0.0.jar` |
| Java | Java 21；运行日志记录 `21.0.12.1` |
| Spring Boot | `3.5.7`（`pom.xml`） |
| OkHttp | `4.12.0`（`pom.xml`） |
| WebSocket | `wss://ws-subscriptions-clob.polymarket.com/ws/market` |
| 单连接正式轮 JVM | G1；16 CPU 可用；主机内存约 65,267 MiB；初始/最小堆 1 GiB、最大堆 2 GiB（项目根目录 GC 日志 `,uptime,level,tags*`） |
| 2C2G 等效轮 JVM | G1；16 CPU 中 2 CPU 可用；主机内存约 65,267 MiB；初始/最小堆 512 MiB、最大堆 1,200 MiB（`reports/mc2x5000-2c2g-gc.log`） |
| 基准配置 | 100 Token/批、250 ms 批间隔、队列容量 10,000、4 消费者、模拟处理延迟 0、PING 10 s、自动重连 |
| 分片 | 项目支持 `ROUND_ROBIN` / `CONTIGUOUS`；生产候选优先 `ROUND_ROBIN` |

> 边界：现有证据没有完整记录公网出口、RTT、重传、TCP receive window、资产文件 SHA-256 和实验主机独占状态；这些因素限制了根因归因强度。

## 4. 5k 两轮与 10k 两轮数据

下表的速率由阶段累计量除以阶段时长计算：5k 为 600 秒，10k 为 900 秒。它反映阶段平均，不代表窗口峰值。

| 轮次 | Token/连接 | 时长 | Frames | Bytes | 平均 frames/s | 平均 bytes/s | 平均 KiB/s | frames/KiB | book Frames / Bytes | dropped | 期末队列 | onMessage p99 / max | Max Frame | Close | 阶段判断 |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---|---:|---:|---|---:|---|---|
| 5k-R1 | 5,000 | 10 min | 188,715 | 123,290,171 | 314.53 | 205,483.62 | 200.67 | 1.5674 | 294 / 7,264,037 | 0 | 0 | 20 μs / 16.299 ms | 334,991 B | 报告内无异常；停止日志为 1000 | 有效、通过本轮门槛 |
| 5k-R2 | 5,000 | 10 min | 210,098 | 136,378,792 | 350.16 | 227,297.99 | 221.97 | 1.5775 | 308 / 7,253,142 | 0 | 0 | 20 μs / 68.039 ms | 343,931 B | 报告内无异常 | 有效、通过本轮门槛 |
| 10k-R1 | 10,000 | 15 min | 601,671 | 391,772,273 | 668.52 | 435,302.53 | 425.10 | 1.5726 | 528 / 21,838,927 | 0 | 0 | 10 μs / 116.076 ms | 349,520 B | 汇总字段为空；运行日志存在静默后恢复 | 有效但发生重连，不判稳定 |
| 10k-R2 | 10,000 | 15 min | 566,238 | 370,215,775 | 629.15 | 411,350.86 | 401.71 | 1.5662 | 586 / 22,122,726 | 0 | 0 | 10 μs / 76.836 ms | 348,259 B | `1013:slow consumer: send buffer full` | 有效但发生 1013/重连，不判稳定 |

### 4.1 报告证据文件

- 5k-R1：`reports/benchmark-2026-10-13T13-21-59.868685500Z.json`、`reports/s1-5000-20261013-211150.stdout.log`、`reports/s1-5000-20261013-211150.resources.csv`
- 5k-R2：`reports/benchmark-2026-10-13T13-44-55.865167700Z.json`、`reports/s1r2-5000-20261013-213444.stdout.log`、`reports/s1r2-5000-20261013-213444.resources.csv`
- 10k-R1：`reports/benchmark-2026-10-13T14-02-51.035960100Z.json`、`reports/s2r1-10000-20261013-214733.stdout.log`、`reports/s2r1-10000-20261013-214733.resources.csv`
- 10k-R2：`reports/benchmark-2026-10-13T14-29-17.731530900Z.json`、`reports/s2r2-10000-20261013-run.log`、`reports/s2r2-10000-20261013-resources.csv`、`reports/s2r2-10000-20261013-gc.log`

### 4.2 数据解读

- 5k 两轮平均吞吐约 315—350 frames/s、201—222 KiB/s，且队列基本即时回落、无 drop。
- 10k 两轮阶段平均约 629—669 frames/s、402—425 KiB/s，近似随 Token 数增大，但不同时间窗口的市场活跃度不可控，不能据此建立严格线性模型。
- `book` 帧只占总帧约 0.09%—0.16%，却占总字节约 5.3%—6.0%，说明 `book` 是低频大帧，不能只看 Frame 数评估带宽与内存压力。
- 10k-R1 汇总 JSON 的 `close` 为空，但运行日志出现约 40 秒零流量后从 2,500 Token 重新加载至 10,000 Token，属于发生重连的日志证据；不能因最终汇总为空而判为无异常。

## 5. 10 月 13 日 1013 时间线与证据链

以下时间均为日志本地时间（UTC+8），对应 `reports/s2r2-10000-20261013-run.log`：

| 时间 | 事件 | 证据解释 |
|---|---|---|
| 22:14:20.952 | generation=1 建连 | 开始首代连接 |
| 22:14:26.125 | 已提交约 2,000 Token | 100 Token/批、250 ms/批持续加载 |
| 22:14:36.131 | 已提交约 5,800 Token | 同上 |
| 22:14:46.127 | 已提交约 9,600 Token | 同上 |
| 约 22:14:47 | 完成 10,000 Token 提交 | 推算自批次日志；不是服务端逐 Token ACK |
| 22:14:48.361 | 最后一次记录的 PONG（Close 前） | 距 Close 约 14.6 秒；10 秒采样不足以证明 PONG 响应自身延迟 |
| 22:14:56.120 | 窗口 987.9 frames/s、642,850.5 bytes/s；队列 1/10,000；drop=0；累计 p99=50 μs | Close 前存在较高流量，但客户端业务队列未持续积压 |
| 22:15:02.997 | 服务端发 Close 1013，reason=`slow consumer: send buffer full` | 直接关闭原因确定；全量提交约 16 秒后触发 |
| 约 22:15:04.8 | generation=2 建立 | 自动重连，间隔约 1.8 秒 |
| 22:15:18.400 | generation=2 已恢复约 5,200 Token | `reports/s2r2-10000-20261013-resources.csv` |
| 22:15:38.832 | generation=2 已恢复 10,000 Token | 同配置恢复完成 |
| 后续至实验结束 | 持续收数，最终累计 drop=0 | 表明 10,000 Token/250 ms 不是“每次必然失败”的硬阈值 |

GC 证据：Close 前约 22:14:20.231 后没有 Young/Full GC；Close 附近仅有微秒级 Cleanup safepoint。因此，现有 GC 日志不支持“GC pause 直接导致本次 1013”。

## 6. 已排除、高概率与未知

| 分级 | 判断 | 依据与边界 |
|---|---|---|
| **已证明** | 服务端以 1013 主动关闭 generation=1，并给出 `slow consumer: send buffer full` | Close code/reason 是直接证据 |
| **基本排除** | Java 业务队列持续积压或队列满 | Close 前队列 1/10,000，最终 drop=0；`enqueued` 与 `processed` 基本一致 |
| **基本排除** | 持续 CPU 饱和 | 资源采样未显示持续饱和；但采样粒度不足以排除亚秒抖动 |
| **基本排除** | Full GC 或 Close 前 GC pause | 对应 GC 日志无 Full GC，Close 前无 Young GC |
| **高概率机制** | 服务端到该客户端的单连接下行缓冲在瞬时窗口内无法及时排空 | reason 文本与数据方向吻合；这是对 `send buffer` 的高概率解释，不是服务端内部实现证明 |
| **高概率触发组合** | 订阅加载期初始快照/推送洪峰、资产活跃度、服务端节点或缓冲状态共同作用 | Close 发生于完成全量订阅后约 16 秒；但同速 generation=2 成功，不能归于单一速率 |
| **仍可能** | OkHttp 读线程出现亚秒级停顿 | 源码机制成立；累计 max 回调不能精确定位到 Close 时刻 |
| **仍可能** | TCP 接收窗口收缩、RTT/重传或公网链路短暂异常 | 尚无抓包和精细 TCP 指标 |
| **证据不足** | 固定 10,000 Token 官方限制 | generation=2 同配置运行；官方文档未公布该上限 |
| **未知** | Polymarket slow-consumer 判定窗口、服务端缓冲大小、节点负载 | 官方未公开，客户端日志不能反推 |

## 7. OkHttp 4.12.0 源码事实

依据 OkHttp `parent-4.12.0` 的 `RealWebSocket.kt`：

1. `loopReader()` 在收到 Close 前循环调用 `reader.processNextFrame()`。
2. `RealWebSocket.onReadMessage(String/ByteString)` 直接调用应用的 `WebSocketListener.onMessage(...)`，没有先切换到独立业务线程；因此回调未返回时，同一读取循环不能继续处理下一条完整消息。
3. `WebSocket.queueSize()` 是**客户端本地到远端的出站消息 payload 队列字节数**，不是入站队列、TCP receive buffer，也不是服务端发送缓冲。
4. OkHttp 出站队列上限为 16 MiB；超过时本次 `send()` 返回 `false` 并发起 1001 关闭。实验日志中的约 8 KiB `queueSize` 不等于服务端 reason 所说的 `send buffer full`。
5. 因而，“客户端回调阻塞可能间接造成对端下行缓冲积压”在机制上成立；但当前 p99、队列和 GC 数据只能排除持续饱和，不能证明或排除一次亚秒级读停顿。

源码：<https://github.com/square/okhttp/blob/parent-4.12.0/okhttp/src/main/kotlin/okhttp3/internal/ws/RealWebSocket.kt>

## 8. 联网调研证据分级

### A 级：官方/协议注册表/版本源码，可用于事实依据

| 来源 | URL | 可支持事实 |
|---|---|---|
| Polymarket 官方 `agent-skills/websocket.md` | <https://github.com/Polymarket/agent-skills/blob/main/websocket.md> | Market endpoint；按 asset ID 订阅；动态 subscribe/unsubscribe；每 10 秒发送 `PING` 并接收 `PONG`；没有公布单连接 asset 数或连接数硬上限 |
| OkHttp 4.12.0 `RealWebSocket.kt` | <https://github.com/square/okhttp/blob/parent-4.12.0/okhttp/src/main/kotlin/okhttp3/internal/ws/RealWebSocket.kt> | 读循环、listener 回调线程关系、`queueSize` 的客户端出站语义、16 MiB 上限 |
| IANA WebSocket Close Code Registry | <https://www.iana.org/assignments/websocket/websocket.xhtml> | 1013 已注册为 `Try Again Later`；登记依据为后续 IETF HYBI 邮件记录 |
| RFC 6455 | <https://www.rfc-editor.org/rfc/rfc6455> | 建立 WebSocket Close Code 注册体系；RFC 原文未定义 1013，因此不能把 1013 误称为“RFC 6455 原文定义” |

### B 级：开源工程实现，仅作设计参考，不能证明平台限制

| 项目 | URL | 参考价值 | 限制 |
|---|---|---|---|
| `Kris77z/polymarket-websocket-client` | <https://github.com/Kris77z/polymarket-websocket-client> | 自动重连、指数退避、心跳、连接超时等工程模式 | 社区项目，不能代表 Polymarket 服务端承诺 |
| `nevuamarkets/poly-websockets` | <https://github.com/nevuamarkets/poly-websockets> | 动态订阅、订阅管理、自动重连和连接统计 | 社区项目；其中默认分组数字只能视为实现选择，不能当官方上限 |
| `copa-fi/nautilus-trader` commit `aa4a11f` | <https://github.com/copa-fi/nautilus-trader/commit/aa4a11fb8a53a34e548e527e44a6ac479fc21ace> | 2025-11-10 文档提交声称单连接超过 500 instruments 后初始订单簿快照可能不完整，并建议按 500/连接拆分 | 该提交仅增加 19 行文档、没有测试或源码证据，且明确标为 `undocumented limitation`；NautilusTrader 上游当前文档未保留该说法，不能当作 Polymarket 官方限制。本项目使用 `initial_dump=false`，仍需单独验证事件覆盖率 |
| `nautechsystems/nautilus_trader` commit `c5e6a02` | <https://github.com/nautechsystems/nautilus_trader/commit/c5e6a024c44db2e48ca550e9d69763656b3a109a> | 本地测试服务验证 peer-close 后保持消息转发任务、发出重连通知、重放订阅并以 connection epoch 隔离新旧连接 | 证明的是恢复测试方法与工程实现，不代表 Polymarket 容量或 RPO；其文档亦明确恢复不保证无损历史 |

### C 级：二手文章/经验材料

仅可作为假设发现入口，不用于容量结论；本文管理结论不依赖此类材料。

## 9. 新一轮 2C2G、等效 2×5,000：无效实验

新一轮意图是在 2C2G 等效约束下，以 2 条连接分别承载 5,000 Token，验证多连接扩展性。现有产物包括：

- `reports/mc2x5000-2c2g-gc.log`
- `reports/mc2x5000-2c2g.jfr`（当前文件大小为 0）

JVM 日志确认 2 CPU 可用、G1、`-Xms512m`、`-Xmx1200m`，但该轮发生 TCP Connect Timeout，未形成有效 WebSocket 数据会话，最终为 **0 订阅、0 Frame**。因此：

- 不能计算有效的 frames/s、bytes/s、队列、回调延迟或连接偏斜；
- 不能判定 2C2G 是否足够；
- 不能判定 `2×5,000` 是否比单连接 10,000 稳定；
- 不能把“没有 1013”视为通过，因为根本没有进入订阅和接收阶段；
- 空 JFR 也不能作为性能证据。

该轮应标记为 **INVALID（网络建连失败）**。复测前先做 DNS、TCP 443、TLS、代理/出口和服务端可达性冒烟，并确保至少满足“两条连接均 open、10,000 Token 全部提交、持续收到 Frame”后才开始稳态计时。

## 10. 指标口径

必须同时报告以下四项，避免混淆：

- `frames/s = 窗口内完整 WebSocket 消息回调数 / 窗口秒数`
- `bytes/s = 窗口内收到的原始 payload 字节数 / 窗口秒数`
- `KiB/s = bytes/s / 1024`
- `frames/KiB = 窗口 frames / (窗口 bytes / 1024)`；窗口 bytes 为 0 时记 0

最终 JSON/CSV 中的 `kibibytes = 累计 bytes / 1024`，`framesPerKiB = 累计 frames / kibibytes`，它们是阶段累计密度，**不是速率**。历史日志标签可能写作 `KB/s`、`frames/KB`，但项目定义实际采用 1024，应在汇报中统一写作 `KiB/s`、`frames/KiB`。

另须区分：

- Frame：一次完整 WebSocket 消息回调；
- Event：Frame 内业务事件，一个数组 Frame 可含多个 Event；
- Token：订阅的 asset ID。

当前实现未可靠拆分数组中的全部 Event 数时，`events/s` 应标记“未采集”，不得用 `frames/s` 代替。

## 11. 20 万 Token 生产候选拓扑

**状态：候选设计，尚未验证；不是“已稳定 20 万”。**

基于 5k 两轮有效、10k 两轮均有重连的现状，可继续把 **4,000—5,000 Token/连接** 作为“`initial_dump=false`、以连接稳定与流量接收为目标”的实验候选；但社区 NautilusTrader 分支报告了超过 500 instruments 时初始快照不完整的未文档化现象。该证据不是官方硬限制，也与本项目禁用 initial dump 的场景不完全一致，因此生产评审必须同时加入 **500 Token/连接的保守对照组**，并比较 `observed` 覆盖率、连接数成本和恢复风暴风险：

| 方案 | 节点规格 | 数据连接/节点 | Token/连接 | 数据连接总数 | 正常节点 | N+1 节点 | 当前建议 |
|---|---|---:|---:|---:|---:|---:|---|
| A | 2C2G | 4 | 5,000 | 40 | 10 | 11 | 成本候选；2C2G 有效压测尚未完成 |
| B | 2C4G | 8 | 5,000 | 40 | 5 | 6 | 优先验证，故障域与运维成本较均衡 |
| C | 2C4G | 5 | 4,000 | 50 | 10 | 11 | 对热点与 1013 更保守，但连接/心跳成本更高 |
| D | 待容量测试 | 待测 | 500 | 400 | 待测 | N+1 | 社区未文档化限制的保守对照；连接、握手、心跳与恢复成本显著上升，不能直接作为最终生产方案 |

设计原则：

1. token→worker→connection 使用确定性分片，默认 ROUND_ROBIN，降低资产顺序与热点聚集；后续应增加按历史 bytes/s/frames/s 加权的热点分片。
2. N+1 的备用节点保留接管容量，正常状态下不重复订阅全量 Token。
3. 生产容量按有效高活跃窗口最差值折减，并同时受 frames/s、events/s、bytes/s、网络带宽、连接数与故障接管约束。
4. 单连接或单节点故障时只恢复受影响分片，禁止 20 万 Token 同时重放。
5. 最终上限不能只以“不断线”为判据：至少比较 500、1,000、2,500、5,000 Token/连接的 `submitted` 与 `observed` 覆盖、PONG、异常 close 和恢复时间；低活跃 Token 需用 REST/订单簿抽样对账，避免把无事件误判为订阅失败。

## 12. 快速恢复方案

### 12.1 状态模型

每条逻辑连接维护：

- `desired`：目标 Token 集合；
- `submitted`：本 generation 中已成功进入 OkHttp 出站队列的 Token；
- `observed`：本 generation 中已观察到业务事件的 Token。

`WebSocket.send()==true` 只表示客户端接受入队，不是服务端 ACK。Polymarket Market WSS 当前公开资料没有逐批订阅 ACK，因此恢复完成必须使用组合判据：全部批次已提交、PONG 正常、观察窗口有业务流量，并对低活跃 Token 做抽样 REST/订单簿对账。

### 12.2 恢复状态机

```text
OPEN/HEALTHY
  ├─ 网络失败/PONG 超时/5xx → BACKOFF
  └─ 1013 slow consumer → SLOW_CONSUMER_COOLDOWN
BACKOFF/COOLDOWN
  → 获取节点级握手许可
  → CONNECTING（generation + 1）
  → OPEN（稳定满窗口后再清零失败退避）
  → 获取集群级订阅令牌，分批恢复 desired
  → VERIFYING（PONG + 流量 + 抽样对账）
  → HEALTHY
```

### 12.3 防重连风暴

- 普通故障：指数退避 + full jitter；1013：更长基础冷却。
- 节点级限制并发 TCP/TLS 握手；集群级限制订阅批次/秒。
- 连接建立错峰，订阅批次增加抖动，避免多个连接同相位制造初始快照洪峰。
- generation 隔离旧连接回调；旧代数据不得污染新代状态。
- 同一热点分片重复 1013 时拆细或迁移，而非在原连接快速无限重试。
- 若业务要求订单簿正确性，重连后先用新 `book` snapshot 或 REST snapshot 建立基线，再接增量；否则必须明确断线窗口的 RPO。

建议的候选 SLO（待演练验证）：单连接故障 RTO p95 ≤ 60 秒；单节点故障 95% 分片 ≤ 5 分钟恢复、100% ≤ 10 分钟恢复。

## 13. 尚未完成的实验与风险

### 13.1 必须补齐的实验

1. 单连接 10k 基线至少再重复 3 次，并做 100/500 ms、100/1000 ms、50/500 ms 的单变量降速对照。
2. 固定资产集合与时段，对比 5k、7.5k、10k，减少活跃度偏差。
3. 重做 2C2G `2×5,000`：先通过网络冒烟，再完成不少于 3 轮高活跃有效测试。
4. 增加 500、1,000、2,500、5,000 Token/连接对照，重点验证 `observed` 覆盖率与初始/恢复后的数据完整性；再逐步验证 `4×5,000`、`8×5,000` 的单 Worker 扩展性。只有排除社区所述 500 instruments 快照/覆盖问题后，才进入 40/50 条连接总量验证；否则需评估约 400 条数据连接的成本。
5. 增加 100 ms/1 s 滚动窗口：frames、bytes、book bytes、最大 Frame、回调 max 及时间戳、inter-frame gap、PONG RTT、generation、close history。
6. 同步采集有效 JFR、GC、CPU/RSS、RTT、重传、TCP receive window；必要时抓包确认 Zero Window 与重传。
7. 20 万候选完成至少 3 轮高活跃有效窗口、24 小时 Soak、单连接/单节点接管、服务端异常与出口故障演练。

### 13.2 主要风险

- **服务端未知阈值：** 官方未公开单连接订阅上限、连接配额、slow-consumer 窗口和缓冲大小；社区 NautilusTrader 分支虽报告 500 instruments 的未文档化现象，但仅有文档提交，不能替代本项目实测。
- **热点风险：** Token 活跃度高度不均，平均 5k/连接不能代表最热分片。
- **网络风险：** 本轮 2C2G 已出现 TCP Connect Timeout，说明建连可用性本身是前置门槛。
- **恢复风暴：** 多节点同时重连可造成握手、订阅和初始快照叠加。
- **数据完整性：** 当前内存有界队列的 drop 策略不适合默认承诺“可靠不丢”；需要事件总线或重建机制。
- **指标缺口：** 当前缺少准确 events/s、精细 TCP 指标及回调最长值的发生时间。
- **长期性风险：** 10—15 分钟通过不能替代 24 小时运行和故障演练。

## 14. 管理层结论

- 当前可确认：**单连接 5,000 Token 在两轮 10 分钟实验中表现良好**；它是后续分片方案的合理起点，但尚不是生产认证容量。
- 当前不可确认：单连接 10,000 Token 稳定、2C2G `2×5,000` 稳定、或 20 万 Token 已稳定。尤其 2C2G 新一轮因 TCP Connect Timeout 且 0 订阅/0 Frame，必须作废。
- 1013 的直接原因是服务端报告发送缓冲满；客户端持续队列积压、CPU 饱和和 Close 前 GC 暂停缺乏支持，但网络瞬态、读线程短暂停顿与服务端节点状态仍未知。
- 生产方向应从“提高单连接上限”转为“确定性分片、多节点 N+1、全局限速恢复”。4k—5k/连接仍是当前本项目流量稳定性实验候选，但必须与 500/1,000/2,500 对照并验证 `observed` 覆盖；在社区所述 500 instruments 风险被排除前，不能直接把 5k/连接定为生产值。
- 上线决策门槛应是：高活跃期多轮 PASS、24 小时稳定、无 drop/异常重连、容量余量达标、N+1 接管成功、恢复过程不形成订阅洪峰。在这些门槛完成前，对外和对内均不得表述为“已稳定支撑 20 万”。

## 15. 本地材料索引

- `docs/Polymarket-WSS大规模订阅压测与性能优化方案.md`
- `README.md`
- `pom.xml`
- `src/main/resources/application.yml`
- `reports/benchmark-2026-10-13T13-21-59.868685500Z.json`
- `reports/benchmark-2026-10-13T13-44-55.865167700Z.json`
- `reports/benchmark-2026-10-13T14-02-51.035960100Z.json`
- `reports/benchmark-2026-10-13T14-29-17.731530900Z.json`
- `reports/s1-5000-20261013-211150.stdout.log`
- `reports/s1r2-5000-20261013-213444.stdout.log`
- `reports/s2r1-10000-20261013-214733.stdout.log`
- `reports/s2r2-10000-20261013-run.log`
- `reports/s2r2-10000-20261013-resources.csv`
- `reports/s2r2-10000-20261013-gc.log`
- `reports/mc2x5000-2c2g-gc.log`
- `reports/mc2x5000-2c2g.jfr`
