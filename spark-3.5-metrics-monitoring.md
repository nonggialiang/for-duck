# Spark 3.5.6 Standalone 全 Push 监控方案

> 适用场景：standalone 集群（开启内置 Shuffle Service），metrics 全部经 StatsdSink push 到 otelcol-contrib（statsdreceiver），再经既有 otlphttp exporter 送公司 OTel 后端，不依赖 Prometheus 拉取。
>
> 所有 `spark.metrics.conf.*` 配置直接写在 spark-defaults.conf 中（Spark 会剥掉 `spark.metrics.conf.` 前缀合并进 metrics 配置，且优先级高于 metrics.properties 文件）。

---

## 1. 集群侧配置（Master + 所有 Worker 节点）

文件：`$SPARK_CONF_DIR/spark-defaults.conf`，修改后需**重启 master/worker**。

```properties
# ---------- Shuffle Service 开关 ----------
spark.shuffle.service.enabled=true
spark.shuffle.service.port=7337

# ---------- JVM 指标 source：每进程一份 ----------
# master/worker 实例有 push 通道，采了有意义；
# applications 与 master 同 JVM、shuffleService 与 worker 同 JVM，不重复配
spark.metrics.conf.master.source.jvm.class=org.apache.spark.metrics.source.JvmSource
spark.metrics.conf.worker.source.jvm.class=org.apache.spark.metrics.source.JvmSource

# ---------- 全部走 statsd push（prometheusServlet 不配，每进程一份数据一个通道） ----------
# Master 进程内有两套 MetricsSystem：master + applications
spark.metrics.conf.master.sink.statsd.class=org.apache.spark.metrics.sink.StatsdSink
spark.metrics.conf.master.sink.statsd.host=<statsd-host>
spark.metrics.conf.master.sink.statsd.port=8125
spark.metrics.conf.master.sink.statsd.prefix=spark_master

spark.metrics.conf.applications.sink.statsd.class=org.apache.spark.metrics.sink.StatsdSink
spark.metrics.conf.applications.sink.statsd.host=<statsd-host>
spark.metrics.conf.applications.sink.statsd.port=8125
spark.metrics.conf.applications.sink.statsd.prefix=spark_apps

# Worker 进程内有两套 MetricsSystem：worker + 内嵌 shuffleService
spark.metrics.conf.worker.sink.statsd.class=org.apache.spark.metrics.sink.StatsdSink
spark.metrics.conf.worker.sink.statsd.host=<statsd-host>
spark.metrics.conf.worker.sink.statsd.port=8125
spark.metrics.conf.worker.sink.statsd.prefix=spark_worker

spark.metrics.conf.shuffleService.sink.statsd.class=org.apache.spark.metrics.sink.StatsdSink
spark.metrics.conf.shuffleService.sink.statsd.host=<statsd-host>
spark.metrics.conf.shuffleService.sink.statsd.port=8125
spark.metrics.conf.shuffleService.sink.statsd.prefix=spark_shuffle
```

### 推送内容一览

名字格式：`prefix.<sourceName>.<metric>`。daemon 流全部静态，唯一带变量的是 `spark_apps`。

| prefix | 来源 source | 名字示例 | 变量 pattern |
|---|---|---|---|
| `spark_master` | MasterSource | `spark_master.master.workers`<br>`spark_master.master.aliveWorkers`<br>`spark_master.master.apps`<br>`spark_master.master.waitingApps` | 无 |
| `spark_master` | JvmSource | `spark_master.jvm.memory.heap.used` | 无 |
| `spark_apps` | ApplicationSource（每个运行中 app 一个 source，随起停注册/注销） | `spark_apps.application.<appName>.<ts>.runtime_ms`<br>`spark_apps.application.<appName>.<ts>.cores` | **`spark_apps.application.<appName>.<ts>.<metric>`**<br>appName 为 spark.app.name（用户随意起，**可含点**）；ts 为 source 注册时刻的 13 位毫秒时间戳（**不是 appId**，无法与应用流的 appId join，只能按 appName 关联；同名应用重注册会换新 ts，可当"注册代数"用）。注意 `status` 指标是字符串 gauge，推送为空，**实际可用只有 runtime_ms 和 cores** |
| `spark_worker` | WorkerSource | `spark_worker.worker.executors`<br>`spark_worker.worker.coresUsed` / `coresFree`<br>`spark_worker.worker.memUsed_MB` / `memFree_MB` | 无 |
| `spark_worker` | JvmSource | `spark_worker.jvm.memory.heap.used` | 无 |
| `spark_shuffle` | ExternalShuffleServiceSource（自动注册，无需配 source） | `spark_shuffle.shuffleService.numRegisteredConnections`<br>`spark_shuffle.shuffleService.numRegisteredExecutors`<br>openBlock/fetch 请求时延（`.count`/`.p95` 等 statsd 后缀） | 无 |

---

## 2. 应用侧配置（提交的 Application）

文件：提交侧 spark-defaults.conf 或 `--conf`。建议与集群侧配置物理分开存放。

```properties
# ---------- JVM source ----------
spark.metrics.conf.driver.source.jvm.class=org.apache.spark.metrics.source.JvmSource
spark.metrics.conf.executor.source.jvm.class=org.apache.spark.metrics.source.JvmSource

# ---------- driver + executor 全 push（解决 4040 端口漂移/服务发现问题） ----------
spark.metrics.conf.driver.sink.statsd.class=org.apache.spark.metrics.sink.StatsdSink
spark.metrics.conf.driver.sink.statsd.host=<statsd-host>
spark.metrics.conf.driver.sink.statsd.port=8125
spark.metrics.conf.driver.sink.statsd.prefix=spark

spark.metrics.conf.executor.sink.statsd.class=org.apache.spark.metrics.sink.StatsdSink
spark.metrics.conf.executor.sink.statsd.host=<statsd-host>
spark.metrics.conf.executor.sink.statsd.port=8125
spark.metrics.conf.executor.sink.statsd.prefix=spark

# ---------- 进程树指标（不开则 ProcessTree* 恒为 0；仅 Linux /proc 有效） ----------
spark.executor.processTreeMetrics.enabled=true

# ---------- 可选 ----------
spark.executor.metrics.pollingInterval=10s    # 峰值指标采样密度（默认随心跳 ~10s 一次）
#spark.ui.prometheus.enabled=true             # 仅当还想从 Driver 4040 拉 /metrics/executors/prometheus；纯 push 方案不需要
```

### 推送内容一览

**这条流全部带变量**：driver/executor 实例的 registry 名自动拼 `appId.executorId`（driver 的 executorId 固定为 `driver`），名字格式：`spark.<appId>.<executorId>.<sourceName>.<metric>`。

| 来源 source | 名字示例 | 变量 pattern |
|---|---|---|
| driver：DAGScheduler / BlockManager / LiveListenerBus / CodeGenerator / jvm | `spark.<appId>.driver.DAGScheduler.stage.failedStages`<br>`spark.<appId>.driver.jvm.memory.heap.used` | **`spark.<appId>.driver.<source>.<metric>`**，appId 形如 `app-1762345678901-0000`（含连字符） |
| executor：ExecutorSource | `spark.<appId>.<execId>.executor.threadpool.activeTasks`<br>`spark.<appId>.<execId>.executor.shuffleBytesWritten.count` | **`spark.<appId>.<execId>.executor.<metric>`**，execId 为数字；counter/histogram 带 `.count` / `.p95` 等 statsd 后缀 |
| executor：ExecutorMetricsSource（默认开启，21 项 gauge，随心跳刷新） | `spark.<appId>.<execId>.ExecutorMetrics.JVMHeapMemory`<br>`spark.<appId>.<execId>.ExecutorMetrics.ProcessTreeJVMRSSMemory`<br>`spark.<appId>.<execId>.ExecutorMetrics.MinorGCTime` | 同上 |
| executor：JVMCPUSource / BlockManager shuffle 客户端 | `spark.<appId>.<execId>.JVMCPU.jvmCpuTime`<br>`spark.<appId>.<execId>.ExternalShuffle.remoteBytesRead` | 同上 |

---

## 3. OTel collector：把名字里的变量抽成 label

receiver + transform processor，exporter 沿用现有 `otlphttp` 不动。

```yaml
receivers:
  statsd:
    endpoint: 0.0.0.0:8125
    aggregation_interval: 10s        # 对齐 Spark 的推送周期（默认 60s 太粗）

processors:
  transform/labels:
    error_mode: ignore               # 切不中的名字原样透传，不丢数据
    metric_statements:
      # ---- 应用流：spark.<appId>.<execId>.X → 拆出 appId/executorId 两个 label ----
      - context: datapoint           # label 加在 datapoint 上；必须先于名字重写执行
        statements:
          - set(attributes["spark_app"], extract_patterns(metric.name,
              "^spark\\.(?P<app>[^.]+)\\.(?P<exec>[^.]+)\\.")["app"])
              where IsMatch(metric.name, "^spark\\.[^.]+\\.[^.]+\\.")
          - set(attributes["spark_exec"], extract_patterns(metric.name,
              "^spark\\.(?P<app>[^.]+)\\.(?P<exec>[^.]+)\\.")["exec"])
              where IsMatch(metric.name, "^spark\\.[^.]+\\.[^.]+\\.")
      # ---- spark_apps 流：第三段是注册时间戳不是 appId；
      #      appName 可含点，用"贪婪匹配 + 纯数字段锚定"切分 ----
      - set(attributes["spark_app_name"], extract_patterns(metric.name,
          "^spark_apps\\.application\\.(?P<name>.*)\\.\\d+\\.")["name"])
          where IsMatch(metric.name, "^spark_apps\\.application\\.")
      - set(attributes["spark_reg_ts"], extract_patterns(metric.name,
          "^spark_apps\\.application\\.(?P<name>.*)\\.(?P<ts>\\d+)\\.")["ts"])
          where IsMatch(metric.name, "^spark_apps\\.application\\.")
      - context: metric
        statements:
          # 名字收敛：去掉变量段，指标名稳定
          - replace_all_patterns(metric, "name", "^spark\\.[^.]+\\.[^.]+\\.", "spark.")
          - replace_all_patterns(metric, "name", "^spark_apps\\.application\\..*\\.\\d+\\.", "spark_apps.")
  batch:

service:
  pipelines:
    metrics:
      receivers: [statsd]
      processors: [transform/labels, batch]
      exporters: [otlphttp/company]   # 现有 exporter 原封不动
```

### 转换效果

| 收到的名字 | 转换后 |
|---|---|
| `spark.app-1762345678901-0000.3.executor.threadpool.activeTasks` | `spark.executor.threadpool.activeTasks{spark_app="app-1762345678901-0000", spark_exec="3"}` |
| `spark_apps.application.My.ETL.Job.1762345678901.cores` | `spark_apps.cores{spark_app_name="My.ETL.Job", spark_reg_ts="1762345678901"}` |
| `spark_master.master.workers` | 不变（daemon 流无可拆变量；`spark_` 下划线前缀与 `spark.` 点分前缀天然隔离，正则不会误伤） |

---

## 4. OTel collector：statsd 点分名 → Prometheus 下划线名（适配老 dashboard）

追加到同一 transform 的 metric 段。**顺序必须：拆 label → 后缀对齐 → 压平 → 补前缀**（后缀对齐语句按 dashboard 实际引用的指标按需补全，下面列常用的）。

```yaml
      - context: metric
        statements:
          # 1) 后缀风格对齐（PrometheusServlet 是直接拼接、无下划线：xxx.count → xxxCount）
          - replace_all_patterns(metric, "name", "\\.count$", "Count")        where IsMatch(name, "^spark\\.")
          - replace_all_patterns(metric, "name", "\\.p50$", "50thPercentile") where IsMatch(name, "^spark\\.")
          - replace_all_patterns(metric, "name", "\\.p95$", "95thPercentile") where IsMatch(name, "^spark\\.")
          - replace_all_patterns(metric, "name", "\\.p99$", "99thPercentile") where IsMatch(name, "^spark\\.")
          - replace_all_patterns(metric, "name", "\\.m1_rate$", "OneMinuteRate") where IsMatch(name, "^spark\\.")
          - replace_all_patterns(metric, "name", "\\.mean_rate$", "MeanRate")   where IsMatch(name, "^spark\\.")
          # 2) 压平：[^a-zA-Z0-9] → _，复刻 Spark PrometheusServlet 自己的 normalizeKey 规则
          - replace_all_patterns(metric, "name", "[^a-zA-Z0-9]", "_")          where IsMatch(name, "^spark\\.")
          # 3) 补 metrics_ 前缀
          - replace_all_patterns(metric, "name", "^", "metrics_")              where IsMatch(name, "^spark")
          # 4) 可选：gauge 补 Value 后缀（dashboard 查询带 Value 时加）
          # - set(name, name + "Value") where IsMatch(name, "^metrics_") and not IsMatch(name, "(Count|Percentile|Rate|Max|Mean|Min|StdDev)$")
```

### 两套命名对照

| 维度 | PrometheusServlet（原 pull） | Statsd push（现 push） | 本节 transform 后 |
|---|---|---|---|
| 前缀 | `metrics_` | `spark` 等自定义 prefix | `metrics_` |
| 分隔符 | 非字母数字全部 `_` | `.` 保留 | `_` |
| counter 后缀 | `<name>Count{type="counters"}` | `<name>.count` | `<name>Count` |
| histogram 分位 | `<name>95thPercentile{type="histograms"}` | `<name>.p95` | `<name>95thPercentile` |
| 速率 | `<name>OneMinuteRate{type="timers"}` | `<name>.m1_rate` | `<name>OneMinuteRate` |
| 完整示例 | `metrics_app_1762.._0000_3_executor_threadpool_activeTasks` | `spark.app-1762..-0000.3.executor.threadpool.activeTasks` | `metrics_app_1762__0000_3_executor_threadpool_activeTasks{spark_app=..., spark_exec=...}` |

已知缺口与注意：

- `{type="gauges"/"counters"/"histograms"}` label 重建不了（counter 和 histogram 都发 `.count`，名字上无法区分）；引用了该过滤的面板需手动去掉。
- 先验证公司 OTel 后端在 PromQL 查询边界是否已自动做点→下划线转换；若是，压平一步可省。
- 若 spark_apps / daemon 流也要 `_` 风格，把语句的 `where IsMatch(name, "^spark\\.")` 守卫放宽为 `^spark`。

---

## 5. 推荐的 Grafana dashboard

| Dashboard | 链接 | 用途 |
|---|---|---|
| Apache Spark JMX Metrics Dashboard (23304) | <https://grafana.com/grafana/dashboards/23304-jmx-for-fink-broker> | 首选骨架：driver/executor JVM（CPU/内存/heap/GC/线程/FD）+ 作业统计 |
| Apache Spark - Performance Metrics (7890) | <https://grafana.com/grafana/dashboards/7890-spark-performance-metrics> | 补充：executor 内存/shuffle/吞吐面板，带模板变量 |
| Grafana Cloud 官方 Spark integration | <https://grafana.com/docs/grafana-cloud/observe-and-act/monitor-infrastructure/integrations/integration-reference/integration-spark> | 官方维护，JSON 可直接下载 |
| microsoft/azure-synapse-spark-metrics | <https://github.com/microsoft/azure-synapse-spark-metrics> | 应用总览排版参考（已归档；job/stage 粒度面板 metrics 体系喂不饱） |
| cerndb/spark-dashboard (CERN) | <https://github.com/cerndb/spark-dashboard> | 生产级参考（自定义 sink，取 dashboard JSON 借排版） |

daemon 侧（master/worker/shuffleService）无现成 dashboard，按第 1 节表格中的指标自建（总量不到 15 个 gauge）。

---

## 6. 上线验证清单

1. 重启 master/worker 后检查日志：不得出现 `Sink class ... cannot be instantiated`（sink 类错误是 rethrow 的，会导致 daemon 起不来）；`Source class ... cannot be instantiated` 仅是告警。
2. 提交测试应用，在 OTel 后端确认收到 5 个前缀的数据，且应用流名字带 `appId.driver` / `appId.<execId>`。
3. 确认 `ExecutorMetrics.ProcessTreeJVMRSSMemory` 等 gauge 非 0（为 0 说明 `spark.executor.processTreeMetrics.enabled` 未生效或非 Linux）。
4. 反面清单：不配 `ProcessTreeMetrics` / `StaticSources` 等 source；sink 名不用 `servlet`；prometheusServlet 的 class/path 必须成对；`spark_apps` 流第三段是时间戳不是 appId，别拿它做 join 键。
