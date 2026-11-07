-- 个人知识库 MVP：在目标业务库内执行，可重复执行，不修改既有表。
CREATE TABLE IF NOT EXISTS knowledge_space (
    owner_id BIGINT NOT NULL PRIMARY KEY,
    collection_key VARCHAR(128) NOT NULL UNIQUE,
    embedding_profile VARCHAR(128) NULL,
    created_at DATETIME(3) NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE IF NOT EXISTS knowledge_document (
    id BIGINT NOT NULL PRIMARY KEY,
    owner_id BIGINT NOT NULL,
    name VARCHAR(255) NOT NULL,
    active_version_id BIGINT NULL,
    pending_version_id BIGINT NULL,
    reserved_bytes BIGINT NOT NULL DEFAULT 0,
    revision BIGINT NOT NULL DEFAULT 0,
    deleted_at DATETIME(3) NULL,
    created_at DATETIME(3) NOT NULL,
    INDEX idx_knowledge_document_owner (owner_id,deleted_at,created_at,id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE IF NOT EXISTS knowledge_document_version (
    id BIGINT NOT NULL PRIMARY KEY,
    document_id BIGINT NOT NULL,
    owner_id BIGINT NOT NULL,
    file_name VARCHAR(255) NOT NULL,
    version_no INT NOT NULL,
    status VARCHAR(24) NOT NULL,
    byte_size BIGINT NOT NULL,
    content_hash CHAR(64) NOT NULL,
    raw_content MEDIUMBLOB NULL,
    normalized_text MEDIUMTEXT NULL,
    embedding_profile VARCHAR(128) NULL,
    chunk_count INT NOT NULL DEFAULT 0,
    attempt INT NOT NULL DEFAULT 1,
    processing_build_id CHAR(36) NOT NULL,
    published_build_id CHAR(36) NULL,
    lease_until DATETIME(3) NOT NULL,
    error_code VARCHAR(64) NULL,
    created_at DATETIME(3) NOT NULL,
    UNIQUE KEY uk_knowledge_version (document_id,version_no),
    INDEX idx_knowledge_processing (status,lease_until)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE IF NOT EXISTS knowledge_operation (
    id BIGINT NOT NULL PRIMARY KEY,
    owner_id BIGINT NOT NULL,
    operation_type VARCHAR(24) NOT NULL,
    client_request_id CHAR(36) NOT NULL,
    request_hash CHAR(64) NOT NULL,
    document_id BIGINT NOT NULL,
    version_id BIGINT NOT NULL,
    attempt INT NOT NULL,
    build_id CHAR(36) NOT NULL,
    status VARCHAR(24) NOT NULL,
    result_code VARCHAR(64) NULL,
    created_at DATETIME(3) NOT NULL,
    UNIQUE KEY uk_knowledge_request (owner_id,operation_type,client_request_id),
    INDEX idx_knowledge_operation_version (version_id,attempt)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE IF NOT EXISTS knowledge_task_reference (
    task_id CHAR(36) NOT NULL PRIMARY KEY,
    app_id BIGINT NOT NULL,
    owner_id BIGINT NOT NULL,
    user_message_id BIGINT NULL,
    ai_message_id BIGINT NULL,
    state VARCHAR(24) NOT NULL,
    snapshot_digest CHAR(64) NULL,
    created_at DATETIME(3) NOT NULL,
    submitted_at DATETIME(3) NULL,
    INDEX idx_knowledge_reference_app (app_id,created_at,task_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE IF NOT EXISTS knowledge_reference_item (
    task_id CHAR(36) NOT NULL,
    ordinal_no INT NOT NULL,
    document_id BIGINT NOT NULL,
    version_id BIGINT NOT NULL,
    document_name VARCHAR(255) NOT NULL,
    version_no INT NOT NULL,
    build_id CHAR(36) NOT NULL,
    chunk_id VARCHAR(128) NOT NULL,
    text MEDIUMTEXT NULL,
    content_hash CHAR(64) NOT NULL,
    score DOUBLE NOT NULL,
    visibility VARCHAR(24) NOT NULL,
    PRIMARY KEY(task_id,ordinal_no),
    INDEX idx_knowledge_item_document(document_id,task_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
