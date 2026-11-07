-- 新增独立追踪表：在选定数据库执行，不修改或回填原业务表。所有 DATETIME 按 UTC 写入。
CREATE TABLE IF NOT EXISTS generation_trace (
    trace_id CHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL PRIMARY KEY,
    root_span_id CHAR(16) CHARACTER SET ascii NOT NULL,
    trace_kind VARCHAR(16) NOT NULL,
    request_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    task_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    user_id BIGINT NOT NULL,
    app_id BIGINT NULL,
    creation_link_state VARCHAR(16) NULL,
    creation_trace_id CHAR(32) CHARACTER SET ascii NULL,
    generation_mode VARCHAR(32) NULL,
    code_gen_type VARCHAR(32) NULL,
    execution_status VARCHAR(24) NOT NULL DEFAULT 'RUNNING',
    recording_status VARCHAR(16) NOT NULL DEFAULT 'COLLECTING',
    started_at DATETIME(3) NOT NULL,
    finished_at DATETIME(3) NULL,
    expires_at DATETIME(3) NOT NULL,
    duration_ms BIGINT NULL,
    service_instance_id VARCHAR(64) NOT NULL,
    model_calls BIGINT NOT NULL DEFAULT 0,
    input_reported_calls BIGINT NOT NULL DEFAULT 0,
    output_reported_calls BIGINT NOT NULL DEFAULT 0,
    known_input_tokens BIGINT NULL,
    known_output_tokens BIGINT NULL,
    failure_category VARCHAR(64) NULL,
    safe_message VARCHAR(2048) NULL,
    artifact_ref VARCHAR(512) NULL,
    artifact_digest CHAR(64) CHARACTER SET ascii NULL,
    revision BIGINT NOT NULL DEFAULT 0,
    last_persisted_sequence BIGINT NOT NULL DEFAULT 0,
    UNIQUE KEY uk_generation_trace_request (request_id),
    UNIQUE KEY uk_generation_trace_task (task_id),
    KEY idx_generation_trace_owner_app_time (user_id, app_id, started_at, trace_id),
    KEY idx_generation_trace_expiry (expires_at),
    KEY idx_generation_trace_app (app_id),
    CONSTRAINT ck_generation_trace_identity CHECK (
        (trace_kind = 'CREATE' AND request_id IS NOT NULL)
        OR (trace_kind = 'GENERATION' AND task_id IS NOT NULL AND app_id IS NOT NULL)),
    CONSTRAINT ck_generation_trace_usage CHECK (
        model_calls >= 0 AND input_reported_calls >= 0 AND output_reported_calls >= 0
        AND (known_input_tokens IS NULL OR known_input_tokens >= 0)
        AND (known_output_tokens IS NULL OR known_output_tokens >= 0)
        AND (duration_ms IS NULL OR duration_ms >= 0))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='七天生成追踪索引';

CREATE TABLE IF NOT EXISTS generation_trace_step (
    trace_id CHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    span_id CHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    parent_span_id CHAR(16) CHARACTER SET ascii NULL,
    step_no BIGINT NOT NULL,
    attempt_no INT NOT NULL,
    step_kind VARCHAR(32) NOT NULL,
    step_name VARCHAR(128) NOT NULL,
    status VARCHAR(24) NOT NULL DEFAULT 'RUNNING',
    started_at DATETIME(3) NOT NULL,
    finished_at DATETIME(3) NULL,
    duration_ms BIGINT NULL,
    provider_alias VARCHAR(128) NULL,
    model_name VARCHAR(256) NULL,
    prompt_version CHAR(64) CHARACTER SET ascii NULL,
    config_digest CHAR(64) CHARACTER SET ascii NULL,
    finish_reason VARCHAR(64) NULL,
    input_tokens BIGINT NULL,
    output_tokens BIGINT NULL,
    safe_metadata JSON NULL,
    error_category VARCHAR(64) NULL,
    safe_message VARCHAR(2048) NULL,
    revision BIGINT NOT NULL DEFAULT 0,
    PRIMARY KEY (trace_id, span_id),
    UNIQUE KEY uk_generation_trace_step_order (trace_id, step_no),
    CONSTRAINT ck_generation_trace_step_usage CHECK (
        step_no > 0 AND attempt_no > 0 AND (input_tokens IS NULL OR input_tokens >= 0)
        AND (output_tokens IS NULL OR output_tokens >= 0) AND (duration_ms IS NULL OR duration_ms >= 0))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='生成过程步骤元数据';
