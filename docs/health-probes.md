# 应用与依赖拨测

本服务已包含 Spring Boot Actuator。拨测复用其内置健康检查，不新增聊天 Controller、业务 SQL、
线程池或定时任务，也不调用 Intent、DomainAgent 或 Relay。本说明中的地址使用默认 Actuator 路径；
网关存在前缀时由部署方映射，不更改聊天业务 OpenAPI。

## 1. 配置与启用

仓库 `application.yml` 保持数据库、Redis 健康检查默认关闭。由配置中心或部署配置统一提供以下
**完整属性**，覆盖默认值后重启实例生效。本轮不提供动态刷新，不自动修改配置中心。

```properties
management.endpoint.health.probes.enabled=true
management.endpoint.health.group.readiness.include=readinessState
management.endpoint.health.group.readiness.show-details=never
management.endpoint.health.group.readiness.show-components=never

management.health.db.enabled=true
management.health.redis.enabled=true
management.endpoint.health.group.dependencies.include=db,redis
management.endpoint.health.group.dependencies.show-details=never
management.endpoint.health.group.dependencies.show-components=always
```

| 配置 | 作用 |
|---|---|
| `probes.enabled=true` | 在非 Kubernetes 环境也启用 readiness/liveness 探针，不启动主动拨测任务 |
| `readiness.include=readinessState` | 就绪分组只读取应用生命周期状态，不检查数据库、Redis |
| `management.health.db/redis.enabled=true` | 注册内置依赖检查器，不是启用业务数据库或 Redis 的开关 |
| `dependencies.include=db,redis` | 定义只包含两个依赖的健康分组 |
| `show-details=never` | 不向响应暴露版本、数据库产品信息或异常详情，不改变服务端原有异常日志 |
| `show-components=always/never` | 控制是否显示组件名称及状态，不是访问权限或检查执行开关 |

部署时核对最终生效属性，避免其他配置源覆盖上述值。保留默认的健康分组成员校验，不设置
`management.endpoint.health.validate-group-membership=false`；声明的 db 或 redis 检查器缺失时应启动失败，
不能将漏检当成健康。数据库 DataSource 和 RedisConnectionFactory 必须正常装配。

仅使用原有 health HTTP 暴露能力，不配置 `management.endpoints.web.exposure.include=*`。
如果部署已经排除了 health，需要由部署方显式恢复 health 暴露；这些属性不会绕过网关或安全规则。

停用时统一撤销本节覆盖配置并重启，恢复仓库默认行为。不能只关闭 db/redis 而保留
`dependencies.include=db,redis`，否则成员校验会失败。仓库未启用 probes 时，Kubernetes 环境仍可能
自动启用可用性探针，不应将撤销配置等同于所有环境都删除 readiness/liveness 地址。

## 2. HTTP 契约

| 地址 | 检测范围 | 正常 | 异常 |
|---|---|---|---|
| `GET /actuator/health/readiness` | HTTP 请求可处理且应用处于接受流量状态 | 200 / UP | 拒绝流量状态为 503 / OUT_OF_SERVICE；不可达由拨测端判失败 |
| `GET /actuator/health/dependencies` | 当前实例访问数据库及 Redis 的检查结果 | 200 / UP | 任一依赖 DOWN 时为 503 / DOWN |

readiness 正常响应：

```json
{"status":"UP"}
```

dependencies 正常响应：

```json
{
  "status": "UP",
  "components": {
    "db": {"status": "UP"},
    "redis": {"status": "UP"}
  }
}
```

例如 Redis 异常，HTTP 503 响应：

```json
{
  "status": "DOWN",
  "components": {
    "db": {"status": "UP"},
    "redis": {"status": "DOWN"}
  }
}
```

上述依赖示例对应当前单 DataSource、单 RedisConnectionFactory 装配。增加多个连接工厂或数据源后，
组件可能出现子级结构，需重新核对监控规则。

**开启 db/redis 后，根 `/actuator/health` 也会检查它们。** 不要继续将根接口当作无外部依赖的存活检查，
也不要让多个监控系统同时高频请求根接口和 dependencies，重复放大依赖访问。
本配置还启用 `/actuator/health/liveness`，但不因此自动配置平台重启策略。

## 3. 检测实现及资源边界

- 数据库复用业务 DataSource，获取连接、读取产品元信息并验证连接。当前未配置校验 SQL 时，
  Spring Boot 3.4.6 调用 JDBC `Connection.isValid(0)`，具体通信由驱动实现，不假定一定执行 `SELECT 1`。
  如果部署配置了连接校验 SQL，检查器会使用该 SQL。检查结束通过 Spring JDBC 释放连接；不保证每次拨测只借用一次连接。
- Redis 复用 `financeex.redis.*` 对应的连接工厂。按仓库当前 `RedisConnectionFactory` Bean 声明，
  同步检查器的单机命令为 `INFO`、集群命令为 `CLUSTER INFO`，不是简单 `PING`。
  如果部署提供了可在自动装配时识别的响应式工厂 Bean，则优先使用响应式检查器，单机命令为 `INFO server`。
  连接、鉴权、命令异常或集群失败状态会导致 DOWN，账号 ACL 必须允许检查命令。
- 不扫描 Session、Message 等业务表，不遍历或写入 Redis 业务 Key。百万或千万条业务记录不会直接使检查变成全表扫描。
- 请求仍消耗 HTTP 处理资源、临时对象、网络和共享数据库/Redis 资源。仓库 Hikari 上限默认 10，部署可覆盖；
  获取和验证连接期间可能与业务竞争。正常完成释放资源不等于故障时能够立即释放。
- Hikari 默认 500ms 仅为连接获取等待期限，不是整个检查期限；`isValid(0)`本身未指定验证超时。
  Redis 使用现有连接和命令超时。Actuator 慢检查告警阈值不是执行超时，本轮不增加服务端整体硬期限或并发隔离。
- 拨测客户端超时不保证服务端 JDBC/Redis 检查立即停止。依赖长期阻塞时，即使客户端没有重叠请求，
  服务端仍可能积压；上线前需验证实际驱动网络超时、连接释放和故障负载，不能承诺零性能影响或固定资源上限。
- UP 不证明业务表权限、写入事务、所有 Redis 节点、Pub/Sub 订阅或聊天全链路正常；压测前仍需独立业务 smoke test。

## 4. 监控平台接入

使用业务服务端口，不另开管理端口规避业务端口故障。通过网关来源白名单或网络策略仅允许监控访问，
不得向公网无约束暴露。禁用网关对这些响应的缓存；网关认证、限流及路径映射由部署方核验。

| 检查 | 每实例初始频率 | 客户端超时 |
|---|---|---|
| readiness | 30 秒一次 | 3 秒 |
| dependencies | 60 秒一次 | 5 秒 |

多实例错峰，同实例不并发重复拨测，失败不立即密集重试。连续 3 次失败告警、连续 2 次成功恢复，
不因一次依赖故障自动重启或摘除全部实例。这些是**监控平台配置**，不是上述 Spring 属性产生的后台动作。
实例级检查应逐实例定位；只访问负载均衡入口不能证明每个实例都健康。

```bash
BASE_URL=https://monitoring.example.com
curl --silent --show-error --fail-with-body --max-time 3 "$BASE_URL/actuator/health/readiness"
curl --silent --show-error --fail-with-body --max-time 5 "$BASE_URL/actuator/health/dependencies"
```

命令仅供人工单次检查，不构成定时任务。平台成功条件必须同时核对 HTTP 200 和顶层 `status=UP`；
dependencies 还须有 db、redis 且两者均为 UP。404、认证失败、非 JSON、组件缺失、UNKNOWN 和请求超时均不能判成功。

先在测试环境核对配置覆盖、200/503、详情隐藏、连接池等待、接口延迟及失败日志，再低频受控启用。
真实数据库/Redis 故障及网关接入另行联调，本次自动测试不替代目标环境容量验证。

## 5. 参考

- [Spring Boot 3.4 健康检查与分组](https://docs.spring.io/spring-boot/3.4/reference/actuator/endpoints.html)
- [JDK 21 Connection.isValid](https://docs.oracle.com/en/java/javase/21/docs/api/java.sql/java/sql/Connection.html#isValid(int))
- [单实例压测指导](performance-testing-guide.md)
