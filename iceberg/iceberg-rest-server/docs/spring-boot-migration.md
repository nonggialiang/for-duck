# Iceberg REST Server — Spring Boot 迁移设计文档

## 1. 目标与背景

将 `iceberg-rest-server` 从 **Jersey + Jetty + HK2** 迁移到 **Spring Boot 3.x（内嵌 Tomcat）**，消除全部 JAX-RS / `javax.*` 依赖，统一使用 Spring MVC + `jakarta.*` 命名空间。保留自定义 `IcebergConfig` 配置框架和 `ServerContext` 单例。

**迁移前技术栈：** Jersey (JAX-RS) + Jetty (Servlet 容器) + HK2 (DI) + Dropwizard Metrics

**迁移后技术栈：** Spring Boot 3.3.0 + Spring MVC + Spring AOP + 内嵌 Tomcat

---

## 2. 整体架构

```
                    ┌─────────────────────────────────┐
                    │       IcebergRESTServer          │  (main 入口，加载 .conf)
                    │   SpringApplication.run(...)      │
                    └──────────────┬───────────────────┘
                                   │
                    ┌──────────────▼───────────────────┐
                    │      IcebergBeanConfig           │  @Configuration
                    │  @EnableAutoConfiguration        │  @ComponentScan
                    │  @ComponentScan("...iceberg")     │
                    │                                  │
                    │  Bean: Properties, IcebergConfig, │
                    │    ConfigProvider, WrapperManager,│
                    │    MetricsManager, Authorizer,    │
                    │    Dispatchers, ObjectMapper      │
                    └──────────────┬───────────────────┘
                                   │ discovers via @ComponentScan
                    ┌──────────────▼───────────────────┐
                    │     6 @RestController classes     │
                    │  Table / Namespace / View /       │
                    │  Config / TableRename / ViewRename│
                    └──────────────┬───────────────────┘
                                   │ AOP @Around
                    ┌──────────────▼───────────────────┐
                    │  IcebergAuthorizationAspect       │
                    │  → IcebergMetadataAuthorization   │
                    │    MethodInterceptor.authorize()  │
                    └──────────────┬───────────────────┘
                                   │ dispatches to
                    ┌──────────────▼───────────────────┐
                    │  EventDispatcher → Executor →     │
                    │  IcebergCatalogWrapperManager →   │
                    │  CatalogWrapperForREST             │
                    └──────────────────────────────────┘
```

异常由 `@ControllerAdvice` (IcebergGlobalExceptionHandler) 统一捕获，委托 `IcebergExceptionMapper` 转换为 Iceberg REST 规范的 HTTP 响应。

---

## 3. 依赖变更 (POM)

### parent pom (`iceberg/pom.xml`)

| 操作 | 内容 |
|------|------|
| 删除 properties | `jetty.version`, `jersey.version`, `servlet.version`, `jaxrs-api.version` |
| 新增 property | `<spring-boot.version>3.3.0</spring-boot.version>` |
| 升级 | `<junit.version>` 5.8.1 → **5.10.2** (Spring Boot 3.3 要求) |
| 新增 BOM | `spring-boot-dependencies` (import, type=pom) |
| 删除 dependencyManagement | 全部 Jetty / Jersey / `jakarta.ws.rs-api` / `javax.servlet-api` / `metrics-jersey2` / `jersey-test-framework` 条目 |

### module pom (`iceberg-rest-server/pom.xml`)

| 操作 | 内容 |
|------|------|
| 删除 | 全部 Jetty / Jersey / `javax.servlet-api` 依赖、`jersey-test-framework` |
| 新增 | `spring-boot-starter-web`、`spring-boot-starter-aop`、`spring-boot-starter-test` (test scope) |

---

## 4. Spring 基础设施（6 个新文件）

### 4.1 `IcebergBeanConfig` — 中央配置（替代 HK2 AbstractBinder）

```
路径: service/spring/IcebergBeanConfig.java
注解: @Configuration + @EnableAutoConfiguration + @ComponentScan("org.apache.gravitino.iceberg")
```

**职责：** 装配所有服务级 Bean，替代旧 `RESTService.initServer()` 的手动 DI。

**关键 @Bean 方法链：**

```
Properties (strip "gravitino.iceberg-rest." prefix from System.getProperties())
  └→ IcebergConfig
      ├→ IcebergConfigProvider (Static 或自定义 class)
      │     └→ getDefaultCatalogName() = "default_catalog"
      ├→ EventListenerManager → EventBus
      ├→ IcebergCatalogWrapperManager
      ├→ IcebergMetricsManager
      └→ IcebergAuthorizer (AllowAll 或 OPA)

IcebergTableOperationExecutor(wrapperManager)
  └→ IcebergTableEventDispatcher(executor, eventBus)
     (View / Namespace 同构)

ObjectMapper (@Primary, = IcebergObjectMapper.getInstance())
```

**ServerContext 初始化（关键设计）：**

不能在 `@Configuration` 类的 `@PostConstruct` 中调用同一类的 `@Bean` 方法（CGLIB 代理导致 `BeanCurrentlyInCreationException`）。解决方案：将初始化逻辑提取到内部 `@Component` 类，通过构造器注入所需 Bean。

```java
@Component
static class ServerContextInitializer {
    // 构造器注入 authorizer, catalogWrapperManager, configProvider,
    //              metricsManager, eventListenerManager

    @PostConstruct
    void init() {
        ServerContext.reset();
        ServerContext.initialize(authorizer, catalogWrapperManager,
            configProvider.getDefaultCatalogName());
    }

    @PreDestroy
    void shutdown() throws Exception {
        metricsManager.close();
        eventListenerManager.stop();
        catalogWrapperManager.close();
        configProvider.close();
    }
}
```

### 4.2 `IcebergRestServerApplication` — 启动类

```java
public class IcebergRestServerApplication {
    public static void main(String[] args) {
        SpringApplication.run(IcebergBeanConfig.class, args);
    }
}
```

**关键点：** 故意不加 `@SpringBootApplication`，避免与测试类 `IcebergTestApp` 的 `@SpringBootConfiguration` 冲突。`@EnableAutoConfiguration` 和 `@ComponentScan` 放在 `IcebergBeanConfig` 上。

### 4.3 `IcebergWebMvcConfig` — Web MVC 配置

- 实现 `WebMvcConfigurer`
- 禁用尾斜杠匹配 (`setUseTrailingSlashMatch(false)`)
- 配置 CORS（allowedOriginPatterns `"*"`，methods GET/POST/HEAD/DELETE/PUT）

### 4.4 `IcebergTomcatConfig` — Tomcat 定制

```java
@Bean
WebServerFactoryCustomizer<TomcatServletWebServerFactory> tomcatCustomizer() {
    return factory -> factory.addConnectorCustomizers(connector -> {
        connector.setProperty("encodedSolidusHandling", "passthrough");
    });
}
```

`encodedSolidusHandling=passthrough` 防止 Tomcat 对 `%2F`（编码斜杠）做特殊解码，保持与 Iceberg namespace 编码兼容。

### 4.5 `IcebergAuthorizationAspect` — 鉴权切面

```java
@Aspect @Component
public class IcebergAuthorizationAspect {
    private final IcebergMetadataAuthorizationMethodInterceptor interceptor =
        new IcebergMetadataAuthorizationMethodInterceptor();

    @Around("@annotation(op)")
    public Object check(ProceedingJoinPoint pjp, IcebergAuthorizationOperation op) throws Throwable {
        interceptor.authorize(
            ((MethodSignature) pjp.getSignature()).getMethod(),
            pjp.getArgs());
        return pjp.proceed();
    }
}
```

鉴权失败时抛出 `ForbiddenException`，由全局异常处理器捕获返回 403。

### 4.6 `IcebergGlobalExceptionHandler` — 全局异常处理

```java
@ControllerAdvice
public class IcebergGlobalExceptionHandler {
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Object> handle(Exception ex) {
        return IcebergExceptionMapper.toResponseEntity(ex);
    }
}
```

---

## 5. Controller 层迁移（6 个文件）

### 5.1 注解映射对照

| JAX-RS | Spring MVC |
|--------|-----------|
| `@Path("/v1/{prefix:(...)?}...")` + `@Produces` + `@Consumes` | `@RestController` + `@RequestMapping(path = {...}, produces = JSON)` |
| `@GET` / `@POST` / `@DELETE` | `@GetMapping` / `@PostMapping` / `@DeleteMapping` |
| HEAD（JAX-RS 无专用注解） | `@RequestMapping(value = "{table}", method = RequestMethod.HEAD)` |
| `@PathParam("x")` | `@PathVariable("x")` |
| `@QueryParam("x")` + `@DefaultValue("y")` | `@RequestParam(name = "x", defaultValue = "y")` |
| `@HeaderParam("x")` | `@RequestHeader("x")` |
| `@Context HttpServletRequest` 字段注入 | `HttpServletRequest` 作为方法参数 |
| `@Inject` 构造器 | 构造器（Spring 自动注入单构造器） |
| `javax.ws.rs.core.Response` | `ResponseEntity<Object>` |
| `javax.ws.rs.core.MediaType` | `org.springframework.http.MediaType` |
| `javax.servlet.*` | `jakarta.servlet.*` |
| Dropwizard `@Timed` / `@ResponseMetered` | 删除 |
| `@Encoded` | 删除（Tomcat 层面统一处理） |

### 5.2 双路径映射策略

JAX-RS 的 `{prefix:([^/]*/)?}` 匹配空字符串或 `catalog/`。Spring MVC 用双路径声明替代：

```java
@RestController
@RequestMapping(
    path = {
        "/v1/namespaces/{namespace}/tables",            // 无 prefix
        "/v1/{prefix}/namespaces/{namespace}/tables"     // 有 prefix
    },
    produces = MediaType.APPLICATION_JSON_VALUE)
public class IcebergTableOperations {

    @GetMapping("{table}")
    public ResponseEntity<Object> loadTable(
        @PathVariable(value = "prefix", required = false) String prefix,
        @PathVariable("namespace") String namespace,
        @PathVariable("table") String table,
        ...) { ... }
}
```

**匹配规则：** Spring MVC "最具体匹配优先"。字面量路径优先于变量路径，无 prefix 的路径和有 prefix 的路径不会冲突。

### 5.3 `consumes` 约束（重要）

**类级 `@RequestMapping` 不设 `consumes`。** GET/HEAD/DELETE 请求不发送 `Content-Type` 头，如果类级设了 `consumes = APPLICATION_JSON_VALUE`，这些请求返回 415。

### 5.4 六个 Controller 端点清单

| Controller | 端点数 | 方法 |
|-----------|-------|------|
| `IcebergTableOperations` | 9 | listTables, createTable, loadTable, updateTable, dropTable, tableExists(HEAD), reportMetrics, registerTable, getTableCredentials |
| `IcebergNamespaceOperations` | 7 | listNamespaces, createNamespace, loadNamespace, namespaceExists(HEAD), dropNamespace, updateNamespace, registerTable |
| `IcebergViewOperations` | 6 | listView, createView, loadView, replaceView, dropView, viewExists(HEAD) |
| `IcebergConfigOperations` | 1 | getConfig (无鉴权) |
| `IcebergTableRenameOperations` | 1 | renameTable |
| `IcebergViewRenameOperations` | 1 | renameView |

---

## 6. 支持类改造

### 6.1 `PrefixResolver`

```java
public static String getCatalogName(String rawPrefix) {
    if (StringUtils.isBlank(rawPrefix)) {
        return ServerContext.getInstance().getDefaultCatalogName();
    }
    return rawPrefix;
}
```

**变更点：** Jersey 的 prefix 路径变量包含尾斜杠（`"catalog/"`），需要 strip。Spring MVC 的 `@PathVariable` 不包含尾斜杠，直接返回原始值。`rawPrefix` 为 `null` 时（无 prefix 路径匹配），返回 `ServerContext` 中的默认 catalog 名。

### 6.2 `IcebergMetadataAuthorizationMethodInterceptor`

**变更点：**
- 删除 `implements MethodInterceptor`（aopalliance 接口）
- 新增 `public void authorize(Method method, Object[] args)` 供 AOP 切面调用
- 鉴权失败改为 `throw ForbiddenException` 而非返回 Response

**关键 bug 修复（null prefix）：**

```java
// 修复前（bug）：
String value = String.valueOf(args[i]);  // null → "null" 字符串
// PrefixResolver.getCatalogName("null") → 返回 "null" 而非默认 catalog

// 修复后：
String value = args[i] == null ? null : String.valueOf(args[i]);
```

`extractNameIdentifierFromParameters` 遍历带 `@AuthorizationMetadata` 注解的参数，按 `Entity.EntityType`（CATALOG / SCHEMA / TABLE / VIEW）提取 `NameIdentifier`，再委托 `IcebergAuthorizer.checkOperation()` 鉴权。

### 6.3 `HttpResponseBuilder`

新增返回 `ResponseEntity<Object>` 的方法：

| 方法 | HTTP 状态 |
|------|----------|
| `okEntity(T entity)` | 200 + JSON body |
| `noContentEntity()` | 204 |
| `notExistsEntity()` | 404 |
| `errorEntity(Throwable, int status)` | 自定义 + ErrorResponse body |

旧 `Response` 返回方法保留为 `@Deprecated` 委托。

### 6.4 `IcebergExceptionMapper`

- 删除 `implements ExceptionMapper<Exception>` 和 `@Provider`
- 删除 `toRESTResponse(Throwable)`，新增 `toResponseEntity(Throwable)` → `ResponseEntity<Object>`
- 保留异常类 → HTTP 状态码映射 Map（`EXCEPTION_ERROR_CODES`）

```
IllegalArgumentException → 400    NotAuthorizedException → 401
ForbiddenException       → 403    NoSuchNamespaceException → 404
NoSuchTableException     → 404    NoSuchCatalogException   → 404
AlreadyExistsException   → 409    UnsupportedOperationException → 406
```

### 6.5 `IcebergRESTServer` (main 入口)

保留 `.conf` 文件加载逻辑 → 设置为 system properties → `SpringApplication.run(IcebergRestServerApplication.class, args)`。

---

## 7. ServerContext 单例

线程安全的全局持有器，存储 authorizer、catalog wrapper manager 和默认 catalog 名。

```
ServerContext (volatile singleton)
├── IcebergAuthorizer authorizer
├── IcebergCatalogWrapperManager catalogWrapperManager
└── String defaultCatalogName
```

- `initialize()` 在 `ServerContextInitializer.@PostConstruct` 中调用
- `reset()` 仅用于测试
- `PrefixResolver` 和 `IcebergRESTUtils` 通过 `getInstance()` 访问

---

## 8. 请求处理链路（以 loadTable 为例）

```
GET /v1/namespaces/{ns}/tables/{table}
  │
  ▼
DispatcherServlet → 路由匹配
  │  匹配: /v1/namespaces/{namespace}/tables/{table}
  │  prefix = null (无 prefix 路径)
  ▼
IcebergAuthorizationAspect.check()   ← AOP @Around
  │  interceptor.authorize(method, args)
  │  → extractNameIdentifierFromParameters:
  │    prefix=null → PrefixResolver → "default_catalog"
  │    namespace → decodeNamespace → schema
  │    table → decodeString → tableName
  │  → AllowAllAuthorizer.checkOperation() → true
  ▼
IcebergTableOperations.loadTable()
  │  catalogName = PrefixResolver.getCatalogName(null) = "default_catalog"
  │  context = new IcebergRequestContext(request, catalogName)
  │  tableOperationDispatcher.loadTable(context, tableIdentifier)
  ▼
IcebergTableEventDispatcher.loadTable()
  │  fire PreEvent → executor.loadTable() → fire PostEvent
  ▼
IcebergTableOperationExecutor.loadTable()
  │  catalogWrapperManager.getCatalogWrapper("default_catalog")
  │  → Caffeine cache miss → createCatalogWrapper()
  │  → configProvider.getIcebergCatalogConfig("default_catalog")
  │  → new CatalogWrapperForREST(catalogName, config)
  │  wrapper.loadTable(identifier)
  ▼
返回 LoadTableResponse → HttpResponseBuilder.okEntity()
  │
  ▼
ResponseEntity<Object> {200, JSON} → 客户端
```

---

## 9. 删除的文件

| 文件 | 原因 |
|------|------|
| `iceberg/RESTService.java` | 被 IcebergBeanConfig + Spring Boot 替代 |
| `service/IcebergObjectMapperProvider.java` | JAX-RS ContextResolver 不再需要 |
| `service/authorization/interceptor/IcebergRESTAuthInterceptionService.java` | HK2 InterceptionService 被 Spring AOP 替代 |
| `server/JettyServer.java` | 手动 Jetty 管理被 Spring Boot 内嵌服务器替代 |
| `server/JettyServerConfig.java` | 被 Spring Boot `server.*` 属性替代 |
| `server/CorsFilterHolder.java` | CORS 由 Spring WebMvcConfigurer 配置 |
| `metrics/MetricNames.java` | Dropwizard 指标名常量 |

---

## 10. 测试架构

### 10.1 测试类层次

```
IcebergRestTestBase (@SpringBootTest + @AutoConfigureMockMvc)
├── MockMvc mockMvc        ← @Autowired
├── ObjectMapper           ← IcebergObjectMapper.getInstance()
├── doGet / doPost / doDelete / doHead
├── getTablePath / getViewPath / getNamespacePath / getConfigPath / ...
└── maybeInjectPrefix(path)  ← 测试 prefix 路径
    ├── IcebergNamespaceTestBase
    │   ├── verifyCreateNamespaceSucc / Fail
    │   ├── verifyLoadNamespaceSucc / Fail
    │   ├── verifyDropNamespaceSucc / Fail
    │   ├── verifyListNamespaceSucc / Fail
    │   ├── verifyUpdateNamespaceSucc / Fail
    │   ├── verifyRegisterTableSucc / Fail
    │   └── dropAllExistingNamespace()  ← 嵌套清理
    ├── TestSpringIcebergConfig (4 tests)
    ├── TestSpringIcebergNamespaceOperations (7 tests)
    ├── TestSpringIcebergTableOperations (5 tests)
    └── TestSpringIcebergViewOperations (4 tests)
```

### 10.2 `IcebergTestApp` — 测试专用配置

```
@SpringBootApplication + @TestConfiguration
@ComponentScan(basePackages = "org.apache.gravitino.iceberg",
    excludeFilters = {IcebergBeanConfig.class, IcebergRestServerApplication.class,
                      IcebergBeanConfig.ServerContextInitializer.class})
```

排除生产配置，提供 `@Primary` 测试 Bean 覆盖：

| @Bean | 用途 |
|-------|------|
| `icebergObjectMapper` (@Primary) | Iceberg ObjectMapper（默认 Spring Boot ObjectMapper 无法序列化 Iceberg 响应类） |
| `icebergTestConfig` | 空 IcebergConfig |
| `icebergTestConfigProvider` (@Primary) | StaticIcebergConfigProvider，配置 `prefix_gravitino` catalog + S3FileIO |
| `icebergTestCatalogWrapperManager` (@Primary) | IcebergCatalogWrapperManagerForTest（内存 catalog） |
| `icebergTestMetricsManager` (@Primary) | DummyMetricsStore |
| `eventBus` | 空 EventBus |
| `icebergTestTableDispatcher` (@Primary) | Table EventDispatcher |
| `icebergTestViewDispatcher` (@Primary) | View EventDispatcher |
| `icebergTestNamespaceDispatcher` (@Primary) | Namespace EventDispatcher |
| `TestServerContextInitializer` (@Component) | 初始化 ServerContext（AllowAllAuthorizer） |

### 10.3 测试隔离

Spring `@SpringBootTest` 跨测试类共享同一 Application Context（共享内存 catalog）。`dropAllExistingNamespace()` 负责清理：

1. 列出所有 namespace
2. 按深度降序排序（子 namespace 先于父 namespace 删除）
3. 每个 namespace：先删 tables → 再删 views → 最后删 namespace 本身
4. 重试最多 3 轮（处理层级依赖）
5. 删除失败时 catch 并忽略（下一轮重试）

---

## 11. 迁移中遇到的关键问题及解决方案

| # | 问题 | 根因 | 解决方案 |
|---|------|------|---------|
| 1 | `NoSuchMethodError: ExtensionContext.getExecutableInvoker()` | JUnit 5.8.1 与 Spring Boot 3.3 不兼容 | `junit.version` 升级到 5.10.2 |
| 2 | `Found multiple @SpringBootConfiguration` | 生产 `IcebergRestServerApplication` 和测试 `IcebergTestApp` 都有 `@SpringBootApplication` | 生产类去掉 `@SpringBootApplication`，改为纯启动类调用 `SpringApplication.run(IcebergBeanConfig.class)` |
| 3 | `BeanCurrentlyInCreationException` | `@PostConstruct` 调用同 config 类的 `@Bean` 方法触发 CGLIB 循环引用 | 提取 `ServerContextInitializer` 为独立 `@Component`，构造器注入 |
| 4 | HTTP 415 (Unsupported Media Type) | 类级 `consumes = JSON` 导致 GET/HEAD/DELETE 无 Content-Type 时被拒绝 | 类级 `@RequestMapping` 移除 `consumes` |
| 5 | `HttpMessageNotWritableException: No converter for ConfigResponse` | 测试 context 缺少 Iceberg ObjectMapper bean | `IcebergTestApp` 添加 `@Primary ObjectMapper` bean |
| 6 | `NoSuchCatalogException: catalog null` | `String.valueOf(null)` 返回 `"null"` 字符串而非 Java null | `args[i] == null ? null : String.valueOf(args[i])` |
| 7 | `NamespaceNotEmptyException` (409) | `dropAllExistingNamespace()` 未先删 tables | 新增 `dropAllTablesInNamespace` / `dropAllViewsInNamespace`，按深度排序 + 多轮重试 |

---

## 12. javax.* 清理验证

迁移后 `src/` 中仅保留 `javax.annotation.Nullable`（明确允许）。无 `javax.ws.rs`、`javax.servlet`、`javax.inject`、`com.codahale`、`javax.validation` 残留。

```
# 验证命令
grep -rn "import javax\." iceberg-rest-server/src/ | grep -v "javax.annotation.Nullable"
# 期望: 无输出
```

---

## 13. 完整测试结果

```
Tests run: 141, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

包括：
- 4 个 Spring MockMvc 测试类（20 tests）
- 鉴权拦截器测试（4 tests）
- OPA authorizer 测试（8 tests）
- Prefix resolver / exception mapper / catalog wrapper 测试
- 事件总线 / credential provider / metrics 测试
