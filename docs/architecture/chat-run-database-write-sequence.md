# Chat Run 主流程数据库写入时序

主线：前端通过 `POST /v1/chat/runs` 发起普通 `NEXT` 提问，使用 `FULL` 留存，DomainAgent 或 Relay 正常回答。图中均为[初始化 DDL](../../src/main/resources/db/init-20260718.sql) 中的完整物理表名。色块标出准入和正常完成两个事务；启动响应只表示首事件已持久化，不表示回答已经保存。

**观测图例：** 指向 ObservationWriter 的虚线表示提交快照；从 ObservationWriter 指向数据库的实线表示后台在独立事务中按主键写入或更新（UPSERT）。箭头位置表示快照的触发时点，不保证 SQL 实际执行顺序。业务响应不等观测写库；队列满或写入失败时可能缺少记录。`financeex.observability.enabled=false`（默认）时，跳过全部观测步骤，两张监测表均不写。

```mermaid
sequenceDiagram
    autonumber
    actor U as 前端
    participant C as ChatService
    participant D as 业务数据库
    participant W as ObservationWriter（异步写入）
    participant I as Intent 服务
    participant A as DomainAgent 或 Relay

    U->>C: POST /v1/chat/runs
    C--)W: 首次订阅 Run 启动流程，提交 Trace STARTED
    W->>D: UPSERT fin_ex_turn_trace_t（trace_id）
    opt 需要创建新会话
        C->>D: INSERT fin_ex_chat_session_t
    end

    rect rgb(235, 245, 255)
        Note over C,D: 准入事务：保存用户问题并创建 Run
        C->>D: 锁定 fin_ex_chat_session_t，更新消息节点序号
        C->>D: INSERT fin_ex_chat_message_t（role=user）
        opt 本次提问携带附件
            C->>D: INSERT fin_ex_chat_message_attachment_t（关联已有文件）
        end
        C->>D: UPDATE fin_ex_chat_session_t（当前叶节点指向用户消息）
        C->>D: INSERT fin_ex_chat_run_t（RUNNING，关联用户消息）
    end

    C--)W: 准入事务已提交，提交同一 Trace 的 RUNNING 快照
    W->>D: UPSERT fin_ex_turn_trace_t
    C->>D: 创建 fin_ex_chat_run_execution_t（执行实例、执行权和租约）
    C->>D: INSERT fin_ex_chat_event_t（run.started）
    C->>D: UPDATE fin_ex_chat_run_t（记录首事件序号）
    C-->>U: 首事件已持久化，返回 Run 启动结果

    opt 本轮需要意图识别
        C--)W: Intent 实际调用开始，提交 Observation STARTED
        W->>D: UPSERT fin_ex_agent_observation_t（本次 observation_id）
        C->>I: 提交意图识别请求
        I-->>C: 返回识别结果
        C--)W: Intent 尝试结束，提交 FINISHED 和调用结果
        W->>D: UPSERT fin_ex_agent_observation_t（同一 observation_id）
    end
    C->>D: 按路由结果创建、恢复或复用 fin_ex_runtime_binding_t
    C->>D: UPDATE fin_ex_chat_run_t（记录本轮实际路由）
    C--)W: Agent 实际调用开始，同一 trace_id，新的 observation_id
    W->>D: UPSERT fin_ex_agent_observation_t（STARTED）
    C->>A: 调用选定的 DomainAgent 或 Relay
    loop 收到需要持久化的业务事件
        A-->>C: 返回正文、卡片、思考等数据帧
        Note over C: 开启观测时分别判断 Trace 和 Observation 首帧，只在内存采样
        C->>D: 按事件或批次写入 fin_ex_chat_event_t
        C-->>U: 实时发布事件，不逐帧写历史 Parts
    end
    C--)W: Agent 尝试结束，提交 FINISHED 和调用结果
    W->>D: UPSERT fin_ex_agent_observation_t（含已采到的首帧时间）

    alt 正常完成（主线）
        rect rgb(235, 249, 239)
            Note over C,D: 正常完成事务：先锁会话，再校验执行权
            C->>D: INSERT fin_ex_chat_event_t（run.completed）
            C->>D: INSERT fin_ex_chat_message_t（role=assistant）
            C->>D: 写入 fin_ex_chat_message_part_t（组装出的历史 Parts）
            C->>D: UPDATE fin_ex_chat_session_t（叶节点指向回答，推进最新消息序号）
            C->>D: UPDATE fin_ex_chat_run_t（关联 assistant 消息）
            C->>D: 按完成策略更新或激活 fin_ex_runtime_binding_t
            C->>D: UPDATE fin_ex_chat_run_t（COMPLETED）
            C->>D: UPDATE fin_ex_chat_run_execution_t（COMPLETED）
        end
        C--)W: 完成事务已提交，提交 Trace COMPLETED
        W->>D: UPSERT fin_ex_turn_trace_t（结束时间、总耗时）
    else 进入等待
        C--)W: 等待事务已提交，提交 WAITING_USER 或 ASYNC_WAITING
        W->>D: UPSERT fin_ex_turn_trace_t（保留 trace_id，不写结束时间）
    else 失败或取消
        C--)W: 终态事务已提交，提交 FAILED 或 CANCELLED
        W->>D: UPSERT fin_ex_turn_trace_t（结束时间、总耗时）
    end
    C-->>U: 业务事务提交后发布对应事件，不等观测写入
```

## 条件分支的写入

| 场景 | 与正常完成路径的区别 |
|---|---|
| 澄清、模糊候选、Ask User、拒答等待确认 | 在等待事务中，`fin_ex_chat_event_t` 写入 `run.waiting_user`，`fin_ex_chat_message_t` 和 `fin_ex_chat_message_part_t` 保存阶段性回答，新增 `fin_ex_chat_interaction_request_t`，`fin_ex_chat_run_t` 和 `fin_ex_chat_run_execution_t` 进入 `WAITING_USER`。用户提交后认领 Interaction 并创建新的 Run 和 Execution，不将原 Run 改回 `RUNNING`。 |
| DomainAgent 拒答后自动重新路由 | 需要取消原绑定时，在同一事务内将拒答事件写入 `fin_ex_chat_event_t`、将 `fin_ex_runtime_binding_t` 中的原绑定置为 `CANCELLED`。后续可在同一 Run 内重新路由，不因更换技能新增用户消息。 |
| DomainAgent 异步受理与回调 | 受理后 `fin_ex_chat_run_t` 保持 `RUNNING`，`fin_ex_chat_run_execution_t` 变为 `ASYNC_WAITING`。有效回调更新 `fin_ex_chat_message_t` 中的原 assistant，按结果处理 `fin_ex_chat_message_part_t` 和 `fin_ex_chat_event_t`，并收口原 Run 和 Execution。没有独立的异步任务表。 |
| Stop、失败、Watchdog | `fin_ex_chat_event_t` 保存对应终态事件，`fin_ex_chat_run_t` 和 `fin_ex_chat_run_execution_t` 更新终态。是否在 `fin_ex_chat_message_t` 保存部分回答取决于具体路径，不是每次失败都会新增 assistant。 |
| 意图记录、路由记忆 | 启用意图记录后，符合条件的识别结果异步写入 `fin_ex_intent_recognition_t`；`fin_ex_route_memory_t` 按路由记忆规则写入。 |
| 观测与重试 | `fin_ex_turn_trace_t` 以 `trace_id` 标识整轮问答，正常续跑继承同一 Trace，包括等待时间。`fin_ex_agent_observation_t` 以 `observation_id` 标识一次实际调用，重试使用新 ID；直连跳过 Intent 时没有 Intent 调用记录。两级首帧随之后的快照保存，不为每帧写监测表。详见[问答 Trace 与 Agent 调用观测](../observability.md)。 |

上传文件和提问引用文件是两个操作：上传涉及 `fin_ex_uploaded_document_t`，提问时仅在 `fin_ex_chat_message_attachment_t` 写消息与文件的关联。

实时事件与历史 Parts 也不是一一对应：`FULL` 下，流中事件先写 `fin_ex_chat_event_t`，历史 Parts 在完成或进入等待时组装后写 `fin_ex_chat_message_part_t`。`no-store` 下仍保存必要控制记录，但不按图中的 `FULL` 路径保存真实业务正文和业务 Parts。
