# ai-operations-gateway 项目定位

## 一、平台整体模块划分

平台按用户工作台、运营控制面、基础域、运行时引擎和治理分析域拆分。下表只说明职责，建设状态由各模块自己的定位文档维护。

| 模块 | 平台职责 |
| --- | --- |
| `ai-base` | 用户、个人空间、企业租户、组织及租户侧角色、菜单、权限基础域（非运营角色） |
| `ai-operations` | 独立运营身份与权限、平台初始化编排与运营配置 |
| `ai-admin-gateway` | 用户租户入口、认证、路由与追踪 |
| `ai-operations-gateway` | 无租户运营入口、认证、路由与追踪 |
| `ai-admin-web` | 用户 AI 工作台兼企业智能管理平台 |
| `ai-operations-web` | 运营控制台 |
| `ai-agent` | 多 LLM 平台协议适配 |
| `ai-mcp` | 内置工具与外部 MCP Server 接入 |
| `ai-orchestration` | Agent 任务编排与 ReAct 循环 |
| `ai-memory` | 会话上下文与长期记忆 |
| `ai-knowledge` | 知识库与 RAG |
| `ai-eval` | 模型、Agent 及其调用质量评测 |
| `ai-experiment` | 实验分流与版本对比 |
| `ai-analysis` | 传统规则扫描与 AI 代码分析 |

## 二、项目背景与定位

`ai-operations-gateway` 是平台运营人员的独立 HTTP 入口，链路为 `ai-operations-web` → `ai-operations-gateway` → `ai-operations`。业务端口为 8890，独立管理端口为 8891；与用户网关分别维护路由、凭据、配置及会话访问范围。

运营身份没有租户概念：本网关只接受 `ai-operations` 签发的 `ops` 会话，不要求 `X-Tenant-Id`，不注入 `X-Internal-Tenant-Id`，不将运营人员转换成租户用户。用户个人空间、企业工作台和 Agent 建设入口归 `ai-admin-web` 与 `ai-admin-gateway`。

网关负责认证、可信上下文及流量治理，不保存运营业务数据，不决定运营角色、资源权限或用户 Agent 的发布规则；这些由运营服务与相关领域服务执行。

## 三、本模块功能定位

- 通过独立 Nacos 路由代理运营接口，当前只配置 `/ai-operations/**` → `lb://ai-operations`，保持完整路径；
- 校验运营 Redis Opaque Session，要求 `clientType=ops`，仅读取 `ai-admin:ai-operations:auth:*` 会话与索引；
- 删除客户端 Token、伪造用户/会话/租户身份头及 `X-Client-Type`、`X-Internal-Client-Type`，仅重建可信用户与会话身份头；
- 认证通过后按会话策略续期，先续索引，再比较原会话内容并续主会话；索引失败或会话变化时拒绝续期；
- 解析或生成 W3C TraceId、维护调用链与 Reactor Context / MDC，记录不含敏感内容的访问摘要；
- 按路由和 TCP 直连 IP 限流，并执行路由级熔断与最终下游请求头裁剪；
- 热更新运营侧路由、白名单、限流、熔断、请求头透传范围和调用链限制。

## 四、职责边界与模块协作

| 协作模块 | 本项目负责 | 对方负责 |
| --- | --- | --- |
| `ai-operations-web` | 运营 API 路由、认证及可信上下文 | 运营登录与控制台页面，不经用户网关绕过运营接口 |
| `ai-operations` | 校验和续期运营会话，透传可信 `X-Internal-User-Id` 与 `X-Internal-Session-Id` | 会话签发/撤销、运营角色权限、初始化编排、运营配置及受控跨域调用 |
| `ai-admin-gateway` | 保持无租户运营入口独立 | 校验 `usr` 会话及用户租户上下文，承接个人/企业工作台流量 |
| 各领域模块 | 不直接作为运营浏览器的默认路由出口 | 通过与 `ai-operations` 的服务契约校验业务对象和权限，管理自身权威数据 |

运营请求可以包含待处理租户对象的业务参数，但它不是运营登录租户身份。平台存储用户私有 Agent 不默认赋予运营访问权；企业市场发布与版本更新仍遵守用户主动发布、只有发布者更新的业务规则，不由网关改写。

## 五、能力建设状态

| 能力 | 当前建设状态 | 现状说明 |
| --- | --- | --- |
| 工程与配置基础 | **已具备** | 四模块工程、Spring Cloud Gateway、WebFlux、Reactive Redis、Nacos、Vault AppRole 与四环境配置已具备。 |
| 运营路由与白名单 | **已具备** | 当前配置仅代理运营服务；默认无会话白名单只有 `/ai-operations/api/access/login` 和 `/ai-operations/api/access/sms-code/send`。 |
| 运营认证与身份隔离 | **已具备** | 校验 `ops` 会话，只建立用户/会话身份，不校验或透传租户身份，不接受用户侧会话。 |
| 会话续期 | **已具备** | 仅读取会话 TTL/续期窗口；索引只延长 TTL，失败不续主会话，原会话内容变化时拒绝续期。 |
| 动态治理 | **已具备** | 路由参数先绑定验证，无效配置保留有效快照；撤回配置清空路由或恢复对应治理默认状态。 |
| 限流与熔断 | **已具备** | 直连 IP 令牌桶限流，Redis 异常当前 Fail-Open；自动熔断配置计入 500、502、503、504，并保留显式过滤器参数。 |
| 追踪与请求头治理 | **已具备** | 可信调用链与访问日志；最终请求头阶段移除伪造 IP 头并按运营配置保留会话头和可信 XFF。 |
| 错误与基础监控 | **部分具备** | 统一 JSON 保留框架 HTTP 状态；health、info、prometheus 管理端口绑定 `127.0.0.1`，健康详情关闭；业务指标、告警及错误细分待完善。 |
| 运营业务授权与浏览器策略 | **待完善** | 资源级运营授权由下游服务建设，可信代理识别、CORS 和连接资源治理仍需完善。 |

上述状态表示代码与工程能力，尚不代表真实基础设施联调、完整运营业务闭环或生产部署完成。

## 六、Nacos 治理配置与技术栈

治理 Group 为 `ai-operations-gateway`，索引为 `ai-operations-gateway-index.properties`；业务服务发现 Group 为 `ai-admin`。本网关不读取用户网关或 `ai-base` 的治理配置。

| DataId | 用途 |
| --- | --- |
| `ai-operations-gateway-routes.json` | 运营路由与鉴权白名单 |
| `ai-operations-gateway-rate-limit.json` | 默认及运营路由级令牌桶规则 |
| `ai-operations-gateway-circuit-breaker.json` | 默认及运营路由级熔断规则 |
| `ai-operations-gateway-downstream-headers.json` | 运营下游扩展请求头透传范围 |
| `ai-operations-gateway-call-chain.json` | 运营调用链跳数及长度限制 |

技术栈包括 Spring Cloud Gateway、GlobalFilter、HttpHeadersFilter、LoadBalancer、Reactive Redis 单键 Lua、Resilience4j、Nacos、Vault、Reactor Context、MDC、Logback、Actuator 和 Micrometer。

## 七、后续建设方向

- 与运营前端和服务联调登录、会话撤销及角色权限闭环，持续保持运营无租户身份边界；
- 完善运营浏览器 CORS、可信代理 IP 识别、请求大小和连接超时等资源保护；
- 建设认证拒绝、限流、熔断与下游调用指标、告警及统一排障上下文；
- 完善凭据外置、轮换和配置生命周期治理；
- 按实际运营业务需求完善保护策略，不新增用户 Agent 存储、企业任务执行或租户授权职责。

