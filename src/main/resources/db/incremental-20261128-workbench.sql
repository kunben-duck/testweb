-- DBA manual migration; execute in the business schema before enabling workbench.
BEGIN;

CREATE TABLE IF NOT EXISTS fin_ex_wb_task_t (
    task_id NVARCHAR2(64) PRIMARY KEY,
    tenant_id NVARCHAR2(64) NOT NULL,
    owner_user_id NVARCHAR2(64) NOT NULL,
    caller_id NVARCHAR2(64) NOT NULL,
    create_request_id NVARCHAR2(128) NOT NULL,
    create_hash NVARCHAR2(64) NOT NULL,
    create_ack_json TEXT NOT NULL,
    responsible_agent_id NVARCHAR2(128) NOT NULL,
    assignment_version BIGINT NOT NULL,
    task_version BIGINT NOT NULL,
    status NVARCHAR2(32) NOT NULL,
    source_app_id NVARCHAR2(128) NOT NULL,
    l1_key NVARCHAR2(128) NOT NULL,
    l1_name NVARCHAR2(300) NOT NULL,
    l2_key NVARCHAR2(128),
    l3_key NVARCHAR2(128),
    title NVARCHAR2(300) NOT NULL,
    snapshot_json TEXT NOT NULL,
    deleted_at TIMESTAMPTZ,
    creation_date TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    last_update_date TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (tenant_id, caller_id, create_request_id)
);

CREATE TABLE IF NOT EXISTS fin_ex_wb_task_group_t (
    task_id NVARCHAR2(64) NOT NULL,
    group_id NVARCHAR2(128) NOT NULL,
    sort_order INTEGER NOT NULL,
    removed BOOLEAN NOT NULL,
    snapshot_json TEXT NOT NULL,
    creation_date TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    last_update_date TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (task_id, group_id)
);

CREATE TABLE IF NOT EXISTS fin_ex_wb_task_step_t (
    task_id NVARCHAR2(64) NOT NULL,
    step_id NVARCHAR2(128) NOT NULL,
    group_id NVARCHAR2(128),
    sort_order INTEGER NOT NULL,
    status NVARCHAR2(32) NOT NULL,
    removed BOOLEAN NOT NULL,
    step_version BIGINT NOT NULL,
    snapshot_json TEXT NOT NULL,
    creation_date TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    last_update_date TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (task_id, step_id)
);

CREATE TABLE IF NOT EXISTS fin_ex_wb_task_member_t (
    task_id NVARCHAR2(64) NOT NULL,
    user_id NVARCHAR2(64) NOT NULL,
    display_name NVARCHAR2(300) NOT NULL,
    creation_date TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    last_update_date TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (task_id, user_id)
);

CREATE TABLE IF NOT EXISTS fin_ex_wb_task_session_t (
    task_id NVARCHAR2(64) NOT NULL,
    user_id NVARCHAR2(64) NOT NULL,
    session_id NVARCHAR2(64) NOT NULL,
    relation_type NVARCHAR2(32) NOT NULL,
    creation_date TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    last_update_date TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (task_id, user_id),
    UNIQUE (session_id)
);

CREATE TABLE IF NOT EXISTS fin_ex_wb_task_event_t (
    task_id NVARCHAR2(64) NOT NULL,
    task_version BIGINT NOT NULL,
    caller_id NVARCHAR2(64) NOT NULL,
    source_event_id NVARCHAR2(128) NOT NULL,
    payload_hash NVARCHAR2(64) NOT NULL,
    changes_json TEXT NOT NULL,
    ack_json TEXT NOT NULL,
    creation_date TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    last_update_date TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (task_id, task_version),
    UNIQUE (task_id, caller_id, source_event_id)
);

CREATE TABLE IF NOT EXISTS fin_ex_wb_task_output_stream_t (
    task_id NVARCHAR2(64) NOT NULL,
    scope_key NVARCHAR2(128) NOT NULL,
    last_output_seq BIGINT NOT NULL,
    creation_date TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    last_update_date TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (task_id, scope_key)
);

CREATE TABLE IF NOT EXISTS fin_ex_wb_task_operation_t (
    task_id NVARCHAR2(64) NOT NULL,
    operation_id NVARCHAR2(128) NOT NULL,
    scope_key NVARCHAR2(128) NOT NULL,
    agent_id NVARCHAR2(128) NOT NULL,
    assignment_version BIGINT NOT NULL,
    last_frame_no BIGINT NOT NULL,
    status NVARCHAR2(32) NOT NULL,
    content TEXT,
    checkpoint_json TEXT NOT NULL,
    creation_date TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    last_update_date TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (task_id, operation_id)
);

CREATE TABLE IF NOT EXISTS fin_ex_wb_task_op_part_t (
    task_id NVARCHAR2(64) NOT NULL,
    operation_id NVARCHAR2(128) NOT NULL,
    part_order INTEGER NOT NULL,
    part_json TEXT NOT NULL,
    creation_date TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    last_update_date TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (task_id, operation_id, part_order)
);

CREATE TABLE IF NOT EXISTS fin_ex_wb_task_output_frame_t (
    task_id NVARCHAR2(64) NOT NULL,
    scope_key NVARCHAR2(128) NOT NULL,
    operation_id NVARCHAR2(128) NOT NULL,
    first_frame_no BIGINT NOT NULL,
    last_frame_no BIGINT NOT NULL,
    payload_hash NVARCHAR2(64) NOT NULL,
    first_output_seq BIGINT NOT NULL,
    last_output_seq BIGINT NOT NULL,
    events_json TEXT NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    creation_date TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    last_update_date TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (task_id, operation_id, first_frame_no)
);

CREATE TABLE IF NOT EXISTS fin_ex_wb_task_interaction_t (
    task_id NVARCHAR2(64) NOT NULL,
    interaction_id NVARCHAR2(64) NOT NULL,
    agent_id NVARCHAR2(128) NOT NULL,
    assignment_version BIGINT NOT NULL,
    external_request_id NVARCHAR2(128) NOT NULL,
    step_id NVARCHAR2(128),
    operation_id NVARCHAR2(128),
    status NVARCHAR2(32) NOT NULL,
    request_json TEXT NOT NULL,
    answer_json TEXT,
    answered_by NVARCHAR2(64),
    creation_date TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    last_update_date TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (task_id, interaction_id),
    UNIQUE (task_id, agent_id, external_request_id)
);

CREATE TABLE IF NOT EXISTS fin_ex_wb_task_command_t (
    command_id NVARCHAR2(64) PRIMARY KEY,
    task_id NVARCHAR2(64) NOT NULL,
    actor_id NVARCHAR2(64) NOT NULL,
    agent_id NVARCHAR2(128) NOT NULL,
    assignment_version BIGINT NOT NULL,
    idem_key NVARCHAR2(128) NOT NULL,
    payload_hash NVARCHAR2(64) NOT NULL,
    command_type NVARCHAR2(32) NOT NULL,
    payload_json TEXT NOT NULL,
    delivery_status NVARCHAR2(32) NOT NULL,
    attempt_count INTEGER NOT NULL,
    lease_token NVARCHAR2(64),
    next_attempt_at TIMESTAMPTZ NOT NULL,
    creation_date TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    last_update_date TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (task_id, actor_id, idem_key)
);

CREATE TABLE IF NOT EXISTS fin_ex_wb_task_audit_t (
    audit_id NVARCHAR2(64) PRIMARY KEY,
    task_id NVARCHAR2(64) NOT NULL,
    actor_id NVARCHAR2(128) NOT NULL,
    actor_type NVARCHAR2(32) NOT NULL,
    object_id NVARCHAR2(128) NOT NULL,
    action_type NVARCHAR2(128) NOT NULL,
    outcome NVARCHAR2(32) NOT NULL,
    creation_date TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    last_update_date TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS fin_ex_wb_task_schema_t (
    tenant_id NVARCHAR2(64) NOT NULL,
    app_id NVARCHAR2(128) NOT NULL,
    category_key NVARCHAR2(128) NOT NULL,
    schema_version NVARCHAR2(128) NOT NULL,
    definition_json TEXT NOT NULL,
    semantic_doc_ref TEXT NOT NULL,
    creation_date TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    last_update_date TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (tenant_id, app_id, category_key, schema_version)
);

CREATE TABLE IF NOT EXISTS fin_ex_wb_task_ext_value_t (
    task_id NVARCHAR2(64) NOT NULL,
    schema_version NVARCHAR2(128) NOT NULL,
    field_key NVARCHAR2(128) NOT NULL,
    text_value NVARCHAR2(300),
    number_value NUMERIC,
    time_value TIMESTAMPTZ,
    bool_value BOOLEAN,
    creation_date TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    last_update_date TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (task_id, field_key)
);

CREATE INDEX IF NOT EXISTS ix_fx_wb_owner ON fin_ex_wb_task_t (tenant_id, owner_user_id, creation_date, task_id);
CREATE INDEX IF NOT EXISTS ix_fx_wb_member ON fin_ex_wb_task_member_t (user_id, task_id);
CREATE INDEX IF NOT EXISTS ix_fx_wb_frames ON fin_ex_wb_task_output_frame_t (task_id, scope_key, last_output_seq);
CREATE INDEX IF NOT EXISTS ix_fx_wb_expiry ON fin_ex_wb_task_output_frame_t (expires_at);
CREATE INDEX IF NOT EXISTS ix_fx_wb_ops ON fin_ex_wb_task_operation_t (task_id, scope_key, creation_date);
CREATE INDEX IF NOT EXISTS ix_fx_wb_cmd ON fin_ex_wb_task_command_t (delivery_status, next_attempt_at);
CREATE INDEX IF NOT EXISTS ix_fx_wb_audit ON fin_ex_wb_task_audit_t (task_id, creation_date, audit_id);
CREATE UNIQUE INDEX IF NOT EXISTS ix_fx_wb_main ON fin_ex_wb_task_session_t (task_id) WHERE relation_type = 'MAIN';

COMMENT ON TABLE fin_ex_wb_task_t IS '任务当前快照';
COMMENT ON TABLE fin_ex_wb_task_group_t IS '动态步骤分组';
COMMENT ON TABLE fin_ex_wb_task_step_t IS '任务步骤当前状态';
COMMENT ON TABLE fin_ex_wb_task_member_t IS '任务参与人（责任人在任务主表）';
COMMENT ON TABLE fin_ex_wb_task_session_t IS '个人任务会话关联';
COMMENT ON TABLE fin_ex_wb_task_event_t IS '业务状态变更与幂等回执';
COMMENT ON TABLE fin_ex_wb_task_output_stream_t IS '任务级或步骤级执行过程游标';
COMMENT ON TABLE fin_ex_wb_task_operation_t IS '独立操作的正文与解析检查点';
COMMENT ON TABLE fin_ex_wb_task_op_part_t IS '操作的思考、工具和卡片等展示分段';
COMMENT ON TABLE fin_ex_wb_task_output_frame_t IS '有期限的规范化事件批次';
COMMENT ON TABLE fin_ex_wb_task_interaction_t IS '人工确认、作答授权与答案';
COMMENT ON TABLE fin_ex_wb_task_command_t IS '幂等命令与异步投递';
COMMENT ON TABLE fin_ex_wb_task_audit_t IS '任务操作审计';
COMMENT ON TABLE fin_ex_wb_task_schema_t IS '已发布的产品字段定义';
COMMENT ON TABLE fin_ex_wb_task_ext_value_t IS '可查询的类型化扩展字段值';

COMMIT;
