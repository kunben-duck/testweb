# 智能工作台任务流设计方案（V1）

**状态：设计提案，尚未实现。** 本文中的任务表、接口和下游协议均为拟新增能力，不是现网调用契约。

## 1. 任务与会话分离

SuperAgent 是任务编排方，也是整单和步骤状态的唯一权威。领域 Agent 或 SubAgent 接受委派，在用户离线时也可以执行，上报的是“我完成了这个步骤”等事实；只有 SuperAgent 能决定下一步、是否等待人工、是否换 Agent，以及整单是否完成。

| 对象 | 标识与职责 |
|---|---|
| 任务 | `taskId` 标识一个业务目标。创建时固定模板版本和快照；手动、定时和外部触发共用创建逻辑。 |
| 分组 | `groupId` 表示一级业务阶段。进度由下属步骤汇总，不接受独立的分组状态写入。分组边界不等于 Agent 边界。 |
| 步骤 | `stepId` 是任务内可操作、追问和回顾的单位。模板定义全局顺序、执行方、人工输入、允许动作和重试/跳过规则。首版同一时刻只推进一个主步骤。 |
| 执行尝试 | `executionId` 标识一次委派，绑定实际 Agent 和获授权的连续步骤。网络重发沿用此 ID；换 Agent 或明确业务重试才建立新的尝试。 |
| 任务主会话 | `sessionId` 是用户讨论入口，一个任务最多关联一个有效主会话，首次对话时才创建。`runId` 仍只表示一轮对话，不用一个长期 Run 代表任务。 |

**示例：经营报告。** “资料准备”分组包含 S1 收集报表、S2 校验口径，委派给 A；“分析与交付”分组包含 S3 人工复核、S4 生成报告，S4 委派给 B。无人在线时 A 能做完 S1、S2；只有复核通过才能执行 S4。追问“为什么 S2 的数据不一致”可以立即回答，但不等于通过 S3。

## 2. 状态与操作

| 整单状态 | 进入条件及含义 |
|---|---|
| `PENDING` 待处理 | 已创建任务和待派发命令，尚未开始执行。 |
| `RUNNING` 处理中 | 正在推进自动步骤。当前 Agent 从当前步骤的可信指派读取。 |
| `WAITING_HUMAN` 待人工处理 | 模板到达人工步骤，或当前执行提出经模板校验的输入需求。只有指定动作和有效输入才能解除，纯人工步骤可没有当前 Agent。 |
| `PAUSED` 已暂停 | 已到达安全边界并停止继续派发。点击暂停之后、边界确认之前，仅显示“暂停请求已受理”；恢复执行不同于提交人工答案。 |
| `FAILED` 异常 | 已确认的失败导致流程无法继续。是否允许重试取决于模板；仅网络超时或回调延迟不证明执行失败。 |
| `TERMINATED` 已终止 | 主控不再授权任何推进；远端是否已停止计算是不同的事实。 |
| `COMPLETED` 已完成 | 所有必需步骤已完成，可选步骤已完成或合法跳过；单次调用成功不足以判定整单完成。 |

步骤还支持 `SKIPPED`，仅适用于允许跳过的步骤，不设整单“已跳过”。暂停任务不批量覆盖其他步骤的历史状态。`TERMINATED`、`COMPLETED` 为不可再推进的终态。

## 3. 下游执行协议

### 派发与受理

SuperAgent 向可信配置中的目标 Agent 发送以下请求，适配器负责 HTTP 或消息传输。示例中 S1、S2 属于同一次委派，只有 S1 已获开始许可。

```json
{"taskId":"T1","executionId":"E1","templateVersion":"v3","allowedStepIds":["S1","S2"],"startStepId":"S1","inputRefs":["file-1"]}
```

下游受理后返回 HTTP 202 或等价的消息确认：

```json
{"executionId":"E1","externalExecutionId":"job-83","status":"ACCEPTED"}
```

相同 `executionId` 和请求内容必须返回同一作业；相同 ID 不同内容应拒绝。受理响应丢失时仍以 E1 查询或重发，不能改用 E2。响应迟于进度回调时，只补充执行关联，不回退状态。输入和结果使用受访问控制的引用，不把用户 Cookie 或凭据持久化给异步作业。

### 后台事件与步骤边界

回调入口：`POST /v1/internal/tasks/execution-events`。首版支持 `STEP_STARTED`、`STEP_PROGRESS`、`STEP_COMPLETED`、`STEP_FAILED`、`HUMAN_INPUT_REQUIRED`。下面是 E1 的第 4 条事件：

```json
{"eventId":"evt-4","taskId":"T1","executionId":"E1","stepId":"S1","seq":4,"type":"STEP_COMPLETED","data":{"summary":"已校验12份报表","resultRef":"artifact-17"}}
```

事务提交成功后返回：

```json
{"accepted":true,"lastAcceptedSeq":4,"decision":"CONTINUE","nextStepId":"S2"}
```

`CONTINUE` 只授权指定下一步；`HOLD` 禁止继续，等待人工输入或恢复；`END` 结束本次委派。上例中 A 完成 S2 后，SuperAgent 将 S3 置为待人工，向 A 返回 `END`。S3 获批后再创建 B 的执行 E2，不能让 A 自行转单。不支持步骤边界确认的下游每次只派一步。

下游应支持按执行标识查询快照，包括当前步骤、最近序号、已完成步骤和结果引用。缺号时优先补发事件；无法补发时以可核实的执行快照对账，不用远端状态整体覆盖本地任务，不补造人工审批或历史结果。

## 4. 主控的事务、幂等与恢复

1. **创建并留出恢复依据。** 在一个事务中写任务、分组、步骤和待派发命令。提交后后台工作线程以带期限的租约认领；网络调用在事务外。服务重启后可重新认领过期命令，仍复用原执行 ID。
2. **验证并去重。** 从服务间认证识别回调方，验证任务、执行、Agent 及步骤范围。先核对已处理事件：相同标识与内容返回已保存的处理结果；相同标识或序号但内容不同则冲突。接着检查序号是否连续、执行是否仍有效、任务是否允许迁移。给旧事件的回执不构成新的执行许可。
3. **原子推进并确认。** 在短事务中锁定或按版本条件更新任务，记录事件、关键节点版本以及下一条命令。如果需要切换处理方，将当前步骤切至 S4、指派 B、创建 E2 和待派发命令作为同一次提交。提交失败则不确认事件、不派发下一步；通知前端以提交后的任务版本为准。
4. **避免双重副作用。** 下游按执行 ID 和步骤许可去重，SuperAgent 按动作幂等键和回调序号去重。暂停请求与发放许可竞争时，以已提交的结果为准：已经获得许可的步骤可到达安全边界，不能显示为已经停止。终止后到达的旧执行结果不能重新激活任务。
5. **区分业务历史与遥测。** 开始、等待、换人、完成、失败、跳过等关键变化保留节点版本。进度可合并后更新；心跳、文本增量和每一个百分点都不生成节点历史版本。前端缺少增量时重新拉取详情，不依赖持续在线。

## 5. 数据保存与表结构

以下七张表为逻辑设计，不是可执行 DDL。ID 建议使用 `NVARCHAR2(64)`，状态使用 `NVARCHAR2(32)`，版本和序号使用 `BIGINT`，时间使用 `TIMESTAMPTZ`，结构化快照使用序列化 JSON，审计时间使用 `creation_date/last_update_date`。所有访问通过任务归属约束到租户和用户。

| 表名 | 主要字段 | 约束和读取方式 |
|---|---|---|
| `fin_ex_task_t` | `task_id, tenant_id, user_id, session_id, title, template_key, template_version, template_snapshot_json, trigger_type, create_key, status, current_step_id, task_version` | 任务主键；非空会话 ID 唯一；归属与创建键联合唯一。按归属、状态和更新时间查询列表。 |
| `fin_ex_task_group_t` | `group_id, task_id, group_key, title, sort_order` | 任务内分组键唯一；保存分组展示顺序。 |
| `fin_ex_task_step_t` | `step_id, task_id, group_id, step_key, title, sort_order, status, agent_type, agent_key, active_execution_id, step_version, progress, result_ref, pending_input_json` | 任务内步骤键、全局顺序各自唯一。工作台当前节点及 `currentAgent` 取自此表。 |
| `fin_ex_task_exec_t` | `execution_id, task_id, agent_type, agent_key, allowed_steps_json, attempt_no, retry_of_id, external_execution_id, last_agent_seq, status, started_at, finished_at` | 委派及当时的 Agent 不覆盖；业务重试以新行关联前次执行。 |
| `fin_ex_task_event_t` | `event_id, task_id, task_version, step_id, execution_id, source_type, source_event_id, source_seq, event_type, payload_hash, change_json, occurred_at` | 下游事实分别按执行加事件 ID、执行加序号去重。一次已提交的业务变化用一个任务版本表达，包含相应变化及边界回执，可按版本增量读取。 |
| `fin_ex_task_step_rev_t` | `step_id, step_version, task_id, task_version, status, agent_key, summary, result_ref` | 步骤与版本联合唯一，记录当时的结论、Agent 和结果引用；重试不覆盖旧版。 |
| `fin_ex_task_command_t` | `command_id, task_id, step_id, execution_id, command_type, actor_type, actor_id, idempotency_key, expected_version, status, payload_json, lease_until, next_attempt_at, result_json` | 任务与幂等键联合唯一；以状态及到期时间索引认领派发命令。区分用户已提交、等待确认、派发中和已处理，不以请求到达代替动作完成。 |

不修改现有大消息表。任务问答仍按原规则保存消息，由服务端关联可信的 `taskId/stepId/stepVersion`；指定历史版本时，不以当前版本替代。临时地址、敏感原文和无权留存的结果不能转存到任务事件或历史快照；取用结果引用时仍须鉴权。

## 6. 工作台接口及自然语言分流

| 类型 | 接口 | 行为 |
|---|---|---|
| 新增 | `POST /v1/chat/tasks`、`GET /v1/chat/tasks`、`GET /v1/chat/tasks/{taskId}` | 创建、列表、详情。返回版本、整单状态、当前步骤及 Agent、分组和允许动作。定时任务由可信授权确定归属，按“任务计划、目标用户、计划触发时间”生成稳定创建键。 |
| 新增 | `POST /v1/chat/tasks/{taskId}/session` | 首次对话时创建并绑定，重复或并发打开返回同一有效主会话。删除会话只解除关联，不停止任务；此后可重新创建主会话。 |
| 扩展 | `POST /v1/chat/runs` | 根据服务端确认的会话归属，在普通路由和 Binding 处理前分流。可指定 `stepId/stepVersion`，后端确认其确属该任务。无关联的普通聊天保留原行为。 |
| 新增 | `POST /v1/chat/tasks/{taskId}/actions` | 按钮与自然语言共用命令校验：归属、幂等键、动作、目标、参数和预期版本。版本过期时返回冲突和最新状态，不把对旧节点的批准转给新节点。 |
| 新增 | `GET /v1/chat/tasks/{taskId}/events?afterVersion=…`、`GET /v1/chat/tasks/{taskId}/steps/{stepId}/history` | 前者用于增量刷新，后者读取节点历史版本。任务通知只提示有变化，详情才是页面恢复依据。 |

自然语言分三路：“到哪一步了？”读任务快照；“当时为什么判异常？”对选定版本走下游只读问答；“确认复核并继续”提议模板允许的动作，由主控校验执行。输入有歧义先澄清，跳过、终止及模板标记的高风险动作必须二次确认。即使自然语言模型判断错误，也不能绕过权限、动作白名单、确认和版本检查。

现有 [Run、Stop、Resume 和 DomainAgent 异步终态回调契约](../openapi/financeex-chatservice-v1.yaml) 不作为整单协议使用。普通 Run 继续只负责一次聊天；停止追问不停止任务，恢复聊天流不代替任务历史。工作台识别任务会话可通过会话列表或详情中的可选 `sessionMode/taskId`，列表批量关联，不逐会话查任务。

## 7. 交付与验收

- **阶段一：不依赖会话的执行闭环。** 模板、任务存储、手动创建、派发、回调、换 Agent、查询、重复和迟到事件防护。必须能在用户完全离线时运行。
- **阶段二：工作台交互。** 主会话、只读追问、自然语言命令、节点回顾和定时触发。下游接入前须联调幂等受理、步骤边界许可、回调身份、快照对账及只读问答，不能仅凭已有聊天能力假设支持。
- **端到端验收。** 无人在线时 A 做完 S1、S2 → 任务等待 S3 → 首次打开会话并追问 S2，S3 仍等待 → 确认 S3 后只派发一次 B → A 的迟到回调不改变 S4 → 关闭会话后 B 完成 → 再次打开可分别回顾 A、B 的结果。
- **竞争与隔离。** 验证创建重复触发、受理响应丢失、回调重放/缺号、工作线程重启、过期页面提交、暂停与步骤完成竞争、终止后迟到成功、跨用户访问、只读追问失败及普通聊天回归。
