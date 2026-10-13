# Polymarket WSS Benchmark

独立的 Java 21 / Spring Boot 客户端基准项目，用于分阶段观察 Polymarket market WebSocket 在不同订阅规模下的客户端接收与处理表现。项目不会依赖或修改 `E:/code/market`。

## IDEA 导入

1. 使用 IntelliJ IDEA 打开本目录或 `pom.xml`。
2. Project SDK 选择 JDK 21，Maven 使用 IDEA 自带版本或 Maven 3.9+。
3. 等待 Maven 下载依赖。

## 资产文件

默认读取 `src/main/resources/assets_ids.txt`。文件不存在、为空或仅包含注释时，应用会明确失败。支持三种格式：

```text
# 每行一个
REAL_ID_1
REAL_ID_2
```

```text
REAL_ID_1,REAL_ID_2
```

```json
["REAL_ID_1", "REAL_ID_2"]
```

仓库示例文件只有注释，不包含虚构 ID。运行前必须换成真实 ID。也可通过 `--benchmark.assets-file=file:E:/path/assets.txt` 指定外部文件。

## 配置与运行

主要配置位于 `src/main/resources/application.yml`：阶段订阅数量、阶段时长、批次大小与间隔、连接数、ROUND_ROBIN/CONTIGUOUS 分片、队列容量、消费者数、模拟延迟、自动重连、最大运行时长、level 和 PING 间隔。

```bash
mvn clean package
java -jar target/polymarket-wss-benchmark-1.0.0.jar
```

命令行覆盖示例：

```bash
java -jar target/polymarket-wss-benchmark-1.0.0.jar --benchmark.phase-subscription-counts=100,500,1000 --benchmark.phase-duration=10m --benchmark.connections=2
```

应用启动即连接公网并执行实验；在受控环境运行。本项目的构建和单元测试不会建立公网连接。

只读端点：

- `GET /api/status`
- `GET /api/report`
- `GET /actuator/health`
- `GET /actuator/prometheus`

每 10 秒日志输出每连接速率、帧大小、book 流量、事件计数、队列与 onMessage 延迟。运行结束在 `reports/` 写入按阶段汇总的 JSON 和 CSV。

## 实验方法

1. 固定机器、JVM 参数、网络、资产集合、level、连接数和阶段持续时间。
2. 预热后按 p1/p2/p3 逐步增加订阅；多次重复并比较同阶段分位数，而非只看单次峰值。
3. 同时观察服务端 close/failure、PING/PONG、新消息速率、客户端 CPU/GC、队列使用率和 dropped。
4. 一次实验只能说明该客户端、网络和当时服务状态下的观测结果，不能据此证明服务端存在某个硬订阅上限。服务端限流、市场活跃度和网络波动都可能造成相似现象。

## 如何判定 slow consumer

以下信号持续同时出现时，才应怀疑客户端消费能力不足：

- 队列深度/使用率持续增长且不能回落；
- `dropped` 持续增加；
- `enqueued - processed` 差值扩大；
- onMessage p95/p99 或 OkHttp `queueSize()` 异常上升；
- PONG 明显变旧，随后出现超时、close 或 failure；
- 增加消费者或降低模拟处理延迟后，上述现象显著缓解。

`book` 帧在 onMessage 中仅统计后直接丢弃；非 book 使用 `ArrayBlockingQueue.offer()`，队列满时丢弃，永不阻塞 OkHttp 回调线程。为避免高流量下完整反序列化，每帧仅字符串扫描 `event_type`；完整 JSON 只用于低频订阅发送与报告。
