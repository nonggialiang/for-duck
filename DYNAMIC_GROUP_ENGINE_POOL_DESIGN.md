# 动态 Group Engine Pool 设计文档

> **状态**: Draft（v3，决策已闭环）
> **分支**: kyuubi-1.11
> **适用场景**: Kyuubi + Spark Standalone 集群
> **目标 ShareLevel**: GROUP

---

## 1. 需求背景

### 1.1 业务场景

在 Kyuubi + Spark Standalone 集群架构下，存在多个业务团队（group），负载在时间维度上高度互补：

- `data-science`：白天高峰、夜间空闲
- `bi-reporting`：全天平稳
- `ad-hoc-query`：突发性大

但 Spark Standalone 集群的总资源（CPU cores、memory）是**固定的物理池**，无法像 Yarn/K8s 那样弹性伸缩底层资源。需要在固定总资源下，按 group 实时负载在 group 间动态再分配 engine。

### 1.2 现状与痛点

Kyuubi 在 `ShareLevel=GROUP` 下，通过 `kyuubi.engine.pool.size` 为每个 group 配置**静态**的 engine pool：

- **资源利用率低**：高峰 group 资源不足（用户排队/超时），低谷 group 资源闲置（engine 空转占用 executor）
- **运维成本高**：需人工根据业务峰谷调整配置、滚动重启 Kyuubi Server
- **缺乏公平性**：无法根据全局负载做资源再分配，容易出现"某些 group 饿死、某些 group 浪费"
- **响应迟缓**：配置变更生效慢，无法应对突发流量

### 1.3 目标

在 `ShareLevel=GROUP` 下，根据每个 group 的**实时负载**（并发 Session 数 + Engine 内 Query 并发/队列），动态调整其 engine pool size，实现：

1. **跨 group 资源共享**：固定总资源池下，按需在 group 间再分配
2. **自动弹性**：无需人工干预，跟随负载变化调整 engine 数量
3. **资源利用率最大化**：减少空闲 engine 占用，缓解高峰排队
4. **平滑可控**：扩缩容过程对用户透明，避免中断正在执行的查询（除非业务允许强制 kill）

### 1.4 非目标（Out of Scope）

- 不改动单个 engine **内部资源规格**（`--executor-cores`/`--executor-memory` 保持固定）
- 不替换 Spark Standalone 调度器
- 不实现跨集群（federation）调度
- 不改变 `ShareLevel` 的语义与路由机制

---

## 2. 现状分析（基于代码调研）

### 2.1 Engine Pool 现状

**核心结论：Kyuubi 1.11 中不存在独立的 `EnginePool` 类，pool 是逻辑概念。**

| 组件 | 文件 | 关键行 |
|------|------|--------|
| Engine 描述符 | `kyuubi-server/.../engine/EngineRef.scala` | L114-138 (subdomain 计算) |
| Session 持有 | `kyuubi-server/.../session/KyuubiSessionImpl.scala` | 调用 `engine.getOrCreate()` |
| 进程构建 | `kyuubi-server/.../engine/ProcBuilder.scala` | 启动 engine 进程 |
| 应用管理 | `kyuubi-server/.../engine/KyuubiApplicationManager.scala` | Yarn/K8s 操作 |

**Pool size 关键逻辑**（`EngineRef.scala:114-138`）：

```scala
private[kyuubi] val subdomain: String = conf.get(ENGINE_SHARE_LEVEL_SUBDOMAIN) match {
  case subdomain if clientPoolSize > 0 && (subdomain.isEmpty || enginePoolIgnoreSubdomain) =>
    val poolSize = math.min(clientPoolSize, poolThreshold)
    val seqNum = enginePoolSelectPolicy match {
      case "POLLING" => /* ZK 分布式计数器 */
      case "RANDOM"  => Random.nextInt(poolSize)
    }
    s"$clientPoolName-${seqNum % poolSize}"
  case Some(_subdomain) => _subdomain
  case _ => "default"
}
```

**关键限制：**
- `poolSize` 在 session 建立时由**静态配置**计算，运行时不可变
- subdomain 一旦确定，该 session 期间不变
- 不存在"pool 元数据"概念，pool 中的 engine 集合是 ZK 路径下节点的隐式集合

### 2.2 GROUP ShareLevel 现状

- `routingUser` = `groupProvider.primaryGroup(sessionUser, conf)`（`EngineRef.scala:105-109`）
- ZK 路径：`/{serverSpace}_{version}_GROUP_{engineType}/{primaryGroup}/{subdomain}`
- 默认 `HadoopGroupProvider` 通过 Hadoop GroupsMapping 获取 primary group
- `GroupProvider` 插件机制已就绪（`KyuubiSessionManager.scala:63` 的 `groupProvider` lazy val）

### 2.3 现有指标体系

| 指标 | 含义 | 是否按 group 聚合 |
|------|------|------------------|
| `kyuubi.connection.opened` | 活跃 connection 数 | 否（按 user） |
| `kyuubi.exec.pool.threads.alive` | engine 执行线程池活跃数 | engine 端，不回传 server |
| `kyuubi.exec.pool.work_queue.size` | engine 工作队列大小 | engine 端，不回传 server |
| `kyuubi.engine.startup.permit.*` | engine 启动并发许可 | 全局，不按 group |

**关键缺口：**
- **没有 group 维度的负载聚合**
- engine 端的执行池指标**不回传 server**，server 无法感知 engine 内部 query 并发

**可复用的现有模式**（重要）：metrics 按 user 维度的拆分已成熟，通过 `MetricRegistry.name(base, user)` 拼接实现（见 `KyuubiSession.scala:60-72`）。新增 group 维度可直接沿用：`MetricRegistry.name(base, group)`。

### 2.4 现有生命周期机制（可复用）

| 机制 | 配置 | 位置 |
|------|------|------|
| Engine idle timeout | `kyuubi.session.engine.idle.timeout` (默认 30min) | `SessionManager.scala:340-367` |
| Spark max lifetime | `kyuubi.session.engine.spark.max.lifetime` | `SparkSQLEngine.scala:165-214` |
| 分布式锁 | `EngineRef.tryWithLock` | `EngineRef.scala:182-203` |
| Admin REST: 列 engine | `GET /api/v1/admin/engine` | `AdminResource.scala:337-379` |
| Admin REST: 删 engine | `DELETE /api/v1/admin/engine` | `AdminResource.scala:280-329` |
| Engine 注册/注销 | `register()` / `deregister()` | `EngineRef.scala` |
| 启动并发限制 | `kyuubi.server.limit.engine.startup` | `KyuubiSessionManager.scala:497-502` |

---

## 3. 可行性分析

### 3.1 总体结论：**可行，且采用最小化改动方案**

本设计的核心简化在于：**保留现有 `seqNum % poolSize` 的 subdomain 编号方案，仅将 poolSize 从静态配置升级为存储在 ZK 的动态共享状态。** 这样既获得了动态扩缩容能力，又最大限度地复用现有代码与语义。

| 能力需求 | 现状 | 可行性 | 说明 |
|---------|------|--------|------|
| 获取 group 并发 session 数 | 部分 | 易 | server 端已有 session 列表，按 primaryGroup 聚合即可 |
| 获取 engine 内 query 并发/队列 | 缺失 | 中 | 采用 ZK 心跳方案，engine 定期写 ephemeral 节点 |
| 动态调整 poolSize | 不支持 | 易 | 将 poolSize 存入 ZK 持久节点，EngineRef 读取动态值替代静态配置 |
| 缩容时优雅排空 | 部分 | 易 | 缩容目标明确为 `[newSize, oldSize)` 区间的 engine |
| 跨 Server 协调 | 已有基础 | 易 | 复用 ZK 分布式锁与持久节点 |
| Spark Standalone 资源约束 | N/A | 易 | engine 启动失败即天然约束（资源不足时申请不到 executor） |
| group 维度 metrics | 模式成熟 | 易 | 沿用 `MetricRegistry.name(base, group)` 拼接 |

### 3.2 关键技术风险

1. **engine 内指标回传通道缺失**
   - **解决方案**：engine 启动后在 `/load/{group}/{subdomain}` 创建 ephemeral 节点，定期 `setData` 写入负载 JSON；server 端 `EnginePoolManager` 定期 list 该路径获取全量。ephemeral 节点绑定 engine 的 ZK session，engine 退出后自动消失，兼做存活检测。
   - **风险点**：ZK 写频率受限于 ZK 集群性能，心跳间隔需权衡（默认 10s）。engine 数量 >200 时需压测。

2. **抖动（thrashing）风险**
   - 负载波动可能导致频繁扩缩容，engine 启动/销毁开销大（Spark application 启动数十秒）
   - **解决思路**：冷却时间（cooldown）、滞后阈值（hysteresis）、最小存活时间（min lifetime before shrink）

3. **资源争用的公平性**
   - Spark Standalone 总资源固定，扩容 group A 可能导致 group B 的 engine 无法获得 executor
   - **解决方案**：保底 + 抢占模式（详见 §5.5）

4. **poolSize 切换瞬间的一致性**
   - 缩容将 ZK 中 size 从 M 改为 N 后，个别 in-flight 请求可能仍按旧值 M 计算并路由到 `[N, M)` 的 draining engine
   - **解决思路**：draining engine 自身拒绝新 session 建连（thrift openSession 时检查状态），保证最终一致；这是软约束，不要求强一致

---

## 4. 功能范围

### 4.1 In Scope（第一期）

| 编号 | 功能 | 优先级 |
|------|------|--------|
| F1 | group 维度负载指标采集与聚合 | P0 |
| F2 | engine 内 query 并发/队列指标上报（ZK 心跳） | P0 |
| F3 | 动态 poolSize 共享状态管理（ZK 持久节点） | P0 |
| F4 | 扩容决策与执行（提高 size + 预创建 engine） | P0 |
| F5 | 缩容决策与执行（降低 size + 排空超范围 engine） | P0 |
| F6 | 扩缩容调度器（定时/事件触发 + 分布式锁） | P0 |
| F7 | **全局资源预算与 group 配额（保底 + 抢占）** | P0 |
| F8 | 扩缩容相关 Metrics（按 group 维度）与 Admin REST API | P0 |
| F9 | 防抖动（cooldown、hysteresis） | P1 |

### 4.2 Out of Scope（后续迭代）

- 单 engine 资源规格动态调整（`--executor-cores` 等运行时变化）
- 基于 CPU/内存利用率的负载指标（需额外探针）
- 跨 Kyuubi 集群的 federation 调度

---

## 5. 设计方案

### 5.1 总体架构

```
                         ┌─────────────────────────────────────┐
                         │     Kyuubi Server (多实例分布式锁)   │
                         │                                     │
   新 Session ──────────►│  ┌──────────────────────────┐       │
                         │  │   KyuubiSessionManager    │       │
                         │  │   - session 列表          │       │
                         │  │   - group → 负载聚合      │       │
                         │  └──────────┬───────────────┘       │
                         │             │                       │
                         │  ┌──────────▼───────────────┐       │
                         │  │  EnginePoolManager (新)   │       │
                         │  │  - 读: currentPoolSize    │       │
                         │  │  - 写: setPoolSize        │       │
                         │  │  - 保底+抢占决策          │       │
                         │  │  - 缩容排空               │       │
                         │  └──────────┬───────────────┘       │
                         │             │ ZK 协调                │
                         └─────────────┼───────────────────────┘
                                       │
                         ┌─────────────▼────────────────────┐
                         │   ZooKeeper / Etcd                │
                         │   /poolMeta/{group}               │
                         │     {size, min, max, gen}         │
                         │   /load/{group}/{subdomain}       │
                         │     {concurrency, queue, ...}     │
                         │   + 分布式锁 + engine 注册节点     │
                         └─────────────┬────────────────────┘
                                       │ 发现 + 心跳
              ┌────────────────────────┼────────────────────────┐
              │                        │                        │
       ┌──────▼──────┐          ┌──────▼──────┐          ┌──────▼──────┐
       │engine-pool-0│         │engine-pool-1│          │engine-pool-2│
       │ (group=A)   │          │ (group=A,   │          │ (group=B)   │
       │             │          │  draining)  │          │             │
       │ 心跳:并发/队列 │        │ 心跳: ...    │          │ 心跳: ...    │
       └─────────────┘          └─────────────┘          └─────────────┘
              │                        │                        │
              └────────────────────────┴────────────────────────┘
                                       │
                              ┌────────▼────────┐
                              │ Spark Standalone │
                              │   Master (固定)  │
                              └─────────────────┘
```

### 5.2 核心新增组件

#### 5.2.1 `EnginePoolManager`（新）

**职责**：管理每个 group 的 poolSize 动态状态，执行扩缩容决策与排空。

**位置**：`kyuubi-server/src/main/scala/org/apache/kyuubi/engine/pool/EnginePoolManager.scala`（新增包）

**核心接口**：

```scala
trait EnginePoolManager {
  def initialize(conf: KyuubiConf): Unit
  def start(): Unit
  def stop(): Unit

  /** 读取指定 group 的当前 poolSize（供 EngineRef 计算 subdomain） */
  def getCurrentPoolSize(group: String, engineType: String): Int

  /** 扩缩容：将 poolSize 设为 newSize，返回变更前后状态 */
  def setPoolSize(group: String, engineType: String, newSize: Int): PoolSizeChange

  /** 标记某 subdomain 为 draining（缩容时使用） */
  def markDraining(group: String, engineType: String, subdomain: String): Unit

  /** 判断某 subdomain 是否处于 draining（engine 端 openSession 时校验） */
  def isDraining(group: String, engineType: String, subdomain: String): Boolean

  /** 获取 group 当前负载快照（聚合 session 数 + engine 心报） */
  def getGroupLoad(group: String): GroupLoad
}

case class PoolSizeChange(
    group: String,
    oldSize: Int,
    newSize: Int,
    drainingSubdomains: Seq[String])  // 缩容时 = [newSize, oldSize) 编号；扩容时为空

case class GroupLoad(
    group: String,
    activeSessions: Int,
    pendingSessions: Int,
    engines: Seq[EngineLoad]) {
  def totalConcurrency: Int = engines.map(_.currentConcurrency).sum
  def totalCapacity: Int = engines.map(_.maxConcurrency).sum
  def utilization: Double = totalConcurrency.toDouble / math.max(totalCapacity, 1)
}
case class EngineLoad(
    subdomain: String,
    currentConcurrency: Int,
    queueSize: Int,
    maxConcurrency: Int,
    draining: Boolean,
    lastHeartbeatMs: Long)
```

#### 5.2.2 Engine 负载上报（ZK 心跳方案）

**职责**：engine 定期向 ZK 写入自身负载，server 端聚合读取。

**数据结构**：

ephemeral 节点路径（绑定 engine 的 ZK session，engine 退出后自动删除，兼做存活检测）：
```
/{serverSpace}_{version}_GROUP_{engineType}_load/{group}/{subdomain}
```

节点内容（JSON，通过 `setData` 定期更新）：
```json
{
  "currentConcurrency": 7,
  "queueSize": 2,
  "maxConcurrency": 10,
  "timestamp": 1700000000000
}
```

**engine 端**：`EngineLoadReporter`（新，engine 端组件）
- 启动时在对应路径创建 ephemeral 节点
- 每隔 `load.reportInterval`（默认 10s）`setData` 更新负载
- 采集来源：engine 端 `SessionManager.getActiveUserSessionCount` + 执行线程池指标

**server 端**：`EnginePoolManager.getGroupLoad(group)`
- `getChildren(/load/{group})` 一次性获取该 group 所有 engine 的 subdomain 列表
- 批量 `getData` 读取每个节点的负载 JSON
- 结合 server 端 session 列表（按 primaryGroup 聚合 activeSessions/pendingSessions）合成 `GroupLoad`

**心跳超时处理**：
- 若 `lastHeartbeatMs` 距今 > `heartbeatTimeout`（默认 3 倍 reportInterval = 30s），视为 engine 失联
- 失联 engine 的负载降级处理：currentConcurrency 保守按 maxConcurrency 计（高估，避免误缩容）
- 同时触发 engine alive checker（已有机制，`KyuubiSessionManager.scala:472-495`）确认是否真的下线

#### 5.2.3 `EnginePoolScaler`（新）

**职责**：根据 group 负载、配额与全局资源，输出扩缩容动作（含跨 group 抢占）。

```scala
case class ScaleDecision(actions: Seq[ScaleAction])
sealed trait ScaleAction
case class ScaleUp(group: String, targetSize: Int) extends ScaleAction
case class ScaleDown(group: String, targetSize: Int) extends ScaleAction  // 触发排空 [targetSize, currentSize)
case object NoOp extends ScaleAction

trait ScalePolicy {
  def evaluate(
      group: String,
      load: GroupLoad,
      quota: GroupQuota,
      currentSize: Int,
      globalBudget: GlobalResourceBudget,
      allGroups: Map[String, GroupLoad]): ScaleDecision
}
```

**默认策略：基于利用率的滞后阈值**

```
扩容触发: utilization > scaleUpThreshold (默认 0.8) 持续 scaleUpWindow (默认 60s)
  目标 size = currentSize + ceil((totalConcurrency - totalCapacity) / singleEngineCapacity)
  受 quota.maxEngines 与全局资源约束
缩容触发: utilization < scaleDownThreshold (默认 0.2) 持续 scaleDownWindow (默认 300s)
  目标 size = max(quota.minEngines, ceil(totalConcurrency / singleEngineCapacity))
防抖动: 相邻两次缩容间隔 >= cooldownAfterScale (默认 600s)
```

### 5.3 动态 poolSize 管理（核心设计）

**核心思路**：保留现有 `subdomain = engine-pool-{seqNum % poolSize}` 的命名与路由方案，仅将 `poolSize` 从**静态配置**升级为**存储在 ZK 的动态共享状态**。

#### 5.3.1 ZK 状态结构

持久节点路径：
```
/{serverSpace}_{version}_GROUP_{engineType}_poolMeta/{group}
```

节点内容（JSON）：
```json
{
  "size": 3,
  "minSize": 1,
  "maxSize": 10,
  "generation": 7,
  "updatedAt": 1700000000000
}
```

- `size`：当前生效的 poolSize
- `generation`：单调递增的版本号，用于检测并发修改（乐观锁）
- `minSize`/`maxSize`：配额约束（见 §5.5）

#### 5.3.2 subdomain 计算变更（`EngineRef.scala:114-138`）

```scala
private[kyuubi] val subdomain: String = conf.get(ENGINE_SHARE_LEVEL_SUBDOMAIN) match {
  case subdomain if clientPoolSize > 0 && (subdomain.isEmpty || enginePoolIgnoreSubdomain) =>
    if (enginePoolManager.dynamicPoolEnabled) {
      // 动态模式：从 ZK 读取当前 poolSize
      val currentSize = enginePoolManager.getCurrentPoolSize(routingGroup, engineType)
      val seqNum = enginePoolSelectPolicy match {
        case "POLLING" => /* 复用现有 ZK 分布式计数器，但用 currentSize */
        case "RANDOM"  => Random.nextInt(currentSize)
      }
      s"$clientPoolName-${seqNum % currentSize}"
    } else {
      // 静态模式：完全保留原逻辑
      val poolSize = math.min(clientPoolSize, poolThreshold)
      s"$clientPoolName-${seqNum % poolSize}"
    }
  case Some(_subdomain) => _subdomain
  case _ => "default"
}
```

**关键性质**：
- subdomain 始终落在 `[0, currentSize)` 区间
- 编号语义与静态模式一致（`engine-pool-0`、`engine-pool-1`...）
- `dynamic.enabled=false` 时完全回退到静态逻辑，零行为变化

#### 5.3.3 扩容执行

```
1. Scaler 决定将 size 从 N 提到 M (M > N)
2. 获取 group 级分布式锁:
   /{serverSpace}_{version}_GROUP_{engineType}_scaleLock/{group}
3. （可选）预创建 [N, M) 的 engine：
   - 对 i ∈ [N, M)，主动构造 EngineRef 并调用 create()
   - 预创建减少首个路由到新编号 session 的等待延迟
   - 可配置 preCreateOnScaleUp=true/false
4. 更新 ZK poolMeta：size = M, generation += 1
5. 后续新 session 的 seqNum % M 自然分布到 [0, M)
```

**懒创建兜底**：即使不做预创建，当某 session 的 `seqNum % M` 命中尚未存在的编号时，`EngineRef.getOrCreate()` 的现有逻辑会触发创建，最终达到 M 个 engine。

#### 5.3.4 缩容执行（核心简化点）

```
1. Scaler 决定将 size 从 M 降到 N (N < M)
2. 获取 group 级分布式锁
3. 标记 [N, M) 的 engine 为 draining：
   - 将 poolMeta.drainingRange = [N, M) 写入元数据（或独立 ZK 状态节点）
4. 更新 ZK poolMeta：size = N, generation += 1
5. 新 session 的 seqNum % N 只落在 [0, N)，不会命中 draining engine
   → [0, N) 内的 engine 保持稳定，继续承接新流量
6. 排空 [N, M) 的 draining engine：
   - 等待其 currentConcurrency == 0（通过负载上报观察）
   - 排空后 kill（复用 KyuubiApplicationManager.killApplicationByTag）
   - 删除对应 ZK 注册节点与 draining 标记
7. 若排空超过 quota.drainTimeout：
   - quota.forceKillAfterDrain=true 时强制 kill 正在执行的查询
   - 否则告警，保留 draining engine 继续等待
```

**简化收益**：
- **缩容目标明确**：就是编号 `[N, M)` 的 engine，无需复杂选择算法
- **保留的 engine 稳定**：`[0, N)` 的 engine 完全不受影响，session 不迁移
- **向后兼容**：命名规则不变，Admin REST、metrics 不需大改

### 5.4 缩容时的优雅排空细节

**复用**：`SparkSQLEngine.scala:165-214` 的 `startLifetimeTerminatingChecker` 模式。

**draining engine 拒绝新连接**（保证一致性的兜底）：

即便个别 in-flight 请求在 size 切换瞬间按旧值 M 路由到 `[N, M)` 的 draining engine，该 engine 在 thrift `OpenSession` 处理时检查自身 draining 状态，拒绝建连并返回错误，触发客户端重试到正确编号。此为软约束，避免强一致带来的复杂锁。

**排空进度观测**：
- `EnginePoolManager` 维护每个 draining engine 的排空计时
- metrics 暴露（按 group 维度，见 §5.7）

### 5.5 全局资源预算与配额（保底 + 抢占）

#### 5.5.1 数据模型

```scala
case class GlobalResourceBudget(
    totalCores: Int,
    totalMemoryMB: Long)

case class GroupQuota(
    group: String,
    minEngines: Int,              // 保底（不可被抢占的最低 engine 数）
    maxEngines: Int,              // 上限（硬隔离）
    weight: Double = 1.0,         // 抢占优先级，越大越不易被抢占
    singleEngineCores: Int = 4,
    singleEngineMemoryMB: Long = 4096,
    drainTimeout: Long = 1800_000L,        // 排空超时（group 级配置）
    forceKillAfterDrain: Boolean = false)  // 超时后是否强制 kill（group 级开关）
```

#### 5.5.2 启动校验

所有 group 的 `minEngines * (singleEngineCores, singleEngineMemoryMB)` 之和 ≤ 全局总资源（`GlobalResourceBudget`）。校验失败则 Kyuubi Server 拒绝启动并报错。

#### 5.5.3 扩容准入规则

当某 group G 需要扩容到 targetSize 时：

```
1. 若 targetSize <= quota.maxEngines 且 全局剩余资源充足：
   → 直接扩容
2. 若 targetSize > quota.maxEngines：
   → 拒绝，保持在 maxEngines（硬上限）
3. 若未超 max 但全局剩余资源不足：
   → 触发抢占流程（见 5.5.4）
```

#### 5.5.4 抢占流程（保底 + 抢占模式）

当全局资源不足，group G 需要扩容时：

```
1. 扫描所有 group，找出可被抢占的候选：
   候选条件: utilization < starveThreshold (默认 0.3)
            且 currentSize > quota.minEngines（保底不可动）
2. 按 weight 升序排序（低权重先被抢占）
3. 对候选依次触发缩容，直到释放的资源满足 G 的扩容需求：
   ScaleDown(候选 group, max(quota.minEngines, 估算目标))
4. 资源释放后（draining engine 排空完成），执行 G 的 ScaleUp
5. 若所有候选缩容到 minEngines 仍不足：
   → G 的扩容请求进入等待队列，等待新资源释放（如其他 group 主动缩容）
```

**关键约束**：
- 保底线：任何 group 的 engine 数不会被抢占到低于 `quota.minEngines`
- 抢占是"缩容触发型"：被抢占 group 进入缩容流程，按 §5.3.4 / §5.4 排空，不会主动 kill 正在执行的查询（除非该 group 的 `forceKillAfterDrain=true` 且超时）

#### 5.5.5 配置示例

```properties
# 全局预算
kyuubi.engine.pool.dynamic.global.total.cores=200
kyuubi.engine.pool.dynamic.global.total.memoryMB=819200

# 默认配额（所有 group 共用）
kyuubi.engine.pool.dynamic.group.minEngines=1
kyuubi.engine.pool.dynamic.group.maxEngines=10
kyuubi.engine.pool.dynamic.group.weight=1.0
kyuubi.engine.pool.dynamic.group.drainTimeout=1800s
kyuubi.engine.pool.dynamic.group.forceKillAfterDrain=false

# per-group 覆盖
kyuubi.engine.pool.dynamic.group.quota.data-science.minEngines=2
kyuubi.engine.pool.dynamic.group.quota.data-science.maxEngines=15
kyuubi.engine.pool.dynamic.group.quota.data-science.weight=2.0
kyuubi.engine.pool.dynamic.group.quota.data-science.forceKillAfterDrain=true
```

### 5.6 扩缩容调度器与跨 Server 协调

**触发方式**：
- **定时触发**（默认）：`evaluationInterval`（默认 30s）扫描所有 group
- **事件触发**（可选）：session open/close 时立即评估对应 group（带去抖）

**跨 Server 协调（分布式锁方案）**：
- 每个 Kyuubi Server 实例都运行 scaler
- 执行扩缩容动作（setPoolSize、markDraining）前**必须获取 group 级分布式锁**
- 锁路径：`/{serverSpace}_{version}_GROUP_{engineType}_scaleLock/{group}`
- 复用 `DiscoveryClient.tryWithLock`
- 锁粒度为 group，不同 group 的扩缩容可并行，不同 server 对同一 group 串行

**poolMeta 写入的并发保护**：
- 所有 setPoolSize 使用 generation 乐观锁（CAS 语义）：读当前 generation → 计算新值 → 写入时校验 generation 未变
- 若 CAS 失败，重读重试（最多 N 次）

### 5.7 与 Spark Dynamic Allocation 的关系

engine 内部允许开启 `spark.dynamicAllocation.enabled`，两者正交、可叠加：

| 层级 | 机制 | 适用场景 |
|------|------|----------|
| engine 内部 | Spark DA：executor 数随 query 并行度弹性 | 单个 query 内部并行度波动 |
| engine 池层 | 本设计：engine 数随 group 负载弹性 | group 级吞吐、多租户公平 |

**协同注意点**：
- engine 开启 DA 后，engine 的"容量"（maxConcurrency）不再等同于 executor 数，应以 **engine 端 operation 队列阈值**（`spark.sql.concurrent.streamingQueries` 等或 `kyuubi.operation.concurrent.queries`）为准
- `EngineLoadReporter` 上报的 `maxConcurrency` 应反映 engine 配置的并发上限，而非 executor 数
- 扩容决策仍以 group 维度的 session 并发 + operation 队列为信号，与 DA 解耦

### 5.8 Metrics 暴露（按 group 维度）

沿用现有 `MetricRegistry.name(base, group)` 拼接模式（见 `KyuubiSession.scala:60-72` 的 user 维度先例）。Prometheus 默认启用。

**新增 metrics**：

| Metric 名（base + `.` + group） | 类型 | 说明 |
|--------------------------------|------|------|
| `kyuubi.pool.group.{group}.size` | Gauge | 当前 poolSize |
| `kyuubi.pool.group.{group}.active_engines` | Gauge | 活跃 engine 数（排除 draining） |
| `kyuubi.pool.group.{group}.draining_engines` | Gauge | 排空中 engine 数 |
| `kyuubi.pool.group.{group}.active_sessions` | Gauge | 该 group 活跃 session 数 |
| `kyuubi.pool.group.{group}.total_concurrency` | Gauge | engine 当前总并发 |
| `kyuubi.pool.group.{group}.total_capacity` | Gauge | engine 总容量 |
| `kyuubi.pool.group.{group}.utilization` | Gauge | 利用率 |
| `kyuubi.pool.group.{group}.scale_up_total` | Counter | 扩容次数 |
| `kyuubi.pool.group.{group}.scale_down_total` | Counter | 缩容次数 |
| `kyuubi.pool.group.{group}.preempted_total` | Counter | 被抢占缩容次数 |
| `kyuubi.pool.scale.decision.latency` | Timer | 决策耗时（全局） |

**注意**：metric name 拼接会产生 `kyuubi_pool_group_data_science_size` 这类 Prometheus metric。若需要标准 label 形式（`{group="data-science"}`），可在 Prometheus scrape 端用 `metric_relabel_configs` 做名称解析，或后续单独改造 `PrometheusReporterService`（不在第一期范围）。

---

## 6. 配置项设计

所有新增配置集中在 `KyuubiConf.scala`，前缀 `kyuubi.engine.pool.dynamic`。

### 6.1 总开关

| Key | 默认 | 说明 |
|-----|------|------|
| `kyuubi.engine.pool.dynamic.enabled` | `false` | 是否启用动态 pool（关闭则完全回退现有静态逻辑） |

### 6.2 指标与上报

| Key | 默认 | 说明 |
|-----|------|------|
| `kyuubi.engine.pool.dynamic.load.reportInterval` | `10s` | engine 向 ZK 心跳间隔 |
| `kyuubi.engine.pool.dynamic.load.heartbeatTimeout` | `30s` | 心跳超时阈值（3 倍间隔），超时视为失联 |

### 6.3 扩缩容策略

| Key | 默认 | 说明 |
|-----|------|------|
| `kyuubi.engine.pool.dynamic.scale.policy` | `utilization` | 决策策略类 |
| `kyuubi.engine.pool.dynamic.scale.upThreshold` | `0.8` | 扩容利用率阈值 |
| `kyuubi.engine.pool.dynamic.scale.downThreshold` | `0.2` | 缩容利用率阈值 |
| `kyuubi.engine.pool.dynamic.scale.upWindow` | `60s` | 持续超阈值多久才扩容 |
| `kyuubi.engine.pool.dynamic.scale.downWindow` | `300s` | 持续低于阈值多久才缩容 |
| `kyuubi.engine.pool.dynamic.scale.cooldown` | `600s` | 同 group 相邻缩容最小间隔 |
| `kyuubi.engine.pool.dynamic.scale.evalInterval` | `30s` | 调度器扫描间隔 |
| `kyuubi.engine.pool.dynamic.scale.preCreateOnScaleUp` | `true` | 扩容时是否预创建新编号 engine |
| `kyuubi.engine.pool.dynamic.scale.minLifetimeBeforeShrink` | `600s` | engine 最小存活时间 |
| `kyuubi.engine.pool.dynamic.scale.starveThreshold` | `0.3` | group 被视为"可被抢占"的利用率上限 |

### 6.4 配额与资源（group 级，支持 per-group 覆盖）

| Key | 默认 | 说明 |
|-----|------|------|
| `kyuubi.engine.pool.dynamic.global.total.cores` | - | 全局总 cores 预算（用于校验） |
| `kyuubi.engine.pool.dynamic.global.total.memoryMB` | - | 全局总内存预算 |
| `kyuubi.engine.pool.dynamic.group.minEngines` | `1` | group 保底 engine 数 |
| `kyuubi.engine.pool.dynamic.group.maxEngines` | `10` | group 上限 |
| `kyuubi.engine.pool.dynamic.group.weight` | `1.0` | 抢占权重 |
| `kyuubi.engine.pool.dynamic.group.singleEngineCores` | `4` | 单 engine 占用 cores（预算计算用） |
| `kyuubi.engine.pool.dynamic.group.singleEngineMemoryMB` | `4096` | 单 engine 占用内存 |
| `kyuubi.engine.pool.dynamic.group.drainTimeout` | `1800s` | 排空超时（group 级） |
| `kyuubi.engine.pool.dynamic.group.forceKillAfterDrain` | `false` | 排空超时后是否强制 kill（group 级开关） |

**per-group 覆盖**：上述 group 级配置支持 `kyuubi.engine.pool.dynamic.group.quota.{groupName}.{key}` 形式覆盖。

### 6.5 缩容排空

| Key | 默认 | 说明 |
|-----|------|------|
| `kyuubi.engine.pool.dynamic.shrink.rejectOpenSessionWhenDraining` | `true` | draining engine 是否拒绝新 session 建连 |

---

## 7. 实现步骤

### 阶段 0：POC 验证（前置）

- [ ] **POC-1**：验证 ZK ephemeral 节点 + 高频 setData 的写性能上限（评估 engine 数量上限，如 200+）
- [ ] **POC-2**：验证 Spark Standalone 下 engine 启动失败的错误码与重试策略
- [ ] **POC-3**：验证 engine 内 DA 开启时的 maxConcurrency 采集口径

### 阶段 1：指标采集（F1, F2, F8）

- [ ] **S1.1** 在 `KyuubiSessionManager` 新增 `getGroupLoadSnapshot`，按 primaryGroup 聚合活跃 session
- [ ] **S1.2** engine 端新增 `EngineLoadReporter`，采集并发/队列/容量，向 ZK ephemeral 节点 setData
- [ ] **S1.3** server 端实现 `EnginePoolManager.getGroupLoad`，聚合 session 数 + 心报数据
- [ ] **S1.4** 注册按 group 维度的 metrics（§5.8）

### 阶段 2：动态 poolSize 状态（F3）

- [ ] **S2.1** 设计 ZK poolMeta 路径与 JSON schema（含 generation 乐观锁）
- [ ] **S2.2** 实现 `getCurrentPoolSize` / `setPoolSize`（含 CAS）/ `markDraining` / `isDraining`
- [ ] **S2.3** 修改 `EngineRef.subdomain`，动态模式读取 ZK poolSize

### 阶段 3：扩容（F4）

- [ ] **S3.1** 实现 `ScalePolicy` 与默认 `UtilizationBasedPolicy`
- [ ] **S3.2** 扩容：更新 ZK size + 可选预创建 `[oldSize, newSize)` 的 engine
- [ ] **S3.3** 实现防抖动（cooldown、window）

### 阶段 4：缩容（F5）

- [ ] **S4.1** 实现 draining 标记（poolMeta.drainingRange）
- [ ] **S4.2** 新 poolSize 下 subdomain 计算只落在 `[0, newSize)`
- [ ] **S4.3** engine 端 `OpenSession` 校验 draining 状态，拒绝新连接
- [ ] **S4.4** 排空等待 + 超时按 group 级 `forceKillAfterDrain` 处理（kill 或继续等待）

### 阶段 5：配额与全局预算（F7，保底 + 抢占）

- [ ] **S5.1** 实现 `GroupQuota` / `GlobalResourceBudget` 配置解析与启动校验
- [ ] **S5.2** 扩容准入检查（maxEngines、全局资源）
- [ ] **S5.3** 实现抢占：扫描低利用率 group、按 weight 升序触发缩容、保底不可动

### 阶段 6：调度器与跨 Server 协调（F6）

- [ ] **S6.1** 实现 `EnginePoolScaler` 调度器（定时线程 + group 级分布式锁）
- [ ] **S6.2** poolMeta 写入的 generation CAS 重试

### 阶段 7：运维与可观测（F8）

- [ ] **S7.1** Admin REST：`GET /api/v1/admin/pool/{group}` 查看 pool 状态
- [ ] **S7.2** Admin REST：`POST /api/v1/admin/pool/{group}/scale?targetSize=N` 手动触发
- [ ] **S7.3** Admin REST：`GET /api/v1/admin/pool` 查看所有 group pool 与全局资源占用
- [ ] **S7.4** 日志规范：关键决策点输出结构化日志

### 阶段 8：测试

- [ ] **S8.1** 单元测试：policy 算法、quota 校验、poolMeta CAS、抢占排序
- [ ] **S8.2** 集成测试：mock engine 心报，验证扩容/缩容/抢占全流程
- [ ] **S8.3** 压测：200+ engine 场景下的 ZK 心跳与决策延迟
- [ ] **S8.4** 混沌测试：engine 异常退出、ZK 抖动、多 server 并发扩缩容、size 切换瞬间并发请求

---

## 8. 关键设计决策（已闭环）

| 编号 | 决策 | 结论 | 来源 |
|------|------|------|------|
| D1 | 资源分配层级 | **仅 engine 数量**，不调单 engine 规格 | 用户确认 |
| D2 | Kyuubi Server 部署 | **多实例**，分布式锁协调 | 用户确认 |
| D3 | 跨 Server 协调 | **分布式锁**（非 leader-only） | 用户确认 |
| D4 | 指标上报通道 | **ZK 心跳**（engine 推送 ephemeral 节点） | 用户确认 |
| D5 | 强制 kill | **group 级配置开关** `forceKillAfterDrain` | 用户确认 |
| D6 | 配额模式 | **保底 + 抢占**（minEngines 不可动，按 weight 抢占低优先级） | 用户确认 |
| D7 | 与 Spark DA 关系 | **允许叠加**，engine 内 DA 与池层弹性正交 | 用户确认 |
| D8 | Metrics 维度 | **按 group 暴露**，沿用 name 拼接模式，走 Prometheus | 用户确认 + 代码调研 |
| D9 | 全局预算优先级 | **第一期必须包含**（F7 列为 P0） | 用户确认 |
| D10 | 负载指标 | **并发 session 数 + engine 内 query 并发/队列** | 用户确认 |
| D11 | subdomain 编号方案 | **保留 `seqNum % poolSize`，poolSize 动态化** | 用户确认 |
| D12 | 缩容目标选择 | **固定为 `[newSize, oldSize)` 区间** | 用户确认 |
| D13 | 向后兼容 | `dynamic.enabled=false` 完全回退静态逻辑 | 工程约束 |

---

## 9. 风险与挑战

| 风险 | 等级 | 缓解措施 |
|------|------|----------|
| Engine 启动慢（Spark 数十秒），扩容滞后导致排队 | 高 | 预创建（preCreateOnScaleUp）+ 基于历史趋势的预测扩容（后续） |
| Spark Standalone 资源不足导致 engine 卡在申请 executor | 高 | 启动超时快速失败 + 全局预算前置校验 + 抢占 |
| 抖动：负载波动导致频繁扩缩容 | 中 | cooldown + hysteresis window |
| ZK 写压力（engine 数量大，心跳频繁） | 中 | 心跳间隔默认 10s；200+ engine 需压测；必要时调大间隔或分 ZK 集群 |
| engine 心跳丢失导致决策失真 | 中 | 心跳超时降级（保守按 maxConcurrency 计）+ 复用 alive checker |
| 多 Server 决策冲突 | 中 | group 级分布式锁 + generation CAS |
| poolSize 切换瞬间路由到 draining engine | 低 | engine 端 OpenSession 拒绝新连接兜底 |
| GROUP 退化到 USER（primary group 为空） | 低 | 启动时校验 + 警告日志 |
| 向后兼容性破坏 | 低 | feature flag + 充分回归 |
| 抢占风暴（多个高优 group 同时抢占同一低优 group） | 低 | group 级锁串行化；被抢占 group 缩容一次到位 |

---

## 10. 待确认问题清单

以下问题为**实施细节级**，不影响整体设计，可在开发过程中明确：

1. **Q1（集群规模）**：Spark Standalone 集群的总 cores/memory、预期 group 数量、单 group 预期 engine 数量？用于校准默认阈值与压测目标。
2. **Q2（心跳间隔与 ZK 容量）**：engine 规模较大时（>200），是否需要调大 `load.reportInterval`？或考虑分集群部署 ZK？
3. **Q3（metrics label 形式）**：第一期是否接受 metric name 拼接（`kyuubi_pool_group_xxx_size`），还是必须改造为 Prometheus 原生 label？后者需额外改动 `PrometheusReporterService`。
4. **Q4（预测扩容）**：第一期是否需要基于历史负载趋势的预测扩容（提前扩容避免启动延迟）？还是只做反应式扩容？

---

## 附录 A：相关源码索引

| 模块 | 文件 | 关键位置 |
|------|------|----------|
| subdomain 计算 | `kyuubi-server/.../engine/EngineRef.scala` | L114-138 |
| 分布式锁 | 同上 | L182-203 (`tryWithLock`) |
| routingUser | 同上 | L105-109 |
| engine 创建 | 同上 | L345-352 (`getOrCreate`) |
| engine 注销 | 同上 | L361-381 (`deregister`) |
| ShareLevel | `kyuubi-common/.../engine/ShareLevel.scala` | L23-47 |
| GroupProvider 接口 | `extensions/server/kyuubi-server-plugin/.../GroupProvider.java` | - |
| HadoopGroupProvider | `kyuubi-server/.../session/HadoopGroupProvider.scala` | - |
| groupProvider 加载 | `kyuubi-server/.../session/KyuubiSessionManager.scala` | L63 |
| idle timeout | `kyuubi-common/.../session/SessionManager.scala` | L340-367 |
| max lifetime | `extensions/engines/spark/.../SparkSQLEngine.scala` | L165-214 |
| 启动并发限制 | `kyuubi-server/.../session/KyuubiSessionManager.scala` | L497-502 |
| alive checker | 同上 | L472-495 |
| Admin REST | `kyuubi-server/.../server/rest/.../AdminResource.scala` | L280-379 |
| ApplicationOperation | `kyuubi-server/.../engine/ApplicationOperation.scala` | - |
| 配置定义（engine pool） | `kyuubi-common/.../config/KyuubiConf.scala` | L2469-2504 |
| Metrics 注册 | `kyuubi-metrics/.../MetricsSystem.scala` | L32-135 |
| Metrics 配置 | `kyuubi-metrics/.../MetricsConf.scala` | - |
| Prometheus reporter | `kyuubi-metrics/.../PrometheusReporterService.scala` | L62-171 |
| user 维度指标先例 | `kyuubi-server/.../session/KyuubiSession.scala` | L60-72 |

## 附录 B：术语表

| 术语 | 含义 |
|------|------|
| ShareLevel | Kyuubi engine 共享级别：CONNECTION/USER/GROUP/SERVER_LOCAL/SERVER |
| primary group | GROUP level 下用于路由的组名（由 GroupProvider 返回） |
| subdomain | engine pool 内单个 engine 的标识（如 `engine-pool-0`） |
| engineSpace | ZK 中 engine 的注册路径命名空间 |
| poolSize | group 的 engine pool 大小，动态模式下存储在 ZK |
| generation | poolMeta 的乐观锁版本号 |
| draining | engine 被标记为"不再接收新 session，等待现有 session 结束"的状态 |
| utilization | group 总并发 / group 总容量 |
| 保底（minEngines） | group 不可被抢占的最低 engine 数 |
| 抢占 | 全局资源不足时，按 weight 触发低优先级 group 缩容以释放资源给高优先级 group |
