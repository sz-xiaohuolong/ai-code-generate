# 平台安全性、稳定性与成本优化设计

## 目标

按照参考文章为 AI 代码生成主链路补充分布式流量保护、Prompt 安全护轨、模型调用稳定性控制和低成本智能路由，同时保持现有 SSE 实时输出能力。

## 安全性

### Redisson 分布式限流

- 使用 Redisson 3.50.0 的 `RRateLimiter` 实现跨实例共享的令牌桶。
- 所有限流代码位于 `ratelimit` 包，提供 `API`、`USER`、`IP` 三种维度和声明式 `@RateLimit` 注解。
- AI 对话接口按用户限制为 60 秒 5 次；未登录用户自动降级到 IP 维度。
- 限流器使用 1 小时 keep-alive，避免动态用户/IP key 永久占用 Redis。
- 超限抛出错误码 `42900`，普通接口返回 JSON，SSE 接口返回 `business-error` 和 `done` 事件。

### Prompt 安全审查

- `PromptSafetyInputGuardrail` 在请求发送给模型前拒绝空输入、超过 1000 字的输入、敏感关键词和常见注入模式。
- 输入护轨应用于代码生成服务和生成类型路由服务。
- 护轨异常通过 SSE 业务错误事件返回准确提示，前端负责展示并关闭连接。

## 稳定性

- 默认同步模型、默认流式模型和推理流式模型最多重试 3 次。
- `RetryOutputGuardrail` 对空响应、短响应和包含敏感凭证信息的同步响应执行最多 3 次 reprompt。
- 输出护轨仅通过方法注解应用于同步生成方法；流式方法不应用输出护轨，避免完整缓冲后一次性返回。
- Vue 工具调用使用 `maxToolCallingRoundTrips(20)`，防止模型陷入无限工具循环；不新增退出工具，控制系统复杂度。

## 成本

- 创建独立 `routingChatModel`，默认使用 DashScope OpenAI 兼容地址、`qwen-turbo` 和 100 max tokens。
- 路由模型密钥通过 `DASHSCOPE_API_KEY` 提供，不写入仓库。
- 未配置密钥时记录警告并回退现有主模型，保证本地开发和部署启动不被阻断。
- `AiCodeGenTypeRoutingService` 只使用路由模型，代码生成仍使用质量更高的主模型。

## 测试

- 单元测试覆盖限流 key、超限异常、限流器 TTL 配置、SSE 业务错误格式、输入护轨和输出护轨。
- 工厂/配置测试覆盖路由模型选择和模型参数绑定。
- 运行后端相关回归测试、Maven 打包、前端 TypeScript 检查和生产构建。

## 边界

- `X-Forwarded-For` 只有在部署层正确清洗代理头时才可信。
- 静态 Prompt 规则是基础防线，不能替代专业内容安全服务。
- 路由成本优化在提供 `DASHSCOPE_API_KEY` 后启用；没有凭据时只能验证配置与回退行为，不能进行真实外部模型计费测试。
