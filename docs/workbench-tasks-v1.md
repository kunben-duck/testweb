# 智能工作台任务（V1）

任务不依附于会话或 Run。业务方规划并执行，ChatService 裁决写入、保存状态、登记控制命令与可恢复输出。正常建单不派发 START；无人在线也可通过 HTTP 推进。对照[原始字段表](workbench-task-fields.md)、[可导入 Swagger 的 OpenAPI](openapi/workbench-tasks-v1.json)和[数据库增量脚本](../src/main/resources/db/incremental-20261128-workbench.sql)。

## 接入与部署

| 接入方 | 完整操作、请求和响应示例 | 单文件 Swagger |
|---|---|---|
| 第三方产品、定时服务、领域 Agent | [建单、分批同步及反向命令接收](workbench-agent-integration.md) | [Agent 版](openapi/workbench-agent-v1.json) |
| 工作台前端 | [九个用户接口、WS 实收帧及断线恢复](workbench-frontend-integration.md) | [前端版](openapi/workbench-frontend-v1.json) |

维护时以[完整 HTTP 契约](openapi/workbench-tasks-v1.json)为准，在仓库根目录运行 `node docs/openapi/export-workbench.mjs` 导出两版，带 `--check` 只校验不写入。WS 的双向消息不伪装成 HTTP 操作。

1. DBA 审核并先部署增量 DDL（全新库可用初始化脚本），不需要重写现有消息表。
2. 服务间入口仅允许可信网络经 HTTPS 调用，网关做限流。在配置中心为调用方配置 tenant、token-sha256、apps、agents 和 delegated-users。身份来自服务端配置而非请求体。每个服务使用独立高熵 Bearer 密钥。Agent 与产品授权的组合应遵循最小权限；同一凭据拥有所登记 Agent 的权限。
3. 个人入口和会话建单要求**实际选中的**企业 AuthContextProvider 实现 VerifiedWorkbenchIdentity。不要给固定默认用户加此标识。生产对话建单需至少 32 字符的服务端 signing-key（多实例一致）。不要记录凭据或完整正文。
4. 配置命令地址与单独的出站密钥，验证下游按 commandId 幂等并核对 assignmentVersion。所有实例支持后显式开启 financeex.workbench.enabled；默认不暴露接口、不执行派发或清理。企业权限、真实锁竞争与容量需部署联调。

配置中心完整键示例（值为占位，不是凭据）：
```properties
financeex.workbench.enabled=true
financeex.workbench.signing-key=<从密钥管理注入>
financeex.workbench.clients.product-a.tenant=tenant1
financeex.workbench.clients.product-a.token-sha256=<服务入站密钥UTF-8字节的小写SHA256>
financeex.workbench.clients.product-a.apps=finance
financeex.workbench.clients.product-a.agents=ledger-agent
financeex.workbench.clients.product-a.delegated-users=u1,u2
financeex.workbench.clients.product-a.command-endpoints.ledger-agent=https://agent.example.invalid/workbench/commands
financeex.workbench.delivery-tokens.product-a=<独立的出站密钥>
```

## 两个服务接口

均传 `Content-Type: application/json`、`X-Task-Client: product-a`、`Authorization: Bearer <服务入站密钥>`。建单与同步均在事务结束后 ACK；网络超时重发原请求。401 身份无效，403 无授权，404 归属不符或不存在，409 幂等冲突、指派过期或缺号，413 数据过大。不能把投递成功等同于业务已完成。

**POST /v1/internal/tasks**（完整最小外部示例）：

```json
{"requestId":"close-202611","channel":"EXTERNAL","locale":"zh","l1":{"id":"accounting","name":"财务"},"sourceProduct":{"id":"finance","name":"财务工作台"},"title":"十一月结账","status":"PENDING","owner":{"id":"u1","name":"王一"},"creator":"财务工作台","responsibleAgent":{"id":"ledger-agent","name":"账务助手"},"executingAgents":[],"agentExecutionStatus":"IDLE","participants":[{"id":"u2","name":"李二"}]}
```

首次 201，同租户、调用方和 requestId 的相同请求重试 200（原回执）；不要用新的 requestId 做网络重试。以下示例中 T 代指回执中的真实 taskId。渠道支持 CHAT、AGENT、EXTERNAL、SCHEDULED。CHAT 需从当前 Agent 问答请求取 taskCreationContext（有效期五分钟，绑定当前用户、主站会话、Run 和 Agent）。只有服务接口创建任务；Agent 取回 taskId 和 referenceToken 后在**原 Run**返回 `{"type":"task.reference","taskId":"…","referenceToken":"…"}`，ChatService 验签后才输出卡片，凭据不进入卡片。

**POST /v1/internal/tasks/{taskId}/sync**：状态与帧分两种请求，不能混在一批。以下是单个状态批次：

```json
{"kind":"STATE_CHANGE","eventId":"plan-1","assignmentVersion":1,"expectedVersion":1,"changes":[{"type":"task.updated","data":{"status":"RUNNING","executingAgents":[{"id":"ledger-agent","name":"账务助手"}]}},{"type":"group.upsert","data":{"groupId":"g1","title":"校验","sortOrder":0}},{"type":"step.upsert","data":{"stepId":"s1","groupId":"g1","title":"核对报表","sortOrder":0,"status":"RUNNING"}},{"type":"step.upsert","data":{"stepId":"s2","groupId":"g1","title":"检查差异","sortOrder":1,"status":"RUNNING"}}]}
```

同批全成或全败；版本只加一。可提供 expectedVersion 进行乐观并发控制。另支持 group.removed、step.updated、step.removed、task.assigned、interaction.requested。移组更新 groupId；先移走步骤再移除分组。移除保留输出，不重用原 ID。交接变更放本批最后，assignmentVersion 加一，取消旧待办。状态：PENDING/RUNNING/PAUSED/WAITING_HUMAN/FAILED/TERMINATED/COMPLETED，步骤另有 SKIPPED。任务完成必须显式上报，步骤完成时在 data.summary 中给出摘要。

同一接口的**完整输出批次**（STEP 作用域）：
```json
{"kind":"OUTPUT_FRAMES","scope":"STEP","stepId":"s1","operationId":"op1","assignmentVersion":1,"firstFrameNo":1,"frames":[{"content":"<think>正在核对</think>两份报表一致。"},{"endFlag":true}]}
```
无步骤时用 scope=TASK 且不传 stepId。operationId 在整单内唯一；业务重试换操作，网络重发不换。firstFrameNo 从 1 起，按输入原始 JSON 帧连续计数，包含不产生展示的心跳；输出游标只计归一化事件。首个展示帧、尾帧立即发，正文可按约 200ms 合批。ACK 返回 lastAcceptedFrameNo 和 lastOutputSeq；缺号返回 expectedFrameNo。已过回放保留期的旧帧不会被重新追加。按服务端 ACK 顺序提交最终正文、步骤完成、任务完成。不要把同一份对话 SSE 再投给共享任务流。

## 任务字段与投影

| 原始业务字段组 | HTTP/快照字段与口径 |
|---|---|
| 语言、模块、二三级分类 | locale=zh/en；l1、可选 l2/l3 均为 {id,name}。L1 不以来源 APPID 代替；L2 与 L3 筛选取交集。 |
| 编码、标题、描述、标签 | businessCode、title（300 字）、description（1000 字）、tags（独立的字符串，重名去重）。 |
| 人、Agent、产品、技能 | owner 是唯一责任人，participants 是参与人；responsibleAgent 与 executingAgents 分开；collaboratingAgents、recommendedSkills 可选。均用 {id,name}，复数为数组。sourceProduct、creator 记录来源。 |
| 进度与状态 | status、statusDescription、progressSummary（1000 字）。executingAgents 必传，可空数组；taskContext、agentExecutionStatus 不进入用户快照。详情 progress 是 completed/total，跳过留在分母，移除不算，零步不返回。 |
| 计划与计时 | plannedStart/plannedEnd 为 {value,zone,precision}，例如 {"value":"2026-11-28T09","zone":"Asia/Shanghai","precision":"HOUR"}。DAY/HOUR/MINUTE 保留输入精度。createdAt、startedAt、finishedAt 服务端生成；elapsedMs 含暂停与人工等待；remainingSeconds 带符号，以输入时间点计算。 |
| 组与步骤 | groupId/stepId 为稳定键；title、sortOrder、groupId、description、renderRef、statusDescription、summary。currentStepIds 包含运行、需人工和异常步骤；组有独立 progress。 |
| 字典与扩展 | extensions 对象须有该产品和类别（有 L2 取 L2，否则 L1）的已发布字典；semantic_doc_ref 指向业务说明。definition_json 的键为字段名，值示例 {"type":"TEXT","filter":true,"search":true,"sort":false}。支持 TEXT、NUMBER、BOOLEAN、TIME；分页过滤时按指定字典版本投影，不将客户端字段名拼进 SQL。 |
| 操作与日志 | actions 为命令而非既成状态；audit 保存 actor_id、actor_type、object_id、action_type、outcome 和 creation_date，不复制正文。 |

## 工作台与实时协议

- GET /v1/chat/tasks：offset/limit（默认 0/50，最大 200）、relation=ALL/OWNER/PARTICIPANT、status、l1/l2/l3。扩展字段另传 app、类别、field、op=filter/search/sort，前两者传 value，排序可传 direction=desc。
- GET /v1/chat/tasks/{taskId}：一致任务快照、当前步骤、组、摘要、授权待办及允许操作。GET .../events?afterVersion=V：只读取 V 之后的业务变化。
- GET .../process 或 .../steps/{stepId}/process：返回 operations[].content/parts、可见交互及 lastOutputSeq，操作按 offset/limit 分页（默认 2，最多 4）。同一操作跨批的思考标签、工具、卡片、正文由原 Run 解析与组装逻辑处理，单次 message.completed 不推进步骤或整单。过期游标重新读取详情。
- POST .../session：不需请求体，幂等返回当前用户自己的 sessionId。沿用 /v1/chat/runs 和原 WS 私聊；不向其他参与人广播聊天正文。删除个人会话不停止任务。当前责任 Agent 优先于普通初始路由。自然语言动作可由责任 Agent 返回 task.action.proposed，回传 taskId、expectedVersion、action 及本轮 taskActionContext；用户确认后走下述 actions 接口，模型提议本身不改变状态。
- POST .../actions：例如 `{"action":"PAUSE","expectedVersion":2,"idempotencyKey":"pause-01"}`。返回 202 和 commandId。START/PAUSE/RESUME/TERMINATE 仅责任人可发；ANSWER 还允许当前待办获授权的参与人。DELETE .../{taskId} 仅责任人可逻辑删除已完成/已终止任务。GET .../logs 支持 offset/limit。
- 交互示例：Agent 在 STATE_CHANGE 的 changes 中发送 `{"type":"interaction.requested","data":{"interactionId":"i1","requestId":"approval1","stepId":"s1","answerUserIds":["u2"],"questions":[{"question":"是否通过复核？","multi_select":false}]}}`。用户提交 `{"action":"ANSWER","expectedVersion":3,"idempotencyKey":"answer-1","interactionId":"i1","answer":{"label":{"是否通过复核？":"通过"}}}`。可选关联 operationId，需属于相同步骤。问卷状态与步骤状态独立；答案不改写思考内容。
- 同一 /v1/chat/ws 连接发送 `{"id":"sub1","type":"subscribe","topicId":"task:T:step:s1","afterSeq":7}`（T 换真实 ID）。task:T 看整单/组/步变化，afterSeq 为 taskVersion；task:T:process、task:T:step:s1 看执行过程，afterSeq 为各自的输出游标。task-event 信封的 offset 为新游标，details.event 为变化批次或 {operationId,outputSeq,type,payload}。首次订阅从详情水位续接，按序应用；收到 TASK_REFRESH_REQUIRED 重新鉴权并取快照。关闭页面只退订，不控制任务。

## 运维与验收

责任 Agent 可授权步骤的 executingAgents。协作方输出必须传 agentId；协作方只能更新获派步骤的状态、说明或摘要，或为该步骤请求人工输入，不能调整整单、成员或计划。整单交接后，即使协作方知道新的 assignmentVersion，旧的步骤授权也不会自动继承。新的责任方需显式更新步骤 executingAgents。

| 事实 | 对应数据库表 |
|---|---|
| 当前快照和归属 | fin_ex_wb_task_t 保存当前版本和初始建单回执；fin_ex_wb_task_group_t、fin_ex_wb_task_step_t 是当前计划；fin_ex_wb_task_member_t、fin_ex_wb_task_session_t 分别用于参与资格和个人会话关联。 |
| 两条独立的恢复链路 | fin_ex_wb_task_event_t 一行对应一个已接受的业务版本。fin_ex_wb_task_output_stream_t 维护每个范围的输出游标；fin_ex_wb_task_operation_t 保存操作正文、水位和解析状态；fin_ex_wb_task_op_part_t 保存展示分段；fin_ex_wb_task_output_frame_t 保存有限期的回放和批次回执。 |
| 人工操作 | fin_ex_wb_task_interaction_t 保存待办、授权成员和答案；fin_ex_wb_task_command_t 保存幂等键、投递状态和租约；fin_ex_wb_task_audit_t 记录何人何时对什么对象做了什么。 |
| 扩展语义 | fin_ex_wb_task_schema_t 登记按租户、产品、类别发布的版本化字段；fin_ex_wb_task_ext_value_t 是类型化查询投影，原始值仍保存在快照中。 |

- 重要写操作的权威依据是数据库事务；输出事务对任务持共享锁、按范围锁水位、按操作确认归属。状态批次在整单排他锁下提交，网络不占用锁。详情在同一个重复读事务读取正文和水位。事件表不存高频正文。
- 本机提交后立即唤醒订阅；有响应式 Redis 模板时以单个频道广播无正文的轻量提示（部署可配置 financeex.workbench.hint-channel）。提示丢失仍由持久化游标定期检查补取。数据库不可用或失权时停止该订阅。没有桥接聊天 Run 的 Redis topic。
- HTTP 批次默认最大 5 MiB、128 帧，单操作累计输入默认 16 MiB；可分别设置 max-batch-bytes、max-frames 和 max-operation-bytes。批次 ACK 与回放默认保留 24h，过期小批删除；业务正文不随回放过期清除。压测需关注大操作逐批更新正文的写放大、步骤热点、任务参与人数和大快照响应。
- 命令提交后按租约取出，事务外 HTTP POST 到注册的 HTTPS 地址；以 2xx 确认投递。超时或非 2xx 退避，最多 12 次；下游必须按 commandId 去重并拒绝旧指派。投递成功不是任务已推进。需监控 FAILED 命令并由运维核对，不承诺跨系统恰好一次执行。
- 监测 workbench.sync.duration、workbench.sync.rejections，并在目标环境确认数据库锁等待、命令积压、回放磁盘和跨实例首帧延迟。灰度前按原始 67 条字段做产品验收；至少演练无人在线完成、A/B 交接、撤权、断线重放、暂停竞态及 ACK 丢失。
