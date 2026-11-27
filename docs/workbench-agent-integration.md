# 第三方产品与领域 Agent：工作台任务接入手册（V1）

本手册适用于负责建单、执行任务或接收用户命令的服务。用户不在线，也不需要先有会话。**你实现一个命令接收端，调用 ChatService 的两个 HTTP 入口。** 浏览器不要使用这套机机凭据。

[Agent Swagger（可单独导入）](openapi/workbench-agent-v1.json) · [全部 HTTP 契约](openapi/workbench-tasks-v1.json) · [前端看到的同一任务](workbench-frontend-integration.md) · [部署配置与容量](workbench-tasks-v1.md) · [原始业务字段](workbench-task-fields.md)

## 接入前准备

向部署方登记服务 ID、租户、允许的产品、Agent、可代办的用户、每个接收 Agent 唯一的 HTTPS 命令地址和**独立的出站凭据**。服务端必须开启 `financeex.workbench.enabled`。示例假设 product-a 在 finance 产品下代表 ledger-a，允许 u1、u2；交接目标 ledger-b 另有授权的凭据。以下地址与令牌均为占位；任务、操作、用户 ID 在两份手册中一致。

两个入站接口都使用以下头。不要用 Cookie 替代服务认证，不提交租户来切换身份。成功响应均为 JSON；状态码仅代表当前 HTTP 操作。

```http
POST /v1/internal/tasks HTTP/1.1
Host: chat.example.invalid
Content-Type: application/json
X-Task-Client: product-a
Authorization: Bearer <product-a 的入站令牌>
```

## 一、创建任务

建单请求不传 taskId、会话 ID、初始步骤或分组。常见 ID 字段使用 `[a-zA-Z0-9_.-]+`，最多 100 字符（下文另有限制者除外）。所有枚举按标出的大小写发送。建单状态由调用方指定；下面都以 PENDING 开始，不会自动投递 START。

| 字段 | 类型 / 何时传 | 来源、作用与限制 |
|---|---|---|
| requestId | string，必填 | 调用方生成；同租户、调用方内幂等。定时建单用本次调度实例的稳定 ID。 |
| channel / taskCreationContext | enum / string | 渠道必传；仅 CHAT 必须附本次会话凭据，其他渠道连 null 都不要传。 |
| locale / title / creator | string，必填 | zh 或 en；任务标题和创建者展示名各最多 300 字。creator 是文字，不是人员对象。 |
| l1 / l2 / l3 | Named | l1 必填，l2/l3 可选；展示分类，与产品身份无关。Named 一律是 {id,name}，name 非空且不超过 300 字。 |
| sourceProduct / owner / responsibleAgent | Named，必填 | 所属产品、唯一责任人、当前责任 Agent。产品与 Agent 必须在凭据授权范围内；非 CHAT 的 owner 须在代用户名单；owner.id 最多 64 字。 |
| executingAgents / agentExecutionStatus | Named[] / string，必填 | 无人执行传 []；机机状态非空，最多 128 字，不替代整单 status，不展示给前端。 |
| status | enum，必填 | PENDING、RUNNING、PAUSED、WAITING_HUMAN、FAILED、TERMINATED、COMPLETED；整单没有 SKIPPED。 |
| participants / collaboratingAgents / recommendedSkills | Named[]，可选 | 参与人、协作 Agent、推荐技能分别保存。人员须在代办范围；每数组最多 128、ID 唯一。协作名单不是步骤写授权。 |
| businessCode / description / progressSummary / statusDescription / tags | string 或 string[]，可选 | 业务编号、描述、进展、状态说明、标签。描述和进展最多 1000 字；标签最多 128 项，非空白、按原序去重。 |
| plannedStart / plannedEnd | object，可选 | {value,zone,precision}；DAY 如 2026-11-28，HOUR 如 2026-11-28T09，MINUTE 如 2026-11-28T09:30，zone 如 Asia/Shanghai；结束不得早于开始。 |
| taskContext / extensions | JSON / object，可选 | 前者是私有机机上下文；后者是产品分类发布的扩展字段，最多 64 个，按字典校验 TEXT/NUMBER/BOOLEAN/TIME。不要把机密放到公开扩展字段。 |

### 四种创建来源
下列四个请求体**分别**提交到上述地址；是替代场景，不是连建四次。首次均返回 201；同内容重发返回 200 和首次回执（不是最新详情）。同键不同内容返回 409。真实 taskId 由服务端生成，后续 URL 使用实际回执值。


**EXTERNAL：第三方产品建单（下述主线）。** 请求：
```json
{
  "requestId": "close-202611",
  "channel": "EXTERNAL",
  "locale": "zh",
  "l1": {
    "id": "accounting",
    "name": "财务"
  },
  "sourceProduct": {
    "id": "finance",
    "name": "财务工作台"
  },
  "title": "十一月结账",
  "status": "PENDING",
  "owner": {
    "id": "u1",
    "name": "王一"
  },
  "creator": "财务工作台",
  "responsibleAgent": {
    "id": "ledger-a",
    "name": "账务助手 A"
  },
  "executingAgents": [],
  "agentExecutionStatus": "IDLE",
  "participants": [
    {
      "id": "u2",
      "name": "李二"
    }
  ]
}
```
响应：
```json
{
  "taskId": "task_0123456789abcdef0123456789abcdef",
  "status": "PENDING",
  "taskVersion": 1,
  "assignmentVersion": 1
}
```


**SCHEDULED：定时任务建单。** 请求：
```json
{
  "requestId": "schedule-202611",
  "channel": "SCHEDULED",
  "locale": "zh",
  "l1": {
    "id": "accounting",
    "name": "财务"
  },
  "sourceProduct": {
    "id": "finance",
    "name": "财务工作台"
  },
  "title": "十一月结账",
  "status": "PENDING",
  "owner": {
    "id": "u1",
    "name": "王一"
  },
  "creator": "财务工作台",
  "responsibleAgent": {
    "id": "ledger-a",
    "name": "账务助手 A"
  },
  "executingAgents": [],
  "agentExecutionStatus": "IDLE",
  "participants": [
    {
      "id": "u2",
      "name": "李二"
    }
  ]
}
```
响应：
```json
{
  "taskId": "task_0123456789abcdef0123456789abcdef",
  "status": "PENDING",
  "taskVersion": 1,
  "assignmentVersion": 1
}
```


**AGENT：Agent 自主建单。** 请求：
```json
{
  "requestId": "agent-01",
  "channel": "AGENT",
  "locale": "zh",
  "l1": {
    "id": "accounting",
    "name": "财务"
  },
  "sourceProduct": {
    "id": "finance",
    "name": "财务工作台"
  },
  "title": "十一月结账",
  "status": "PENDING",
  "owner": {
    "id": "u1",
    "name": "王一"
  },
  "creator": "财务工作台",
  "responsibleAgent": {
    "id": "ledger-a",
    "name": "账务助手 A"
  },
  "executingAgents": [],
  "agentExecutionStatus": "IDLE",
  "participants": [
    {
      "id": "u2",
      "name": "李二"
    }
  ]
}
```
响应：
```json
{
  "taskId": "task_0123456789abcdef0123456789abcdef",
  "status": "PENDING",
  "taskVersion": 1,
  "assignmentVersion": 1
}
```


**CHAT：原对话建单。** 请求：
```json
{
  "requestId": "chat-01",
  "channel": "CHAT",
  "locale": "zh",
  "l1": {
    "id": "accounting",
    "name": "财务"
  },
  "sourceProduct": {
    "id": "finance",
    "name": "财务工作台"
  },
  "title": "十一月结账",
  "status": "PENDING",
  "owner": {
    "id": "u1",
    "name": "王一"
  },
  "creator": "财务工作台",
  "responsibleAgent": {
    "id": "ledger-a",
    "name": "账务助手 A"
  },
  "executingAgents": [],
  "agentExecutionStatus": "IDLE",
  "participants": [
    {
      "id": "u2",
      "name": "李二"
    }
  ],
  "taskCreationContext": "<本轮服务端签发的凭据>"
}
```
响应：
```json
{
  "taskId": "task_0123456789abcdef0123456789abcdef",
  "status": "PENDING",
  "taskVersion": 1,
  "assignmentVersion": 1,
  "referenceToken": "<原 Run 的展示凭据>"
}
```


**CHAT 多一跳**：ChatService 在符合条件的原 DomainAgent 问答请求里提供 `taskCreationContext`（5 分钟，绑定租户、用户、session、run、agent）。原样带入建单请求，不能自己用 sessionId 拼造。CHAT 即使重发仍需有效上下文；已关联其他任务的来源会话不能再绑定。取得回执后，Agent 在**原 Run**返回下面的完整原始帧。服务端验签后产生 runtime.card（前端看到的 payload 见前端手册），不再建单，不公开 token：

```json
{
  "type": "task.reference",
  "taskId": "task_0123456789abcdef0123456789abcdef",
  "referenceToken": "<建单回执中的 referenceToken>"
}
```

后续任务私聊的 Agent 请求携带 taskId、assignmentVersion、taskVersion、taskAllowedActions；可签名时另有 taskActionContext。要提议动作，在本轮响应发送以下帧。ANSWER 可带 interactionId；允许的动作值为 START、PAUSE、RESUME、TERMINATE、ANSWER。验签失败不生成卡片；有效的提议也不是已执行命令，用户确认仍走前端 actions。

```json
{
  "type": "task.action.proposed",
  "taskId": "task_0123456789abcdef0123456789abcdef",
  "expectedVersion": 2,
  "action": "PAUSE",
  "taskActionContext": "<本轮请求的 taskActionContext>"
}
```

## 二、同步状态与输出

```http
POST /v1/internal/tasks/task_0123456789abcdef0123456789abcdef/sync HTTP/1.1
Host: chat.example.invalid
Content-Type: application/json
X-Task-Client: product-a
Authorization: Bearer <当前调用方令牌>
```

| 字段 | 谁生成 / 有效范围 | 正确用法 |
|---|---|---|
| eventId | 调用方，单任务、调用方内 | 每个状态批次一个稳定 ID；同内容重发可取回原 ACK。不是 WS 的 offset。 |
| assignmentVersion | 服务端，整单指派 | 初始 1；每个同步请求必传。真正换责任 Agent 才递增；新 Agent 使用新版本和自己的凭据。 |
| taskVersion / expectedVersion | 服务端整单业务版本 / 调用方可选前提 | 状态批次只加 1；用户动作受理也加 1；纯输出不加。ACK 的 status 是整单状态，不是某步状态。 |
| operationId / firstFrameNo | Agent，单次操作 / 该操作的输入帧 | 整单内唯一的操作 ID；同操作首帧为 1。下一批从上一 ACK.lastAcceptedFrameNo+1 开始，心跳也计数。 |
| lastOutputSeq | 服务端，每个 TASK 或 STEP 作用域 | 仅归一化展示事件计数。一个输入可能产生零至多个输出；同一步的多个操作共用输出游标，但各自输入从 1 开始。 |

**STATE_CHANGE**：kind、eventId、assignmentVersion、changes 必填；expectedVersion 可选。changes 为 1–128 项，每项含 type 和对象 data。按序原子提交，有任何错误整批不生效。主责凭据可以规划；协作者须有同指派版本的步骤授权，仅可上报指定步骤的 status/statusDescription/summary 或为该步发待办。

| changes[].type | data 入参 | 条件与结果 |
|---|---|---|
| task.updated | status、title、description、progressSummary、statusDescription、executingAgents、participants、collaboratingAgents、recommendedSkills、plannedStart/End、agentExecutionStatus、taskContext、tags、extensions | 按所传字段更新；整单结束单独报。 |
| task.assigned | responsibleAgent:Named | 必须是本批最后一项，新 Agent 在同租户、产品已登记。真正交接后旧待办取消，旧步骤执行授权需重新指派。 |
| group.upsert / group.removed | groupId；upsert 可含 title、sortOrder | 排序从 0，标题最多 100 字；有步骤的组不能删，先移步或移除步骤。移除后 ID 不可复用。 |
| step.upsert / step.updated / step.removed | stepId；首次 upsert 还需 title、非负整型 sortOrder、status | 可选 groupId（null 可移出组）、description、renderRef、statusDescription、summary、executingAgents、plannedStart/End。updated/removal 要求项已存在。步骤多一个 SKIPPED；summary 是业务总结，不是操作正文。 |
| interaction.requested | interactionId、requestId、questions、answerUserIds；可选 stepId、operationId、agentId | interactionId 最多 64 字；1–64 道题，question 非空不重复且最多 512 字，multi_select 是布尔。answerUserIds 最多 128 项且须为成员，责任人也可答。operationId 若传须归属同范围及同 Agent。协作者传本步骤及自身 agentId。 |

**OUTPUT_FRAMES**：kind、scope、operationId、assignmentVersion、firstFrameNo、非空 frames 必填；TASK 禁止 stepId（含 null），STEP 必传 stepId；协作者传 agentId。只传完整原始 JSON 对象，不传 SSE 的 data: 前缀，不传已经包装的 WS 消息。可频繁分批，不必等生成结束。默认单请求 5 MiB、每批 128 帧、单操作累计输入 16 MiB；首帧和尾帧及时发，中间可约 200 ms 合批。回放/输出批次回执默认 24 h。同步问卷用 interaction.requested，不能在输出里塞旧问卷或 agent.async_started。

### 一份任务从建单到完成的逐批报文

下面每一对都是上述同一个 /sync 的**完整请求和 200 响应**（特别注明的用户动作除外）。首例已在版本 1。两个并行步骤不必一起完成。

**A：尚无步骤，先给出任务级正文；业务版本仍为 1。**

请求：
```json
{
  "kind": "OUTPUT_FRAMES",
  "scope": "TASK",
  "operationId": "plan-report",
  "assignmentVersion": 1,
  "firstFrameNo": 1,
  "frames": [
    {
      "content": "开始规划。"
    },
    {
      "endFlag": true
    }
  ]
}
```
响应：
```json
{
  "lastAcceptedFrameNo": 2,
  "lastOutputSeq": 2
}
```


**A：启动，规划一个分组和两个并行步骤；四项变化只生成版本 2。**

请求：
```json
{
  "kind": "STATE_CHANGE",
  "eventId": "plan",
  "assignmentVersion": 1,
  "expectedVersion": 1,
  "changes": [
    {
      "type": "task.updated",
      "data": {
        "status": "RUNNING",
        "executingAgents": [
          {
            "id": "ledger-a",
            "name": "账务助手 A"
          }
        ]
      }
    },
    {
      "type": "group.upsert",
      "data": {
        "groupId": "g1",
        "title": "账务复核",
        "sortOrder": 0
      }
    },
    {
      "type": "step.upsert",
      "data": {
        "stepId": "s1",
        "groupId": "g1",
        "title": "核对报表",
        "sortOrder": 0,
        "status": "RUNNING"
      }
    },
    {
      "type": "step.upsert",
      "data": {
        "stepId": "s2",
        "groupId": "g1",
        "title": "复核差异",
        "sortOrder": 1,
        "status": "RUNNING"
      }
    }
  ]
}
```
响应：
```json
{
  "taskId": "task_0123456789abcdef0123456789abcdef",
  "status": "RUNNING",
  "taskVersion": 2,
  "assignmentVersion": 1
}
```


**A：s1 输出；第一个输入是心跳，前端只收到 8 个事件。**

请求：
```json
{
  "kind": "OUTPUT_FRAMES",
  "scope": "STEP",
  "stepId": "s1",
  "operationId": "verify",
  "assignmentVersion": 1,
  "firstFrameNo": 1,
  "frames": [
    {
      "type": "heartbeat"
    },
    {
      "type": "think",
      "thinkTitle": "核对口径"
    },
    {
      "type": "tool",
      "toolTitle": "校验报表"
    },
    {
      "state": "GENERATE",
      "stateDesc": "整理结果"
    },
    {
      "sourceDocuments": [
        "附件A"
      ]
    },
    {
      "cardUrl": "https://assets.example.invalid/report.js"
    },
    {
      "traceId": "downstream-demo"
    },
    {
      "state": "CHECK"
    },
    {
      "content": "账表一致。"
    }
  ]
}
```
响应：
```json
{
  "lastAcceptedFrameNo": 9,
  "lastOutputSeq": 8
}
```


**A：确认同一操作尾批；前端得到 message.completed，s1 仍 RUNNING。**

请求：
```json
{
  "kind": "OUTPUT_FRAMES",
  "scope": "STEP",
  "stepId": "s1",
  "operationId": "verify",
  "assignmentVersion": 1,
  "firstFrameNo": 10,
  "frames": [
    {
      "endFlag": true
    }
  ]
}
```
响应：
```json
{
  "lastAcceptedFrameNo": 10,
  "lastOutputSeq": 9
}
```


**A：s1 等人工，s2 继续；整单仍 RUNNING。**

请求：
```json
{
  "kind": "STATE_CHANGE",
  "eventId": "ask",
  "assignmentVersion": 1,
  "expectedVersion": 2,
  "changes": [
    {
      "type": "step.updated",
      "data": {
        "stepId": "s1",
        "status": "WAITING_HUMAN"
      }
    },
    {
      "type": "interaction.requested",
      "data": {
        "interactionId": "i1",
        "requestId": "approval1",
        "stepId": "s1",
        "operationId": "verify",
        "questions": [
          {
            "question": "通过复核？",
            "multi_select": false
          }
        ],
        "answerUserIds": [
          "u2"
        ]
      }
    }
  ]
}
```
响应：
```json
{
  "taskId": "task_0123456789abcdef0123456789abcdef",
  "status": "RUNNING",
  "taskVersion": 3,
  "assignmentVersion": 1
}
```


**A：在用户回答已受理（版本 4）之后，单独报告 s1 的摘要。**

请求：
```json
{
  "kind": "STATE_CHANGE",
  "eventId": "verified",
  "assignmentVersion": 1,
  "expectedVersion": 4,
  "changes": [
    {
      "type": "step.updated",
      "data": {
        "stepId": "s1",
        "status": "COMPLETED",
        "summary": "已核对，人工复核通过。"
      }
    }
  ]
}
```
响应：
```json
{
  "taskId": "task_0123456789abcdef0123456789abcdef",
  "status": "RUNNING",
  "taskVersion": 5,
  "assignmentVersion": 1
}
```


**A：交给 B，ACK 中两个版本的含义不同。**

请求：
```json
{
  "kind": "STATE_CHANGE",
  "eventId": "handoff",
  "assignmentVersion": 1,
  "expectedVersion": 5,
  "changes": [
    {
      "type": "task.updated",
      "data": {
        "executingAgents": [
          {
            "id": "ledger-b",
            "name": "账务助手 B"
          }
        ]
      }
    },
    {
      "type": "task.assigned",
      "data": {
        "responsibleAgent": {
          "id": "ledger-b",
          "name": "账务助手 B"
        }
      }
    }
  ]
}
```
响应：
```json
{
  "taskId": "task_0123456789abcdef0123456789abcdef",
  "status": "RUNNING",
  "taskVersion": 6,
  "assignmentVersion": 2
}
```


**B：交接后在 s2 独立输出；先确认尾帧，再报告步骤完成。** 新 operationId 从输入 1 开始，s2 的输出游标也独立于 s1。

请求：
```json
{
  "kind": "OUTPUT_FRAMES",
  "scope": "STEP",
  "stepId": "s2",
  "operationId": "review-final",
  "assignmentVersion": 2,
  "firstFrameNo": 1,
  "frames": [
    {
      "content": "无剩余差异。"
    },
    {
      "endFlag": true
    }
  ]
}
```
响应：
```json
{
  "lastAcceptedFrameNo": 2,
  "lastOutputSeq": 2
}
```

**B：用自己的服务身份，报告 s2 完成。**

请求：
```json
{
  "kind": "STATE_CHANGE",
  "eventId": "reviewed",
  "assignmentVersion": 2,
  "expectedVersion": 6,
  "changes": [
    {
      "type": "step.updated",
      "data": {
        "stepId": "s2",
        "status": "COMPLETED",
        "summary": "未发现未解决差异。"
      }
    }
  ]
}
```
响应：
```json
{
  "taskId": "task_0123456789abcdef0123456789abcdef",
  "status": "RUNNING",
  "taskVersion": 7,
  "assignmentVersion": 2
}
```


**B：显式结束整单。**

请求：
```json
{
  "kind": "STATE_CHANGE",
  "eventId": "done",
  "assignmentVersion": 2,
  "expectedVersion": 7,
  "changes": [
    {
      "type": "task.updated",
      "data": {
        "status": "COMPLETED",
        "executingAgents": []
      }
    }
  ]
}
```
响应：
```json
{
  "taskId": "task_0123456789abcdef0123456789abcdef",
  "status": "COMPLETED",
  "taskVersion": 8,
  "assignmentVersion": 2
}
```


**替代分支，不接上面的版本 8：在版本 2 移除两步及空组。**

请求：
```json
{
  "kind": "STATE_CHANGE",
  "eventId": "remove-plan",
  "assignmentVersion": 1,
  "expectedVersion": 2,
  "changes": [
    {
      "type": "step.removed",
      "data": {
        "stepId": "s1"
      }
    },
    {
      "type": "step.removed",
      "data": {
        "stepId": "s2"
      }
    },
    {
      "type": "group.removed",
      "data": {
        "groupId": "g1"
      }
    }
  ]
}
```
响应：
```json
{
  "taskId": "task_0123456789abcdef0123456789abcdef",
  "status": "RUNNING",
  "taskVersion": 3,
  "assignmentVersion": 1
}
```


## 三、接收用户命令（反向调用）

使用部署时登记的**完整 HTTPS URL**，不可在单次任务请求中指定回调地址。以下路径仅为示例，ChatService 本身不提供它。投递凭据从 delivery-tokens 取，与入站令牌独立；没有 X-Task-Client 头，不传浏览器凭据。

```http
POST /workbench/commands HTTP/1.1
Host: agent.example.invalid
Authorization: Bearer <专用投递令牌>
Content-Type: application/json
```

| 字段 | 类型 | 含义 |
|---|---|---|
| commandId / taskId / action | string，必填 | 接收方按 commandId 幂等；动作不等于业务结果。 |
| actorUserId | string，必填 | 提交者，由服务端身份解析，不是服务调用方 ID。 |
| expectedVersion / assignmentVersion | 正整数，必填 | 用户提交时所见业务版本（**不是** command.accepted 后的新版本）、对应指派版本；旧指派不能继续工作。 |
| interactionId / requestId / answer | string / string / object，仅 ANSWER | 前者是 ChatService 待办内的标识；requestId 对应原 Agent 的问卷编号。answer.label 用原题全文作键。 |
| stepId / operationId | string，条件发送 | ANSWER 且原待办关联该步/操作时携带；不是所有命令都有。 |

以下是五种独立的实际请求体形状；ANSWER 对应主线版本 3 的用户提交，其余演示替代分支。所有命令均允许相同成功响应，无 JSON 业务响应要求。

**START**
```json
{
  "commandId": "cmd_00000000000000000000000000000001",
  "taskId": "task_0123456789abcdef0123456789abcdef",
  "action": "START",
  "actorUserId": "u1",
  "expectedVersion": 1,
  "assignmentVersion": 1
}
```


**PAUSE**
```json
{
  "commandId": "cmd_00000000000000000000000000000002",
  "taskId": "task_0123456789abcdef0123456789abcdef",
  "action": "PAUSE",
  "actorUserId": "u1",
  "expectedVersion": 2,
  "assignmentVersion": 1
}
```


**RESUME**
```json
{
  "commandId": "cmd_00000000000000000000000000000003",
  "taskId": "task_0123456789abcdef0123456789abcdef",
  "action": "RESUME",
  "actorUserId": "u1",
  "expectedVersion": 5,
  "assignmentVersion": 1
}
```


**TERMINATE**
```json
{
  "commandId": "cmd_00000000000000000000000000000004",
  "taskId": "task_0123456789abcdef0123456789abcdef",
  "action": "TERMINATE",
  "actorUserId": "u1",
  "expectedVersion": 2,
  "assignmentVersion": 1
}
```


**ANSWER**
```json
{
  "commandId": "cmd_00000000000000000000000000000005",
  "taskId": "task_0123456789abcdef0123456789abcdef",
  "action": "ANSWER",
  "actorUserId": "u2",
  "expectedVersion": 3,
  "assignmentVersion": 1,
  "interactionId": "i1",
  "requestId": "approval1",
  "stepId": "s1",
  "operationId": "verify",
  "answer": {
    "label": {
      "通过复核？": "通过"
    }
  }
}
```


Agent 可返回：
```http
HTTP/1.1 204 No Content
```

ChatService 只判断 HTTP 2xx 并释放响应体。先在接收方可靠登记 commandId，再确认接单；网络错误、非 2xx 或 5 秒超时会退避重投，最多 12 次。重复请求返回已接单的 2xx，不重复执行。已过期指派、已终态或删除的任务不再按有效命令投递。ANSWER 投递给发起该待办的 Agent；实际恢复后仍用 /sync 更新，不要重新建单。

## 失败处理与可见性

| HTTP | 业务错误示例 | 调用方处理 |
|---|---|---|
| 400 | INVALID_JSON、INVALID_OUTPUT_FRAME、INVALID_HANDOFF_MUST_BE_LAST | 修正 JSON、帧或批次顺序；失败批次无部分成功。 |
| 401 / 403 | SERVICE_IDENTITY_REQUIRED、INVALID_SERVICE_CREDENTIAL / TASK_ACCESS_DENIED | 核对凭据、产品、代办用户和步骤授权；不要改请求体伪造身份。 |
| 404 | TASK_RESOURCE_NOT_FOUND | 核对租户、任务和计划项；不推测其他租户任务是否存在。 |
| 409 | IDEMPOTENCY_CONFLICT、FRAME_CONFLICT、OPERATION_MISMATCH | 同一键不可换内容；真正的新操作用新 ID。 |
| 409 | STALE_ASSIGNMENT、STALE_TASK_VERSION、TERMINAL | 停止旧执行，与当前责任方重新对齐；不可盲目加版本重试。 |
| 409 | FRAME_GAP | 按响应给出的期望输入帧号对账。超时优先原样重发原批；回执已过保留期不要换 ID 重放旧正文。 |
| 413 | BATCH_TOO_LARGE、OPERATION_TOO_LARGE | 缩小未接受的批次；单操作累计已超限时，仅拆批不能解决。 |

普通错误只有 code；缺号另有 expectedFrameNo。例如，上例首批已确认，下一批误从 11 开始：

```json
{
  "code": "FRAME_GAP",
  "expectedFrameNo": 10
}
```

没有 WS 消费者也可完成任务。ACK 不证明浏览器已收到；前端使用独立的任务版本和输出游标恢复，规则见[前端手册](workbench-frontend-integration.md)。
