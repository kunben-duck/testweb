# 工作台前端：HTTP、任务事件与断线恢复（V1）

[前端 Swagger（可单独导入）](openapi/workbench-frontend-v1.json) · [Agent 上报同一案例](workbench-agent-integration.md) · [原有 Runs 和 WS 协议](frontend-integration.md) · [原始业务字段](workbench-task-fields.md)

前端只访问 **/v1/chat/tasks** 用户接口，使用主站当前用户的企业登录态和可信网关，不发送 X-Task-Client 或服务 Bearer，不通过 body 传 tenantId 或 userId。部署必须启用工作台并配置受信任的企业身份。GET 和无体 POST 不传业务 JSON；有体 POST 使用 Content-Type: application/json。示例以 u1 为责任人、u2 为参与人，任务 ID 与 Agent 手册一致。示例 HTTP 省略部署提供的登录头；不要把示例域名用作真实环境。

## 一、九个 HTTP 操作

### 1. 查询我的任务

```http
GET /v1/chat/tasks?relation=OWNER&l1=accounting&offset=0&limit=50 HTTP/1.1
```

200 返回**数组**，没有 total、items 或分页包装；无结果为 []。offset 从 0 起，limit 默认 50，范围 1–200。relation=ALL（默认）/OWNER/PARTICIPANT；只能列出本人负责或参与且未删除的任务。status、l1/l2/l3 为精确条件，两级标签同时传取交集。

```json
[
  {
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
    "participants": [
      {
        "id": "u2",
        "name": "李二"
      }
    ],
    "taskId": "task_0123456789abcdef0123456789abcdef",
    "assignmentVersion": 1,
    "taskVersion": 1,
    "createdAt": "2026-11-28T01:00:00Z"
  }
]
```

扩展字段查询示例（前提是已登记相应字典，不是任意 JSON 路径）：

```http
GET /v1/chat/tasks?app=finance&l1=accounting&field=costCenter&op=filter&value=CC01 HTTP/1.1
```

field 指定时 app 和类别必传，优先 l2 否则 l1。op 默认 filter；filter 按字典 TEXT/NUMBER/BOOLEAN/TIME 解析 value，search 仅用于允许搜索的 TEXT，sort 配 direction=desc 才降序。分页按返回数组长度推进；最后一页不足 limit 或为空时结束。400 检查分页、字典类型和权限开关；403 恢复登录或确认身份接入。

### 2. 打开任务详情

```http
GET /v1/chat/tasks/task_0123456789abcdef0123456789abcdef HTTP/1.1
```

200 示例为刚建单时的完整响应。没有分组、步骤、活动 Agent 也是有效任务。保存 taskVersion，作为**状态**订阅的基线。

```json
{
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
  "participants": [
    {
      "id": "u2",
      "name": "李二"
    }
  ],
  "taskId": "task_0123456789abcdef0123456789abcdef",
  "assignmentVersion": 1,
  "taskVersion": 1,
  "createdAt": "2026-11-28T01:00:00Z",
  "groups": [],
  "steps": [],
  "currentStepIds": [],
  "interactions": [],
  "allowedActions": [
    "START",
    "TERMINATE"
  ]
}
```

| 字段 | 含义及显示规则 |
|---|---|
| taskId、taskVersion、assignmentVersion | 稳定任务标识、已提交业务版本、责任 Agent 指派版本；三者不可互换。 |
| title、businessCode、description、progressSummary、statusDescription、tags | 展示标题、业务编码、描述、进展简述、状态说明、标签；可选字段缺失不是空字符串。 |
| locale、channel、creator、sourceProduct、l1/l2/l3 | 语言、建单渠道、创建者、来源产品、分类。展示分类不是来源产品 ID。 |
| owner、participants、responsibleAgent、executingAgents、collaboratingAgents、recommendedSkills | 人、责任 Agent、当前执行者及技能分别展示，Named 为 {id,name}。私有 taskContext、agentExecutionStatus 不从此视图返回。 |
| groups、steps、currentStepIds | 两个平级数组用 groupId 关联，顺序看 sortOrder。步骤另有 stepId、stepVersion、status、summary 等。当前步骤可以有多个（RUNNING、WAITING_HUMAN、FAILED）。移除项不属于当前计划。 |
| progress、plannedStart/End、createdAt、startedAt、finishedAt、elapsedMs、remainingSeconds | 有步骤才显示 completed/total（跳过计入分母），组进度同理。计划时间含 value、zone、precision。运行时长含等待和暂停；剩余秒数可能为负。 |
| extensions、schemaVersion | 产品字典扩展值与版本；无扩展时可能缺失。 |
| interactions、allowedActions | 前者只含当前用户获准看到的问卷，可包含已答内容；后者是责任人的整单控制动作，不代表回答权限。步骤和待办的状态独立。 |

收到版本 3 的待办通知后，参与人 u2 重读同一详情，实际形状如下。s2 仍在执行，整单保持 RUNNING。u2 的 allowedActions 为空也不妨碍回答这个 WAITING 待办。提交成功后重读时，获授权的用户还可看到 status=ANSWERED 和 answer；交接取消未答项时是 CANCELLED。

```json
{
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
  "status": "RUNNING",
  "owner": {
    "id": "u1",
    "name": "王一"
  },
  "creator": "财务工作台",
  "responsibleAgent": {
    "id": "ledger-a",
    "name": "账务助手 A"
  },
  "executingAgents": [
    {
      "id": "ledger-a",
      "name": "账务助手 A"
    }
  ],
  "participants": [
    {
      "id": "u2",
      "name": "李二"
    }
  ],
  "taskId": "task_0123456789abcdef0123456789abcdef",
  "assignmentVersion": 1,
  "taskVersion": 3,
  "createdAt": "2026-11-28T01:00:00Z",
  "groups": [
    {
      "groupId": "g1",
      "title": "账务复核",
      "sortOrder": 0,
      "removed": false,
      "progress": {
        "completed": 0,
        "total": 2
      }
    }
  ],
  "steps": [
    {
      "stepId": "s1",
      "groupId": "g1",
      "title": "核对报表",
      "sortOrder": 0,
      "status": "WAITING_HUMAN",
      "removed": false,
      "stepVersion": 2,
      "startedAt": "2026-11-28T01:01:00Z",
      "elapsedMs": 60000
    },
    {
      "stepId": "s2",
      "groupId": "g1",
      "title": "复核差异",
      "sortOrder": 1,
      "status": "RUNNING",
      "removed": false,
      "stepVersion": 1,
      "startedAt": "2026-11-28T01:01:00Z",
      "elapsedMs": 60000
    }
  ],
  "currentStepIds": [
    "s1",
    "s2"
  ],
  "interactions": [
    {
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
      ],
      "status": "WAITING"
    }
  ],
  "allowedActions": [],
  "startedAt": "2026-11-28T01:01:00Z",
  "elapsedMs": 60000,
  "progress": {
    "completed": 0,
    "total": 2
  }
}
```

### 3. 补取业务事件

```http
GET /v1/chat/tasks/task_0123456789abcdef0123456789abcdef/events?afterVersion=2&limit=2 HTTP/1.1
```

200 按版本升序，严格不含 afterVersion；默认从 0 读 50 条，最多 200。下例返回版本 3 的变更和版本 4 的命令受理。注意请求方看不到问卷正文和答案；需要刷新已授权的详情。410 REFRESH_TASK 时不要继续用旧游标。

```json
[
  {
    "taskVersion": 3,
    "changes": [
      {
        "type": "step.updated",
        "objectId": "s1",
        "data": {
          "stepId": "s1",
          "status": "WAITING_HUMAN"
        }
      },
      {
        "type": "interaction.requested",
        "objectId": "i1",
        "data": {
          "interactionId": "i1",
          "stepId": "s1"
        }
      }
    ]
  },
  {
    "taskVersion": 4,
    "changes": [
      {
        "type": "command.accepted",
        "action": "ANSWER"
      }
    ]
  }
]
```

### 4. 读取无步骤的任务执行过程

```http
GET /v1/chat/tasks/task_0123456789abcdef0123456789abcdef/process?offset=0&limit=2 HTTP/1.1
```

200 示例：Agent 在规划阶段的独立操作已结束，**此时任务仍可为 PENDING**。服务端保存正文 content 和用于分段展示的 parts；不要把 ANSWER part 与 content 再拼接一次。

```json
{
  "operations": [
    {
      "operationId": "plan-report",
      "agentId": "ledger-a",
      "status": "COMPLETED",
      "content": "开始规划。",
      "parts": [
        {
          "partType": "ANSWER",
          "sourceType": "message.snapshot",
          "contentText": "开始规划。",
          "title": "最终回答",
          "status": "COMPLETED",
          "channel": "answer",
          "displayHint": "hidden",
          "visible": false,
          "payload": {
            "content": "开始规划。",
            "serverTimestampMs": 1795827601000
          },
          "partOrder": 1
        }
      ]
    }
  ],
  "interactions": [],
  "lastOutputSeq": 2
}
```

### 5. 读取某一步的执行过程

```http
GET /v1/chat/tasks/task_0123456789abcdef0123456789abcdef/steps/s1/process?offset=0&limit=2 HTTP/1.1
```

200 示例为刚规划、尚未有输出的 s1。与上一接口字段相同，但 lastOutputSeq 是**该步骤独立**的输出游标，不是任务级的 2。

```json
{
  "operations": [],
  "interactions": [],
  "lastOutputSeq": 0
}
```

两个 process 接口按**操作**分页，offset 默认 0，limit 默认 2，最大 4；满页时给 nextOffset，下一页可以为空。operationId 划分一次操作、agentId 是执行者、status 是操作状态；parts 按 partOrder 排列，遵循 visible/channel/displayHint 展示。interactions 按范围及用户过滤。lastOutputSeq 是整个作用域在本次快照时的水位，不是当前页的行号。首次显示先取过程快照再订阅；断线补不到时重新读取需要显示的各页。非法分页返回 400，资源不存在或不可见按 404 处理。

同一接口将 stepId 换成 s2：B 在交接后独立输出并结束一次操作时，响应如下。此时业务版本仍可为 6；这是 s2 的游标 2，不是 s1 的游标 9。

```json
{
  "operations": [
    {
      "operationId": "review-final",
      "agentId": "ledger-b",
      "status": "COMPLETED",
      "content": "无剩余差异。",
      "parts": [
        {
          "partType": "ANSWER",
          "sourceType": "message.snapshot",
          "contentText": "无剩余差异。",
          "title": "最终回答",
          "status": "COMPLETED",
          "channel": "answer",
          "displayHint": "hidden",
          "visible": false,
          "payload": {
            "content": "无剩余差异。",
            "serverTimestampMs": 1795827900000
          },
          "partOrder": 1
        }
      ]
    }
  ],
  "interactions": [],
  "lastOutputSeq": 2
}
```

### 6. 用户控制与人工回答

```http
POST /v1/chat/tasks/task_0123456789abcdef0123456789abcdef/actions HTTP/1.1
Content-Type: application/json
```

action、expectedVersion（当前整单版本）、idempotencyKey 必填；每次**新的用户意图**用新键，同一次网络重发保留键与原 body。首次受理写入命令并推进一个版本；重发同内容返回原 commandId。所有合法响应均为 202，不代表 Agent 已经暂停、恢复或完成。

| 用户与状态 | 可发动作 |
|---|---|
| 责任人，PENDING | START、TERMINATE |
| 责任人，RUNNING / WAITING_HUMAN | PAUSE、TERMINATE |
| 责任人，PAUSED / FAILED | RESUME、TERMINATE |
| 责任人，COMPLETED / TERMINATED | 只能用独立 DELETE 接口，不能 POST action=DELETE |
| 责任人或该待办指定的参与人，未终态且待办可答 | ANSWER：额外传 interactionId 与 answer.label。单选传非空字符串，多选传非空字符串数组，键必须是原题全文，可只答部分；不套用聊天问卷中的 approved/ignore 协议。 |

以下五对为不同状态下的完整请求体和 202 响应，只有 ANSWER 是贯穿示例中的版本 3 → 4。

**START 请求 → 202 响应**
```json
{"action":"START","expectedVersion":1,"idempotencyKey":"start-01"}
```

```json
{"commandId":"cmd_00000000000000000000000000000001"}
```


**PAUSE 请求 → 202 响应**
```json
{"action":"PAUSE","expectedVersion":2,"idempotencyKey":"pause-01"}
```

```json
{"commandId":"cmd_00000000000000000000000000000002"}
```


**RESUME 请求 → 202 响应**
```json
{"action":"RESUME","expectedVersion":5,"idempotencyKey":"resume-01"}
```

```json
{"commandId":"cmd_00000000000000000000000000000003"}
```


**TERMINATE 请求 → 202 响应**
```json
{"action":"TERMINATE","expectedVersion":2,"idempotencyKey":"terminate-01"}
```

```json
{"commandId":"cmd_00000000000000000000000000000004"}
```


**ANSWER 请求 → 202 响应**
```json
{"action":"ANSWER","expectedVersion":3,"idempotencyKey":"answer-01","interactionId":"i1","answer":{"label":{"通过复核？":"通过"}}}
```

```json
{"commandId":"cmd_00000000000000000000000000000005"}
```


收到 409 STALE_TASK_VERSION / INTERACTION_CLOSED 时重读详情，让用户针对新状态决定，不要自动重提旧动作；同键不同内容是 409 COMMAND_CONFLICT。无控制权返回 403，不可见的任务返回 404。

### 7. 打开自己的任务会话

```http
POST /v1/chat/tasks/task_0123456789abcdef0123456789abcdef/session HTTP/1.1
```

无需请求体。200 返回当前用户自己的有效关联会话；重复打开复用已有 sessionId。随后调用原 /v1/chat/runs，按原 Run topic / Resume 消费私聊；不要订阅别人的 Run 或把私聊回答复制进共享过程。

```json
{"sessionId":"session_11111111111111111111111111111111"}
```

任务入口、自然语言操作提议仍是**原 Run 流内的 runtime.card**。这里给出两个完整的卡片 payload（不是新 WS 外层类型）；不会包含签名凭据。前者打开任务详情；后者仅在用户确认且仍有权限时用 expectedVersion 提交动作，不能收到卡片就执行。

```json
{"source":"domain-agent","sourceType":"task.reference","cardType":"task.reference","taskId":"task_0123456789abcdef0123456789abcdef"}
```


```json
{"source":"domain-agent","sourceType":"task.action.proposed","cardType":"task.action.proposed","taskId":"task_0123456789abcdef0123456789abcdef","action":"PAUSE","expectedVersion":2}
```


### 8. 删除任务

```http
DELETE /v1/chat/tasks/task_0123456789abcdef0123456789abcdef HTTP/1.1
```

无需请求体，成功 **204，无响应体**。仅责任人且任务已完成或已终止；活动任务先提交 TERMINATE，等 Agent 同步 TERMINATED 再删除。409 TERMINATE_BEFORE_DELETE 不表示已经替你发了终止命令。删除个人聊天会话不是本操作，不终止任务；也不要等待一个不存在的 task.deleted 业务版本帧。

### 9. 查询操作日志

```http
GET /v1/chat/tasks/task_0123456789abcdef0123456789abcdef/logs?offset=0&limit=50 HTTP/1.1
```

200 返回数组，字段为审计投影的**下划线命名**。依次表示记录、操作者、操作者类型、对象、动作、结果和创建时间；不返回私聊正文。按 creation_date 倒序，offset/limit 默认 0/50，最大 200。成员可读，越权或删除后不展示。

```json
[
  {
    "audit_id": "audit_11111111111111111111111111111111",
    "actor_id": "product-a",
    "actor_type": "SERVICE",
    "object_id": "task_0123456789abcdef0123456789abcdef",
    "action_type": "task.created",
    "outcome": "ACCEPTED",
    "creation_date": "2026-11-28T01:00:00Z"
  }
]
```

用户接口的业务错误体示例：
```json
{"code":"STALE_TASK_VERSION"}
```

400 查参数；403 查可信身份/动作资格；404 停止展示不可访问对象；409 重新读详情；410 重建快照。代理、框架产生的传输错误不保证使用同一 JSON 外形。

## 二、同一条 WS 上消费三种 topic

复用已认证的 /v1/chat/ws。下列代码框**每个都是一条独立的完整 JSON 文本消息**；不要打包成数组发送。真实 taskId 必须形如 task_ 加 32 位小写十六进制；步骤 ID 最长 100 字符，限字母、数字、下划线、点、连字符。

| topic | 初始基线 | 消费规则 |
|---|---|---|
| task:{taskId} | GET 详情的 taskVersion | details.event={taskVersion,changes}；offset 是这个版本的字符串。 |
| task:{taskId}:process | GET /process 的 lastOutputSeq | details.event={operationId,outputSeq,type,payload}，只含无步骤的操作。 |
| task:{taskId}:step:{stepId} | GET /steps/{stepId}/process 的 lastOutputSeq | 同一事件形状，仅该步；多个操作可共用这个输出游标。 |

每个 topic 独立保存**最后已成功应用**的 offset，跨重连按数值使用（注意 JS 大整数精度，不按字符串排序）。订阅命令的 afterSeq 为数值，语义是“这个位置之后”。新业务外层只有 `task-event`，从 `details.event` 读取；不是普通 Run 的 message/payload/encodedItem，也不是 Agent 上报的 STATE_CHANGE/OUTPUT_FRAMES。reply/error 用 id 对应客户端命令。

### 状态订阅：从初始快照到交接和完成

先执行接口 2 取得版本 1。客户端 → 服务端订阅，然后服务端 → 客户端确认：


```json
{"id":"st1","type":"subscribe","topicId":"task:task_0123456789abcdef0123456789abcdef","afterSeq":1}
```

```json
{"id":"st1","type":"reply","reply":{"type":"subscribe","topicId":"task:task_0123456789abcdef0123456789abcdef"}}
```


服务端随后推送版本 2（规划）和 3（单步待办）。一个批次一条信封；`changes[].data` 只复制公开白名单：title、description、status、statusDescription、progressSummary、responsibleAgent、executingAgents、groupId、stepId、sortOrder、summary、interactionId。没有的字段不是清空。全量成员、权限、问卷、答案、计数、taskVersion 之外的指派信息以详情为准。

```json
{"type":"task-event","topicId":"task:task_0123456789abcdef0123456789abcdef","offset":"2","details":{"event":{"taskVersion":2,"changes":[{"type":"task.updated","objectId":"task_0123456789abcdef0123456789abcdef","data":{"status":"RUNNING","executingAgents":[{"id":"ledger-a","name":"账务助手 A"}]}},{"type":"group.upsert","objectId":"g1","data":{"groupId":"g1","title":"账务复核","sortOrder":0}},{"type":"step.upsert","objectId":"s1","data":{"stepId":"s1","groupId":"g1","title":"核对报表","sortOrder":0,"status":"RUNNING"}},{"type":"step.upsert","objectId":"s2","data":{"stepId":"s2","groupId":"g1","title":"复核差异","sortOrder":1,"status":"RUNNING"}}]}}}
```

```json
{"type":"task-event","topicId":"task:task_0123456789abcdef0123456789abcdef","offset":"3","details":{"event":{"taskVersion":3,"changes":[{"type":"step.updated","objectId":"s1","data":{"stepId":"s1","status":"WAITING_HUMAN"}},{"type":"interaction.requested","objectId":"i1","data":{"interactionId":"i1","stepId":"s1"}}]}}}
```

客户端收起页面退订 → 服务端确认；连接断开不需要先发退订：


```json
{"id":"st-off","type":"unsubscribe","topicId":"task:task_0123456789abcdef0123456789abcdef"}
```

```json
{"id":"st-off","type":"reply","reply":{"type":"unsubscribe","topicId":"task:task_0123456789abcdef0123456789abcdef"}}
```


下次打开，若保留版本 3 的基线，从 3 续订；否则先取新快照。下面依次为客户端订阅、服务端确认、服务端从 4 开始的所有变化：
```json
{"id":"st2","type":"subscribe","topicId":"task:task_0123456789abcdef0123456789abcdef","afterSeq":3}
```

```json
{"id":"st2","type":"reply","reply":{"type":"subscribe","topicId":"task:task_0123456789abcdef0123456789abcdef"}}
```

```json
{"type":"task-event","topicId":"task:task_0123456789abcdef0123456789abcdef","offset":"4","details":{"event":{"taskVersion":4,"changes":[{"type":"command.accepted","action":"ANSWER"}]}}}
```

```json
{"type":"task-event","topicId":"task:task_0123456789abcdef0123456789abcdef","offset":"5","details":{"event":{"taskVersion":5,"changes":[{"type":"step.updated","objectId":"s1","data":{"stepId":"s1","status":"COMPLETED","summary":"已核对，人工复核通过。"}}]}}}
```

```json
{"type":"task-event","topicId":"task:task_0123456789abcdef0123456789abcdef","offset":"6","details":{"event":{"taskVersion":6,"changes":[{"type":"task.updated","objectId":"task_0123456789abcdef0123456789abcdef","data":{"executingAgents":[{"id":"ledger-b","name":"账务助手 B"}]}},{"type":"task.assigned","objectId":"task_0123456789abcdef0123456789abcdef","data":{"responsibleAgent":{"id":"ledger-b","name":"账务助手 B"}}}]}}}
```

```json
{"type":"task-event","topicId":"task:task_0123456789abcdef0123456789abcdef","offset":"7","details":{"event":{"taskVersion":7,"changes":[{"type":"step.updated","objectId":"s2","data":{"stepId":"s2","status":"COMPLETED","summary":"未发现未解决差异。"}}]}}}
```

```json
{"type":"task-event","topicId":"task:task_0123456789abcdef0123456789abcdef","offset":"8","details":{"event":{"taskVersion":8,"changes":[{"type":"task.updated","objectId":"task_0123456789abcdef0123456789abcdef","data":{"status":"COMPLETED","executingAgents":[]}}]}}}
```


其他变化的完整实例：若从 0 回放，第一条是 task.created；若在版本 2 选择**移除计划而不是继续**，第三条会是下面的移除批次。task.created 和 command.accepted 是服务器生成的，不接受 Agent 将它们当成 /sync 类型上报。

```json
{"type":"task-event","topicId":"task:task_0123456789abcdef0123456789abcdef","offset":"1","details":{"event":{"taskVersion":1,"changes":[{"type":"task.created"}]}}}
```

```json
{"type":"task-event","topicId":"task:task_0123456789abcdef0123456789abcdef","offset":"3","details":{"event":{"taskVersion":3,"changes":[{"type":"step.removed","objectId":"s1","data":{"stepId":"s1"}},{"type":"step.removed","objectId":"s2","data":{"stepId":"s2"}},{"type":"group.removed","objectId":"g1","data":{"groupId":"g1"}}]}}}
```

### 流式输出订阅：首批、退订、补发和尾帧

无步骤时可以先看整单规划输出。若从初始 /process 水位 0 订阅，以下分别为订阅、确认、两条服务端帧：


```json
{"id":"tp","type":"subscribe","topicId":"task:task_0123456789abcdef0123456789abcdef:process","afterSeq":0}
```

```json
{"id":"tp","type":"reply","reply":{"type":"subscribe","topicId":"task:task_0123456789abcdef0123456789abcdef:process"}}
```

```json
{"type":"task-event","topicId":"task:task_0123456789abcdef0123456789abcdef:process","offset":"1","details":{"event":{"operationId":"plan-report","outputSeq":1,"type":"message.delta","payload":{"sourceType":"domain-agent-content","delta":"开始规划。"}}}}
```

```json
{"type":"task-event","topicId":"task:task_0123456789abcdef0123456789abcdef:process","offset":"2","details":{"event":{"operationId":"plan-report","outputSeq":2,"type":"message.completed","payload":{"status":"MESSAGE_COMPLETED","sourceType":"domain-agent-end"}}}}
```


展开 s1，先取接口 5 的水位 0。客户端订阅 → 服务端确认 → 思考与工具输出。对应 Agent 手册的 step 批次：第一个 heartbeat 不产生 WS 帧。

```json
{"id":"step1","type":"subscribe","topicId":"task:task_0123456789abcdef0123456789abcdef:step:s1","afterSeq":0}
```

```json
{"id":"step1","type":"reply","reply":{"type":"subscribe","topicId":"task:task_0123456789abcdef0123456789abcdef:step:s1"}}
```

```json
{"type":"task-event","topicId":"task:task_0123456789abcdef0123456789abcdef:step:s1","offset":"1","details":{"event":{"outputSeq":1,"operationId":"verify","type":"runtime.thinking","payload":{"source":"domain-agent","sourceType":"think","type":"think","thinkTitle":"核对口径"}}}}
```

```json
{"type":"task-event","topicId":"task:task_0123456789abcdef0123456789abcdef:step:s1","offset":"2","details":{"event":{"outputSeq":2,"operationId":"verify","type":"runtime.tool","payload":{"source":"domain-agent","sourceType":"tool","type":"tool","toolTitle":"校验报表"}}}}
```

收起步骤时客户端退订，随后收到 reply；这不会取消 Agent 工作。断开再连，从本步骤已展示的 2 续订，不要使用上面的 taskVersion。

```json
{"id":"step-off","type":"unsubscribe","topicId":"task:task_0123456789abcdef0123456789abcdef:step:s1"}
```

```json
{"id":"step-off","type":"reply","reply":{"type":"unsubscribe","topicId":"task:task_0123456789abcdef0123456789abcdef:step:s1"}}
```

```json
{"id":"step2","type":"subscribe","topicId":"task:task_0123456789abcdef0123456789abcdef:step:s1","afterSeq":2}
```

```json
{"id":"step2","type":"reply","reply":{"type":"subscribe","topicId":"task:task_0123456789abcdef0123456789abcdef:step:s1"}}
```

**步骤输出回放**（服务端 → 前端，顺序完整）：进展、引用、卡片、元数据、未映射的运行事件、正文增量、输出结束。

```json
{"type":"task-event","topicId":"task:task_0123456789abcdef0123456789abcdef:step:s1","offset":"3","details":{"event":{"outputSeq":3,"operationId":"verify","type":"runtime.progress","payload":{"source":"domain-agent","sourceType":"state","state":"GENERATE","stateDesc":"整理结果","stage":"GENERATE","text":"整理结果"}}}}
```

```json
{"type":"task-event","topicId":"task:task_0123456789abcdef0123456789abcdef:step:s1","offset":"4","details":{"event":{"outputSeq":4,"operationId":"verify","type":"runtime.reference","payload":{"source":"domain-agent","sourceType":"sourceDocuments","referenceType":"source_documents","references":["附件A"]}}}}
```

```json
{"type":"task-event","topicId":"task:task_0123456789abcdef0123456789abcdef:step:s1","offset":"5","details":{"event":{"outputSeq":5,"operationId":"verify","type":"runtime.card","payload":{"source":"domain-agent","sourceType":"cardUrl","cardType":"url","cardSources":["cardUrl"],"cardUrl":"https://assets.example.invalid/report.js"}}}}
```

```json
{"type":"task-event","topicId":"task:task_0123456789abcdef0123456789abcdef:step:s1","offset":"6","details":{"event":{"outputSeq":6,"operationId":"verify","type":"runtime.metadata","payload":{"source":"domain-agent","sourceType":"trace","metadataType":"trace","traceId":"downstream-demo"}}}}
```

```json
{"type":"task-event","topicId":"task:task_0123456789abcdef0123456789abcdef:step:s1","offset":"7","details":{"event":{"outputSeq":7,"operationId":"verify","type":"runtime.event","payload":{"source":"domain-agent","sourceType":"CHECK","eventKind":"state","channel":"runtime","displayHint":"inline","sourcePayload":{"source":"domain-agent","sourceType":"state","state":"CHECK","eventKind":"state"}}}}}
```

```json
{"type":"task-event","topicId":"task:task_0123456789abcdef0123456789abcdef:step:s1","offset":"8","details":{"event":{"outputSeq":8,"operationId":"verify","type":"message.delta","payload":{"delta":"账表一致。","sourceType":"domain-agent-content"}}}}
```

```json
{"type":"task-event","topicId":"task:task_0123456789abcdef0123456789abcdef:step:s1","offset":"9","details":{"event":{"outputSeq":9,"operationId":"verify","type":"message.completed","payload":{"status":"MESSAGE_COMPLETED","sourceType":"domain-agent-end"}}}}
```

| 内层 type | 展示作用 |
|---|---|
| runtime.thinking / runtime.tool | 思考分段（兼容 content 中的 think 标签）和工具详情；不是正文。 |
| runtime.progress / runtime.reference / runtime.card | 阶段或 processResult、检索/文档引用、标准化业务卡片。不同 sourceType 的具体 payload 沿用 [DomainAgent 转发协议](agent-runtime-forwarding.md)。 |
| runtime.metadata / runtime.event | 下游会话、追踪、控制信息或其他事件；按展示约定处理，不拿它们改变工作台任务状态。 |
| message.delta / message.completed | 增量追加到相应 operationId；只结束本次输出。不要据此推测单步结束、整单结束或自动发送动作。 |

一帧原始输入可能产生多个事件（例如含思考标签的正文），也可能不产生事件。前端水位只随接收到的输出推进。问卷待办在**状态** topic 的 interaction.requested 通知后取详情，不从共享文本提取题目或答案。

### 订阅失败与刷新

首次订阅即被拒：服务端返回下例，无 topicId，由 id 找到原订阅。核对身份、任务、步骤和游标，再取对应快照；不要无限重试。

```json
{"id":"step1","type":"error","code":"TASK_SUBSCRIBE_REJECTED","message":"任务不存在、无权限或游标无效"}
```

已订阅后回放不可用、失去权限或读取失败：本订阅停止。重新鉴权；仍有权则重读**对应**详情并从新水位订阅，无权则关闭展示。其他 topic 的游标不要一起清零。

```json
{"id":"step2","type":"error","code":"TASK_REFRESH_REQUIRED","message":"请重新读取任务详情并检查权限"}
```


## 三、联调核对

外部建单得到版本 1 → 任务级输出到游标 2（业务版本仍为 1）→ A 规划两个并行步骤（2）→ s1 输出到游标 9（业务版本仍为 2）→ A 请求人工（3）→ u2 提交 ANSWER 得到 202（4）→ A 确认 s1 完成（5）→ A 交给 B（6，指派版本 2）→ B 确认 s2 完成（7）→ B 显式结束整单（8）。分别断开状态和步骤订阅再重连，确认过程不丢段、不重复，且从未把用户私聊发到共享 topic。
