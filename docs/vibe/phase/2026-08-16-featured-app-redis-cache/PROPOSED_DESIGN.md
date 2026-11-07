# 精选应用 Redis 缓存设计

## 目标

为 `POST /app/list/page/featured` 增加 Redis 旁路缓存，只缓存第 1 至第 10 页的精选应用分页结果，缓存有效期为 10 分钟。缓存命中直接返回，未命中查询数据库并回填 Redis，不在应用新增、更新或删除时主动清理缓存。

## 已确认的接口语义

- `@Cacheable` 直接添加在 `AppController.listFeaturedAppVOByPage` 方法上。
- 当第 1 至第 10 页的查询命中缓存时，Spring Cache 在进入方法体前返回缓存结果，因此允许未登录用户读取已经缓存的精选应用。
- 当缓存未命中时，仍执行方法体内现有的 `userService.getLoginUser(request)`；未登录用户不能触发数据库回源或缓存填充。
- 第 11 页及之后不使用缓存，始终执行现有数据库查询逻辑和登录校验。

## 缓存数据流

1. 请求进入精选应用分页接口。
2. Spring Cache 判断 `1 <= pageNum <= 10`。
3. 不在缓存范围内时，直接执行 Controller 方法体并查询数据库。
4. 在缓存范围内时，将完整 `AppQueryRequest` 转换成稳定 JSON，再计算 SHA-256，得到固定 64 位十六进制哈希。
5. 使用 `featuredApp:<hash>` 作为逻辑缓存键，并由 CacheManager 添加应用级 Redis 前缀。
6. Redis 命中时直接反序列化并返回 `BaseResponse<Page<AppVO>>`。
7. Redis 未命中时执行登录校验、数据库分页查询和 VO 封装，成功结果写入 Redis 后返回。
8. 缓存 10 分钟后自然过期；应用写操作不执行主动删除。

## 组件与修改范围

### 缓存键工具

在 `utils` 包新增 `CacheKeyUtils`：

- 接收任意复杂对象。
- 使用项目统一的 Jackson `ObjectMapper` 将对象转换为 JSON。
- 对 JSON 的 UTF-8 字节计算 SHA-256。
- 输出固定 64 位小写十六进制字符串。
- 序列化或摘要计算失败时抛出明确的状态异常，避免产生不稳定缓存键。

### Spring Cache 启用

在 Spring Boot 启动类添加 `@EnableCaching`，启用 Spring Cache AOP。

### Redis CacheManager

新增缓存配置类：

- 使用现有 Spring Redis 连接配置和 `RedisConnectionFactory`。
- 配置 `RedisCacheManager`。
- 默认 TTL 为 10 分钟。
- Redis key 使用字符串序列化。
- Redis value 使用支持类型信息的 JSON 序列化，以便恢复 `BaseResponse<Page<AppVO>>`、`LocalDateTime` 和嵌套 VO。
- 禁止缓存 null。
- 添加 `zero-code:cache:` 应用前缀，避免与 Session、AI ChatMemory 等 Redis key 冲突。

### 精选应用接口注解

在 `AppController.listFeaturedAppVOByPage` 添加 `@Cacheable`：

- cache name：`featuredApp`。
- key：调用 `CacheKeyUtils` 对 `AppQueryRequest` 生成 SHA-256。
- condition：页码必须处于 `[1, 10]`。
- 不添加 `@CacheEvict`。

## Redis 配置检查

复用当前 `spring.data.redis.host`、`port`、`password` 和现有 Redis 依赖。开发验证时检查：

- Spring 能创建 `RedisConnectionFactory`。
- Redis `PING` 正常。
- 精选应用缓存 key 使用独立前缀。
- Spring Session 与 AI ChatMemory 的 Redis 使用不受影响。

## 测试策略

### 自动化测试

1. `CacheKeyUtils` 对相同对象生成相同的 64 位哈希。
2. 查询条件不同时生成不同哈希。
3. 精选应用第 1 至第 10 页相同查询执行两次时，第二次命中缓存，数据库分页服务只执行一次。
4. 第 11 页相同查询执行两次时，两次都查询数据库。
5. 验证缓存配置的 TTL 为 10 分钟、null 不缓存、key 前缀正确。
6. 验证缓存命中可以在不执行 Controller 方法体的情况下返回数据。

测试遵循红—绿—重构：先写会因缓存组件缺失而失败的测试，再添加最小实现使测试通过。

### 性能验证

在本地 MySQL、Redis 和后端可用且具备有效登录 Cookie 时，对同一个前 10 页查询进行测量：

- 冷缓存：清理该测试 key 后请求一次，记录数据库查询与 VO 组装耗时。
- 热缓存：使用完全相同的请求连续调用多次，记录 Redis 命中耗时。
- 分别计算冷缓存和热缓存样本的中位响应时间。
- 性能倍数按 `冷缓存中位耗时 / 热缓存中位耗时` 计算。

性能数字只代表本机当前数据规模和网络环境，不作为固定 SLA。

## 非目标

- 不缓存个人应用列表或管理员应用列表。
- 不缓存第 11 页及之后的数据。
- 不实现写操作主动失效。
- 不引入分布式锁、缓存预热或热点 key 保护。
- 不修改精选应用查询结果的业务排序、过滤或权限逻辑。
