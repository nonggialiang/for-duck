# Iceberg REST Server — 项目设计文档

## 1. 项目概述

Iceberg REST Server 是 Apache Gravitino 的独立 Iceberg REST Catalog 代理服务。它实现 [Iceberg REST Catalog 规范](https://iceberg.apache.org/docs/latest/api/rest-catalog-openapi/)，将客户端请求路由到底层 Iceberg Catalog 后端（内存 / JDBC），并在此基础上提供：

- **多 Catalog 支持** — 通过 URL prefix 或 `warehouse` 参数路由到不同 Catalog
- **凭据分发 (Credential Vending)** — 为客户端生成短期、路径限定的 S3 / AWS IRSA 凭据
- **鉴权 (Authorization)** — 基于注解的声明式鉴权，支持 AllowAll / OPA
- **事件总线 (Event Bus)** — Pre/Post/Failure 三阶段事件，支持同步和异步监听器
- **指标收集 (Metrics)** — 异步收集 Commit / Scan 报告，支持 Dummy / JDBC 存储
- **Scan Plan 缓存** — 服务端预计算 FileScanTask 并缓存
- **扩展机制** — 通过配置加载额外的 REST Controller 和 Credential Provider

---

## 2. 模块结构

```
iceberg/
├── pom.xml                          # 父 POM（依赖版本管理）
├── iceberg-common/                  # 共享层：Config、CatalogWrapper、常量、工具类
│   └── src/main/java/.../
│       ├── iceberg/common/
│       │   ├── IcebergConfig.java            # 核心配置类（所有 ConfigEntry）
│       │   ├── ops/IcebergCatalogWrapper.java # Catalog 操作基类
│       │   └── utils/IcebergCatalogUtil.java  # 后端加载工厂
│       └── catalog/lakehouse/iceberg/
│           └── IcebergConstants.java         # 全局常量
│
├── iceberg-rest-server/             # REST 服务层
│   └── src/main/java/.../iceberg/
│       ├── IcebergRESTServer.java            # main() 入口
│       ├── credential/...                    # 凭据系统（Provider/Generator/Cache）
│       ├── listener/...                      # 事件总线 + 监听器
│       └── service/
│           ├── spring/...                    # Spring Boot 基础设施
│           ├── rest/...                      # 6 个 REST Controller
│           ├── authorization/...             # 鉴权框架
│           ├── dispatcher/...                # 事件分发 + 操作执行
│           ├── provider/...                  # 多 Catalog 配置提供者
│           ├── metrics/...                   # 指标收集
│           ├── cache/...                     # Scan Plan 缓存
│           └── ...                           # 其他支持类
│
└── iceberg-rest-trino-it/           # Trino 集成测试（独立模块）
```

---

## 3. 功能点清单

| # | 功能域 | 端点/入口 | 涉及核心类 |
|---|--------|----------|-----------|
| 1 | 配置发现 | `GET /v1/config` | IcebergConfigOperations |
| 2 | Namespace CRUD | `GET/POST/HEAD/DELETE /v1/namespaces[/{ns}]` | IcebergNamespaceOperations |
| 3 | Namespace 属性更新 | `POST /v1/namespaces/{ns}/properties` | IcebergNamespaceOperations |
| 4 | Table CRUD | `GET/POST/DELETE/HEAD /v1/namespaces/{ns}/tables[/{table}]` | IcebergTableOperations |
| 5 | Table 更新 (commit) | `POST /v1/namespaces/{ns}/tables/{table}` | IcebergTableOperations |
| 6 | Table Rename | `POST /v1/tables/rename` | IcebergTableRenameOperations |
| 7 | Table 注册 (外部) | `POST /v1/namespaces/{ns}/register` | IcebergNamespaceOperations |
| 8 | Table 凭据获取 | `GET /v1/namespaces/{ns}/tables/{table}/credentials` | IcebergTableOperations |
| 9 | Table 指标上报 | `POST /v1/namespaces/{ns}/tables/{table}/metrics` | IcebergTableOperations |
| 10 | Table Scan Plan | `POST /v1/{prefix}/namespaces/{ns}/tables/{table}/plan` | IcebergTableOperations |
| 11 | View CRUD | `GET/POST/DELETE/HEAD /v1/namespaces/{ns}/views[/{view}]` | IcebergViewOperations |
| 12 | View Replace | `POST /v1/namespaces/{ns}/views/{view}` | IcebergViewOperations |
| 13 | View Rename | `POST /v1/views/rename` | IcebergViewRenameOperations |
| 14 | 声明式鉴权 | AOP @Around | IcebergAuthorizationAspect + IcebergMetadataAuthorizationMethodInterceptor |
| 15 | 凭据分发 | 嵌入 LoadTableResponse / 独立端点 | CredentialVendingService + CatalogCredentialManager |
| 16 | 事件通知 | 内部 (Dispatcher → EventBus) | IcebergTableEventDispatcher + EventBus |
| 17 | 指标收集 | 异步队列 | IcebergMetricsManager |
| 18 | 多 Catalog | URL prefix / warehouse 参数 | PrefixResolver + IcebergConfigProvider |
| 19 | 扩展加载 | 配置 `extension-packages` | IcebergBeanConfig @ComponentScan |
| 20 | 列表过滤鉴权 | 内部 (List 响应后过滤) | ListResponseFilter |

---

## 4. 请求处理架构

```
                    HTTP Request
                         │
           ┌─────────────▼──────────────┐
           │    DispatcherServlet        │  Spring MVC 路由
           │    (内嵌 Tomcat)            │
           └─────────────┬──────────────┘
                         │
           ┌─────────────▼──────────────┐
           │  IcebergAuthorizationAspect │  @Around AOP 切面
           │  → interceptor.authorize()  │  注解提取 → Authorizer 鉴权
           └─────────────┬──────────────┘
                         │ proceed
           ┌─────────────▼──────────────┐
           │  @RestController 方法       │  构造 IcebergRequestContext
           │  (6 个 Controller)          │
           └─────────────┬──────────────┘
                         │
           ┌─────────────▼──────────────┐
           │  EventDispatcher            │  fire PreEvent → execute → fire PostEvent
           │  (Table/View/Namespace)     │  失败时 fire FailureEvent
           └─────────────┬──────────────┘
                         │
           ┌─────────────▼──────────────┐
           │  OperationExecutor          │  薄层委托
           └─────────────┬──────────────┘
                         │
           ┌─────────────▼──────────────┐
           │  IcebergCatalogWrapperMgr   │  Caffeine cache → createCatalogWrapper
           │  → CatalogWrapperForREST    │
           └─────────────┬──────────────┘
                         │
           ┌─────────────▼──────────────┐
           │  IcebergCatalogWrapper      │  Iceberg CatalogHandlers
           │  (Memory / JDBC backend)    │
           └─────────────┬──────────────┘
                         │
           ┌─────────────▼──────────────┐
           │  CredentialVendingService   │  可选：注入凭据到响应
           │  ScanPlanService            │  可选：预计算 scan plan
           └────────────────────────────┘

异常 → @ControllerAdvice (IcebergGlobalExceptionHandler)
     → IcebergExceptionMapper.toResponseEntity() → HTTP 错误响应
```

---

## 5. 各层详细设计

### 5.1 配置层 (`iceberg-common`)

#### `IcebergConfig`

继承 Gravitino `Config`，使用 `ConfigEntry` 模式定义所有配置项。配置前缀 `gravitino.iceberg-rest.`。

| 配置组 | 关键 Key | 默认值 | 说明 |
|--------|---------|--------|------|
| Catalog 后端 | `catalog-backend` | `memory` | `memory` 或 `jdbc` |
| | `warehouse` | — | 数据仓库路径 |
| | `uri` | — | JDBC 连接字符串 |
| | `jdbc.user` / `jdbc.password` / `jdbc-driver` | — | JDBC 认证 |
| IO | `io-impl` | — | 如 `S3FileIO` |
| | `s3.endpoint` / `s3.region` | — | S3 端点 |
| | `s3.path-style-access` | `false` | S3 路径风格 |
| 鉴权 | `authorization.authorizer` | `allow-all` | `allow-all` 或 `opa` |
| | `authorization.opa.url` | — | OPA 服务地址 |
| | `authorization.opa.cache-ttl-seconds` | `30` | OPA 决策缓存 TTL |
| | `authorization.opa.timeout-ms` | `2000` | OPA 请求超时 |
| 缓存 | `iceberg-rest.catalog-cache-eviction-interval` | `3600000` (1h) | Catalog Wrapper 缓存驱逐 |
| | `table-metadata-cache-impl` | — | Table 元数据缓存实现类 |
| | `scan-plan-cache-impl` | — | Scan Plan 缓存实现类 |
| 扩展 | `extension-packages` | `[]` | 额外 REST 扩展包 |
| 多 Catalog | `iceberg-rest.catalog-config-provider` | `static` | 配置提供者类型 |
| 指标 | `iceberg-metrics-store` | — | `dummy` 或 `jdbc` |

#### `IcebergCatalogWrapper` (基类)

封装 Iceberg `Catalog` 实例，通过 `CatalogHandlers` 委托所有 CRUD 操作。

**构造流程：**
1. 从 config 解析 `IcebergCatalogBackend` 枚举（MEMORY / JDBC）
2. 校验 warehouse 非空（MEMORY 除外）
3. `IcebergCatalogUtil.loadCatalogBackend()` 加载实际 Catalog
4. 初始化 `TableMetadataCache`（可选）
5. 构建 Hadoop `Configuration`

**TableMetadataCache 集成：** `loadTable` 先查缓存；`createTable`/`updateTable` 写缓存；`dropTable`/`renameTable` 失效缓存条目。无配置时使用 `DUMMY`（no-op）。

#### `IcebergCatalogUtil`

工厂方法 `loadCatalogBackend(backend, config)`：
- **MEMORY** → `MemoryCatalogWithMetadataLocationSupport`，默认 warehouse `/tmp`
- **JDBC** → `JdbcCatalogWithMetadataLocationSupport`，通过 `Class.forName` 加载驱动

两个自定义 Catalog 类都添加了 metadata-location 支持（Iceberg 原生不提供），供 `TableMetadataCache` 使用。

#### `IcebergConstants`

全局常量定义：
- `ICEBERG_REST_DEFAULT_CATALOG = "default_catalog"` — 默认 catalog 名
- `STATIC_ICEBERG_CATALOG_CONFIG_PROVIDER_NAME = "static"` — 默认 provider
- IO 属性名：`io-impl`, `s3.endpoint`, `s3.access-key-id`, `s3.secret-access-key`, `s3.session-token`, `client.region` 等

---

### 5.2 多 Catalog 配置提供者

#### `IcebergConfigProvider` (接口)

```java
void initialize(Map<String, String> properties)
Optional<IcebergConfig> getIcebergCatalogConfig(String catalogName)
default String getDefaultCatalogName()  // "default_catalog"
```

#### `StaticIcebergConfigProvider` (默认实现)

从扁平配置属性解析多 Catalog：

```properties
# 每个 catalog.<name>.<key> 定义一个具名 catalog
gravitino.iceberg-rest.catalog.jdbc_proxy.catalog-backend = jdbc
gravitino.iceberg-rest.catalog.jdbc_proxy.uri = jdbc:mysql://...

# 同时注册默认 catalog（使用顶层属性）
catalogConfigs.put("default_catalog", new IcebergConfig(topLevelProperties))
```

---

### 5.3 Catalog Wrapper 管理

#### `IcebergCatalogWrapperManager`

使用 Caffeine 缓存管理 `CatalogWrapperForREST` 实例：

```java
// 核心：cache-aside 模式
public CatalogWrapperForREST getCatalogWrapper(String catalogName) {
    return catalogWrapperCache.get(catalogName, k -> createCatalogWrapper(k));
}
```

**Caffeine 配置：**
- `expireAfterAccess` = 1 小时（可配置）
- `removalListener` → 关闭被驱逐的 catalog wrapper
- `scheduler` → 守护线程定期驱逐过期条目

#### `CatalogWrapperForREST`

继承 `IcebergCatalogWrapper`，通过组合持有两个服务：

| 字段 | 类型 | 职责 |
|------|------|------|
| `credentialVendingService` | CredentialVendingService | 凭据注入到 LoadTableResponse |
| `scanPlanService` | ScanPlanService | 服务端 scan plan 预计算 |

关键方法重载 `createTable` / `loadTable`，在基类操作后可选注入凭据。

---

### 5.4 REST Controller 层

所有 Controller 位于 `service/rest/`，使用 Spring MVC 注解。

#### 通用模式

```java
@RestController
@RequestMapping(
    path = {"/v1/.../resource", "/v1/{prefix}/.../resource"},  // 双路径
    produces = MediaType.APPLICATION_JSON_VALUE)
public class XxxOperations {

    // 构造器注入 dispatcher
    // @IcebergAuthorizationOperation 声明操作类型
    // @AuthorizationMetadata 标注参数对应的资源级别
    // @PathVariable(value = "prefix", required = false) 处理可选 prefix
    // 返回 ResponseEntity<Object>
}
```

#### 六个 Controller

| Controller | 端点数 | 鉴权注解 | 特殊处理 |
|-----------|-------|---------|---------|
| **IcebergConfigOperations** | 1 | 无 | 返回 endpoints 列表 + catalog 配置；custom `V1_SUBMIT_TABLE_SCAN_PLAN` |
| **IcebergNamespaceOperations** | 7 | CREATE/LOAD/DROP/UPDATE/LIST/EXISTS_NAMESPACE | registerTable 端点也在此类 |
| **IcebergTableOperations** | 9 | CREATE/LOAD/DROP/UPDATE/RENAME/EXISTS/LIST/PLAN_TABLE_SCAN + LOAD_TABLE_CREDENTIAL | HEAD 用 `@RequestMapping(method=HEAD)` |
| **IcebergViewOperations** | 6 | CREATE/LOAD/DROP/REPLACE/RENAME/EXISTS/LIST_VIEW | 使用 Iceberg View API |
| **IcebergTableRenameOperations** | 1 | RENAME_TABLE | 独立路径 `/v1/tables/rename` |
| **IcebergViewRenameOperations** | 1 | RENAME_VIEW | 独立路径 `/v1/views/rename` |

#### `IcebergConfigOperations` 详细

`GET /v1/config?warehouse={name}` 是客户端首个调用的端点：

1. 解析 catalogName（warehouse 为空时用默认）
2. 检查 catalog 是否支持 View 操作
3. 构建 `ConfigResponse`：
   - `defaults`：catalog 级 IO 配置（S3 endpoint, region 等）
   - `endpoints`：表操作端点列表（+ view 端点如果支持）
   - `prefix`：warehouse 值（非空时）

**自定义端点 `V1_SUBMIT_TABLE_SCAN_PLAN`：** 覆盖 Iceberg 1.10.1 中路径缺少 `namespaces/{namespace}` 段的 bug（apache/iceberg#14120，目标 1.11.x 修复）。

---

### 5.5 鉴权系统

#### 架构

```
Controller 方法
  @IcebergAuthorizationOperation(LOAD_TABLE)      ← 声明操作类型
  @AuthorizationMetadata(type=CATALOG) String prefix  ← 标注参数资源级别
  @AuthorizationMetadata(type=SCHEMA) String namespace
  @IcebergAuthorizationMetadata(type=LOAD_TABLE) String table  ← 特殊处理
       │
       ▼
IcebergAuthorizationAspect (@Around)
       │
       ▼
IcebergMetadataAuthorizationMethodInterceptor.authorize(method, args)
  1. extractNameIdentifierFromParameters → Map<EntityType, NameIdentifier>
  2. createAuthorizationHandler (可选) → LoadTable/RenameTable/RenameView Handler
  3. handler.process(map) 或 默认 checkOperation
  4. 失败 → throw ForbiddenException
```

#### 核心类型

| 类型 | 作用 |
|------|------|
| `IcebergAuthorizer` (接口) | `checkOperation()`, `checkCredential()`, `registerOwner()`, `removeOwner()` |
| `IcebergOperation` (枚举) | 23 个操作类型（CREATE_TABLE, LOAD_TABLE, ...） |
| `IcebergResource` (值对象) | 不可变，层级表示 catalog.schema.table |
| `AllowAllAuthorizer` | 默认实现，全部放行 |
| `OPAIcebergAuthorizer` | OPA REST 委托，双 Caffeine 缓存（决策缓存 + 凭据缓存），默认 deny |

#### 三个注解

| 注解 | 目标 | 用途 |
|------|------|------|
| `@IcebergAuthorizationOperation` | 方法 | 声明操作类型 |
| `@AuthorizationMetadata` | 参数 | 标注参数对应的资源级别（CATALOG/SCHEMA/TABLE/VIEW） |
| `@IcebergAuthorizationMetadata` | 参数 | 标注需要特殊处理的参数（LOAD_TABLE/RENAME_TABLE/RENAME_VIEW） |

#### 特殊 Handler

| Handler | 场景 | 关键逻辑 |
|---------|------|---------|
| `LoadTableAuthzHandler` | `LOAD_TABLE` | 1) 元数据表检查（history/snapshots 等）→ NoSuchTableException；2) View 伪装检查 → NoSuchTableException；3) 执行鉴权。**防止信息泄露**：未授权用户无法区分 "不存在" vs "是 View" vs "无权限" |
| `RenameTableAuthzHandler` | `RENAME_TABLE` | 从 request body 提取源表名；跨 namespace 时同时检查源表 RENAME 和目标 schema CREATE |
| `RenameViewAuthzHandler` | `RENAME_VIEW` | 同上，用于 View |

#### `OPAIcebergAuthorizer` 缓存

```
decisionCache: CacheKey("op", user, operation, resource) → Boolean
credentialCache: CacheKey("cred", user, null, resource) → CredentialPrivilege

expireAfterWrite = 30s (可配置)
maximumSize = 10,000
```

OPA 请求格式（规则路径查询，而非包根查询——包根返回全部规则对象，无法按布尔解析）：

- 操作鉴权：`POST {opaUrl}/v1/data/iceberg/rest/allow`，body `{input: {user, action, resource}}`，期望 `{result: true/false}`
- 凭据鉴权：`POST {opaUrl}/v1/data/iceberg/rest/credential_privilege`，body `{input: {user, resource}}`，期望 `{result: "write"|"read"|null}`（`result` 缺失或为 null 视为无凭据权限）

#### `ListResponseFilter`

静态工具类，在 List 响应返回后根据鉴权结果过滤条目：

```java
// 过滤逻辑：对每个返回的 table/view/namespace，检查 LOAD 权限
ListTablesResponse filterTables(resp, catalogName, authorizer)
ListTablesResponse filterViews(resp, catalogName, authorizer)
ListNamespacesResponse filterNamespaces(resp, catalogName, authorizer)
```

---

### 5.6 Dispatcher 层（事件分发 + 操作执行）

#### 三层架构

```
EventDispatcher (fire events)
  └→ OperationExecutor (薄委托)
       └→ IcebergCatalogWrapperManager.getCatalogWrapper(catalogName)
            └→ CatalogWrapperForREST (实际 Iceberg 操作)
```

#### 事件生命周期

```
PRE-EVENT  ──→  EXECUTE  ──→  SUCCESS: POST-EVENT
                   │
                   └─→  FAILURE: FAILURE-EVENT (then re-throw)
```

每个操作产生 3 个事件类（以 CreateTable 为例）：

| 事件 | 基类 | 额外字段 | SupportsChangingPreEvent |
|------|------|---------|------------------------|
| `IcebergCreateTablePreEvent` | IcebergTablePreEvent | CreateTableRequest | **是** |
| `IcebergCreateTableEvent` | IcebergTableEvent | CreateTableRequest, LoadTableResponse | — |
| `IcebergCreateTableFailureEvent` | IcebergTableFailureEvent | CreateTableRequest, Exception | — |

#### 六个 Dispatcher/Executor 类

| 类型 | Dispatcher (事件) | Executor (执行) |
|------|-------------------|----------------|
| Table | IcebergTableEventDispatcher | IcebergTableOperationExecutor |
| View | IcebergViewEventDispatcher | IcebergViewOperationExecutor |
| Namespace | IcebergNamespaceEventDispatcher | IcebergNamespaceOperationExecutor |

---

### 5.7 事件系统

#### 核心类

| 类 | 作用 |
|---|------|
| `EventBus` | 中央分发枢纽，路由 PreEvent / PostEvent 到所有监听器 |
| `EventListenerManager` | 加载、组装、管理监听器生命周期；`createEventBus()` |
| `EventListenerPlugin` (接口) | 用户 SPI；Mode: SYNC / ASYNC_ISOLATED / ASYNC_SHARED |
| `EventListenerPluginWrapper` | 装饰器，隔离监听器异常（除 SYNC 模式 `ForbiddenException` 传播外） |
| `AsyncQueueListener` | 队列 + 后台线程异步处理；高水位时丢弃事件 |

#### 事件类层级

```
BaseEvent (user, identifier, eventTime)
├── PreEvent (status=UNPROCESSED)
│   └── IcebergPreEvent (+ IcebergRequestContext)
│       ├── IcebergTablePreEvent → 10 个具体 PreEvent
│       ├── IcebergNamespacePreEvent → 6 个
│       └── IcebergViewPreEvent → 7 个
└── Event
    └── IcebergEvent (status=SUCCESS)
        ├── IcebergTableEvent → 10 个
        ├── IcebergNamespaceEvent → 6 个
        ├── IcebergViewEvent → 7 个
        └── IcebergFailureEvent (status=FAILURE, +exception)
            ├── IcebergTableFailureEvent → 10 个
            ├── IcebergNamespaceFailureEvent → 6 个
            └── IcebergViewFailureEvent → 7 个
```

**总计约 80 个具体事件类**，每种操作 3 个（Pre/Post/Failure）。

#### `IcebergRequestContext`

```java
class IcebergRequestContext {
    HttpServletRequest httpRequest;
    String catalogName;
    boolean requestCredentialVending;
    // 派生: userName, remoteHostName, httpHeaders
}
```

#### `SupportsChangingPreEvent`

标记接口。实现了此接口的 PreEvent 会被 SYNC 监听器通过 `transformPreEvent()` 链式修改。只有 `CreateTablePreEvent` 和 `UpdateTablePreEvent` 实现（因为只有它们携带可变的 request 对象）。

#### 监听器组装规则

```
SYNC           → Wrapper(listener)
ASYNC_ISOLATED → AsyncQueueListener([Wrapper(listener)])    // 独立队列+线程
ASYNC_SHARED   → 收集到列表 → AsyncQueueListener(allShared) // 共享队列+线程
```

---

### 5.8 凭据系统

#### 三层架构

```
CredentialVendingService (REST 层入口)
  └→ CatalogCredentialManager (管理 + 缓存)
       ├→ CredentialCache<CredentialCacheKey> (Caffeine, 可变 TTL)
       └→ CredentialProvider (SPI, ServiceLoader 发现)
            ├→ 直接实现 (S3SecretKeyProvider)
            └→ CredentialProviderDelegator → CredentialGenerator (S3Token / AwsIrsa)
```

#### 凭据类型

| 类型 | Provider | Generator | 凭据内容 | 场景 |
|------|----------|-----------|---------|------|
| `s3-secret-key` | S3SecretKeyProvider | 无（直接从配置读取） | access-key-id + secret-access-key | 静态密钥 |
| `s3-token` | S3TokenProvider | S3TokenGenerator | access-key + secret-key + session-token | STS AssumeRole |
| `aws-irsa` | AwsIrsaCredentialProvider | AwsIrsaCredentialGenerator | 同上 | EKS IRSA (WebIdentity) |

#### 凭据分发流程

```
1. 客户端请求 LoadTable (带 request-credentials=true) 或 GET credentials
2. CredentialVendingService.shouldGenerateCredential() 判断是否需要凭据
   → 跳过 file:// / hdfs:// 路径
3. 提取 table location / write-data-location / write-metadata-location
4. 按 CredentialPrivilege (READ/WRITE) 分配到 readPaths / writePaths
5. 创建 PathBasedCredentialContext(userName, writePaths, readPaths)
6. CatalogCredentialManager.getCredential(context) → cache lookup → provider.getCredential()
7. Generator 调用 AWS STS，构建路径限定的 IAM Session Policy
8. Credential → CredentialPropertyUtils.toIcebergProperties() → 注入 LoadTableResponse
```

#### `CredentialPropertyUtils`（适配层）

将 Gravitino 内部属性名映射到 Iceberg 期望的属性名：

```
s3-access-key-id    → s3.access-key-id
s3-secret-access-key → s3.secret-access-key
s3-session-token    → s3.session-token
```

#### `CredentialCache` 可变 TTL

```java
// Caffeine Expiry：根据每个凭据的过期时间动态计算 TTL
expireAfterCreate = (credential.expireTimeInMs - now) * cacheExpireRatio
// cacheExpireRatio 默认 0.15，即凭据在过期前 15% 时间处缓存失效
```

---

### 5.9 指标收集

#### `IcebergMetricsManager`

异步收集 Commit / Scan 报告：

```
reportMetric(catalog, namespace, metricsReport)
  → queue.offer(MetricsReportWrapper)    // 非阻塞，队列满时丢弃
  → writer thread: queue.take() → store.recordMetric()
  → cleaner (可选): 每小时执行 store.clean(expireTime)
```

#### `IcebergMetricsStore` 实现

| 实现 | 类型名 | 说明 |
|------|--------|------|
| `DummyMetricsStore` | `dummy` | 默认，全部 no-op |
| `JDBCMetricsStore` | `jdbc` | 两个表 `commit_metrics_report` + `scan_metrics_report`，INSERT 全量 counter/timer 字段 |

通过 `iceberg-metrics-store` 配置项选择，支持短名（`dummy` / `jdbc`）或全限定类名。

---

### 5.10 Scan Plan 缓存

#### `ScanPlanService`

```
planTableScan(tableIdentifier, request):
  1. table = catalog.loadTable(id)
  2. cacheKey = ScanPlanCacheKey.create(id, table, request)
  3. cached = cache.get(cacheKey) → HIT: return
  4. MISS: createFilePlanScanTasks(table, request)
     → IncrementalAppendScan (有 start/end snapshot) 或 TableScan
  5. 序列化 FileScanTask → JSON
  6. PlanTableScanResponse(COMPLETED) → cache.put() → return
```

#### `ScanPlanCacheKey`

不可变缓存键，包含：tableIdentifier, snapshotId, start/endSnapshotId, filter (toString 比较), select (排序后逗号连接), statsFields (排序), caseSensitive, useSnapshotSchema。

#### `ScanPlanCache` 实现

| 实现 | 说明 |
|------|------|
| `ScanPlanCache.DUMMY` | 默认 no-op（不配置时） |
| `LocalScanPlanCache` | Caffeine，`maximumSize` + `expireAfterAccess` |

---

### 5.11 Spring Boot 基础设施

#### `IcebergBeanConfig`

```
@Configuration + @EnableAutoConfiguration + @ComponentScan("org.apache.gravitino.iceberg")
```

所有 Bean 方法链：

```
Properties → IcebergConfig → {ConfigProvider, EventListenerManager→EventBus,
                              CatalogWrapperManager, MetricsManager, Authorizer}
                             → {Table/View/Namespace}Executor → EventDispatcher
ObjectMapper (@Primary)
```

内部类 `ServerContextInitializer` (@Component) 在 `@PostConstruct` 初始化 ServerContext 单例。

#### `ServerContext` (单例)

```java
volatile static ServerContext instance
├── IcebergAuthorizer authorizer
├── IcebergCatalogWrapperManager catalogWrapperManager
└── String defaultCatalogName    // "default_catalog"
```

`PrefixResolver` 和 `IcebergRESTUtils` 通过 `getInstance()` 访问。

#### 其他基础设施类

| 类 | 作用 |
|---|------|
| `IcebergRestServerApplication` | 纯启动类（无 @SpringBootApplication，避免测试冲突） |
| `IcebergWebMvcConfig` | CORS + 禁用尾斜杠匹配 |
| `IcebergTomcatConfig` | `encodedSolidusHandling=passthrough` (%2F 处理) |
| `IcebergAuthorizationAspect` | @Aspect @Around 鉴权切面 |
| `IcebergGlobalExceptionHandler` | @ControllerAdvice 全局异常处理 |

---

### 5.12 支持类

| 类 | 作用 |
|---|------|
| `PrefixResolver` | URL prefix → catalog name；null/blank → 默认 catalog |
| `IcebergExceptionMapper` | 异常类 → HTTP 状态码映射（400/401/403/404/409/422/500/503） |
| `HttpResponseBuilder` | `ResponseEntity<Object>` 构建辅助（ok/noContent/notFound/error） |
| `IcebergObjectMapper` | Iceberg REST ObjectMapper 单例（注册 Iceberg 模块） |
| `IcebergRESTUtils` | `getGravitinoNameIdentifier()`, `cloneIcebergRESTObject()`, `isCredentialVending()` |
| `IcebergRESTServer` | main 入口，加载 .conf → system properties → `SpringApplication.run()` |

---

### 5.13 扩展机制

#### REST 扩展

通过 `extension-packages` 配置额外包名，`IcebergBeanConfig` 的 `@ComponentScan("org.apache.gravitino.iceberg")` 自动发现 `@RestController` 类。

示例：`HelloOperations` 提供 `GET /hello` 端点。

#### Credential Provider 扩展

通过 Java SPI (`ServiceLoader<CredentialProvider>`) 发现，注册在 `META-INF/services/org.apache.gravitino.credential.CredentialProvider`。也可通过 `CredentialProviderDelegator` + 自定义 `CredentialGenerator` 扩展。

示例：`DummyCredentialProvider` 返回空凭据。

### 5.14 动态视图（Entitlement Row Filter）

元数据层的行级过滤：为配置了行过滤 SQL 的只读用户，把表"伪装"成视图——引擎按 Iceberg 的表→视图回退协议加载 `/views/{t}` 时，服务端合成一个引用 `t@entitlement` 后缀物理表、并带 `WHERE row_filter` 的视图。默认关闭（`authorization.entitlement.enabled=false`）。

#### 端到端流程

```
alice(只读+filter region = 'US')   bob(写)      dave(写+filter)   carol(只读)
GET /tables/t1 ──┐
                 ├─ EntitlementFilter → resolveMode
GET /tables/t1@entitlement ─┘        │
                                     ├─ ENTITLED(alice)：/tables/t1 → 伪装404
                                     │   /tables/t1@entitlement → 剥后缀→200真实表
                                     │   /views/t1 → executor 合成视图：
                                     │     SELECT * FROM `cat`.`db`.`t1@entitlement`
                                     │     WHERE region = 'US'
                                     ├─ NORMAL(bob/carol)：不干预，authz 决定
                                     └─ CONFLICT(dave)：500 明确报错
```

#### resolveMode 三态语义（EntitlementSupport.resolveMode）

| 写权限（UPDATE_TABLE 可通过） | 行过滤（getRowFilter 非空） | Mode | 行为 |
|---|---|---|---|
| F | F | NORMAL | 走普通 authz |
| T | F | NORMAL | 全量访问，直接写原表 |
| F | T | ENTITLED | 伪装 404 + 合成过滤视图 |
| T | T | CONFLICT | 拒绝（500 `ServiceFailureException`），提示管理员移除其一 |

写权限信号 = `checkOperation(user, UPDATE_TABLE, resource)`（owner / modify privilege 均视为写）。冲突在 load 阶段即暴露（引擎写前必先 load）。

#### 请求侧唯一决策点：EntitlementFilter

注册于认证 filter 之后（order = MIN_VALUE+110，`/v1/*`），按请求形态处理：

| 请求形态 | 处理 |
|---|---|
| `/tables/{t}@entitlement`（`%40` 编码同样支持） | ENTITLED → `HttpServletRequestWrapper` 重写 URI/URL 剥后缀放行（下游正常 authz/凭证/缓存，返回真实表）；NORMAL → 不重写放行（下游自然 404，防绕过）；CONFLICT → 500 |
| 无后缀的 GET/HEAD `/tables/{t}` | ENTITLED → 短路 404（`NoSuchTableException` JSON 形态，与 IcebergExceptionMapper 一致，防枚举）；CONFLICT → 500；NORMAL → 放行 |
| 其他（`/views/`、POST、rename、list 等） | 放行（视图合成分流在 executor） |

**死循环结构性消除**：伪装 404 只对"未带后缀的 GET/HEAD"生效，且发生在同一 filter 单次通过中；剥后缀后的请求恒为 200 真实表，引擎侧亦无循环。

#### 视图合成：IcebergViewOperationExecutor.loadView

`loadView` 抛 `NoSuchViewException | UnsupportedOperationException`（视图不存在或 catalog 不支持视图）时三态分流：ENTITLED → `wrapper.loadTable` 取 TableMetadata → `EntitlementSupport.buildLoadViewResponse`（SQL 由 dialect 生成；确定性 view-uuid = `nameUUIDFromBytes(user/catalog/schema/table)`；location = `表location + "-entitlement-view"`；schema 复用真实表；properties 标记 `gravitino.entitlement-view=true`；metadataLocation 为派生的虚拟路径）；CONFLICT → 抛 `ServiceFailureException`；NORMAL → 原样重抛。真实存在的视图优先于合成。

#### OPA 契约（独立 rego 包）

策略与数据均与鉴权包（`iceberg.rest`）分离：

- 策略文件 `resources/opa/iceberg-entitlement-policy.rego`，`package iceberg.entitlement`，规则 `row_filter`
- 查询端点 `POST /v1/data/iceberg/entitlement/row_filter`，body `{"input":{"user","resource":{...}}}`，响应 `{"result":"<行过滤SQL>"}`（缺失/null/非字符串 → null）
- 数据文档（管理员经 OPA Data API 写入）：
  ```
  PUT /v1/data/iceberg/entitlement/filters/alice/cat/db/t1
  body: "region = 'US'"     （JSON string）

  DELETE /v1/data/iceberg/entitlement/filters/alice/cat/db/t1
  ```
- Java 侧独立缓存 `entitlementCache`（CacheKey("ent", user, null, resource)，同 TTL/容量）；`entitlementEnabled=false` 时不发任何请求

#### Dialect 扩展点

`EntitlementDialect` 接口（`name()` + `buildSelectSql(catalog, schema, suffixedTable, rowFilter)`），`EntitlementSupport.configureDialect` 启动时按 `authorization.entitlement.dialect` 选择（默认 `spark`，backtick 引用并转义内部反引号；未知名抛 IllegalArgumentException）。Iceberg ViewVersion 支持多 representation 共存，未来可一次下发多方言。

#### 配置项

| 配置 | 默认 | 说明 |
|---|---|---|
| `authorization.entitlement.enabled` | `false` | 是否启用 entitlement 行级过滤 |
| `authorization.entitlement.dialect` | `spark` | 合成视图 SQL 方言 |

#### 已知限制

1. **文件级绕过**：行过滤是 SQL 层的；持有 S3 凭据者可直接读底层文件（元数据层过滤固有局限，需对象存储侧配合）
2. `tableExists`/`SHOW TABLES` 对 entitled 用户仍报告表存在；listView 不列出合成视图（仅按名加载）
3. dialect 默认 spark；其他引擎需实现 Dialect 且引擎支持 Iceberg view 规范 + 表→视图回退解析
4. CONFLICT 用户在 load 阶段报 500
5. 真实存在名为 `xxx@entitlement` 的表且用户对 `xxx` 有 entitlement 时，后缀语义优先（边缘冲突）
6. OPA 行过滤查询失败时返回 null（按 NORMAL 处理），与 `checkCredential` 的容错一致

---

## 6. 关键实现细节

### 6.1 双路径映射

Iceberg REST 规范支持可选 prefix。Spring MVC 用双路径声明实现：

```java
@RequestMapping(path = {
    "/v1/namespaces/{namespace}/tables",          // 无 prefix: prefix=null
    "/v1/{prefix}/namespaces/{namespace}/tables"   // 有 prefix: prefix=catalog_name
})
```

Spring "最具体匹配优先" 规则确保无冲突。`@PathVariable(value = "prefix", required = false)` 处理可选性。

### 6.2 ServerContext 初始化避免循环引用

`@Configuration` 类的 `@PostConstruct` 不能调用自身的 `@Bean` 方法（CGLIB 代理导致 `BeanCurrentlyInCreationException`）。解法：提取为独立 `@Component`，构造器注入所有依赖。

### 6.3 null prefix 处理

Spring MVC 中 `@PathVariable(required=false)` 缺失时值为 Java `null`。但 `String.valueOf(null)` 返回字符串 `"null"` 而非 Java null，导致 `PrefixResolver` 返回 catalog 名 `"null"`。必须在拦截器中显式检查：

```java
String value = args[i] == null ? null : String.valueOf(args[i]);
```

### 6.4 凭据路径限定 IAM Policy

`S3TokenGenerator.createPolicy()` 和 `AwsIrsaCredentialGenerator.createSessionPolicy()` 构建路径限定的 IAM Session Policy：

```json
{
  "Statement": [
    {"Effect": "Allow", "Action": ["s3:GetObject","s3:GetObjectVersion"],
     "Resource": ["arn:aws:s3:::bucket/read-path/*"]},
    {"Effect": "Allow", "Action": ["s3:PutObject","s3:DeleteObject"],
     "Resource": ["arn:aws:s3:::bucket/write-path/*"]},
    {"Effect": "Allow", "Action": ["s3:ListBucket","s3:GetBucketLocation"],
     "Resource": ["arn:aws:s3:::bucket"],
     "Condition": {"StringLike": {"s3:prefix": ["read-path/*","write-path/*"]}}}
  ]
}
```

### 6.5 事件 PreEvent 转换链

`SupportsChangingPreEvent` 允许监听器修改操作请求。EventBus 按顺序在所有 SYNC 监听器上调用 `transformPreEvent()`，每次的输出作为下一次的输入：

```
原始 PreEvent → listener1.transformPreEvent() → listener2.transformPreEvent() → ... → 最终 PreEvent
```

只有 `CreateTablePreEvent` 和 `UpdateTablePreEvent` 实现此接口。

### 6.6 LoadTable 鉴权防信息泄露

`LoadTableAuthzHandler` 在鉴权前做两项检查：
1. **元数据表检查**：如果表名匹配 `MetadataTableType`（history, snapshots 等），抛 `NoSuchTableException`
2. **View 伪装检查**：如果 catalog 支持 View 且该名称是 View，抛 `NoSuchTableException`

这使得未授权用户无法区分"不存在"、"是 View"、"是元数据表"和"无权限"。

### 6.7 OPA 默认拒绝

`OPAIcebergAuthorizer.doCheckOperation()` 在任何异常或非预期响应时返回 `false`（拒绝）。包括：非 200 状态码、JSON 解析失败、缺少 `result` 字段、`result` 非布尔值。

---

## 7. 测试架构概览

```
IcebergRestTestBase (@SpringBootTest + @AutoConfigureMockMvc)
├── MockMvc + ObjectMapper
├── doGet / doPost / doDelete / doHead
├── 路径构建器 + prefix 注入
│
├── IcebergNamespaceTestBase
│   ├── verify* 辅助方法 (Create/Load/Drop/List/Update/Register)
│   └── dropAllExistingNamespace() (多轮重试 + 深度排序)
│
├── TestSpringIcebergConfig (4 tests)
├── TestSpringIcebergNamespaceOperations (7 tests)
├── TestSpringIcebergTableOperations (5 tests)
├── TestSpringIcebergViewOperations (4 tests)
└── TestSpringIcebergEntitlementViews (5 tests，entitlement 动态视图端到端)
```

测试用 `IcebergTestApp` 排除生产配置，提供 `@Primary` 测试 Bean（内存 Catalog、DummyCredentialProvider），authorizer 为注入式 bean（默认 AllowAll，可用嵌套 `@TestConfiguration` 的 `@Primary` stub 覆盖；entitlement 测试以 `PrincipalUtils.doAs(new UserPrincipal(user), ...)` 包裹请求模拟不同用户）。

非 MockMvc 测试：TestOPAIcebergAuthorizer（mock HTTP server，含 row_filter 路径）、TestIcebergViewOperationExecutor（loadView 三态合成）、TestEntitlementSupport（resolveMode 四象限/后缀工具/视图合成）、TestEntitlementFilter（MockHttpServletRequest 直调 filter）、TestIcebergMetadataAuthorizationMethodInterceptor、TestPrefixResolver、TestIcebergExceptionMapper 等。
