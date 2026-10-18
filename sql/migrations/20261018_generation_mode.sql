-- 执行前确认目标为 ai_code_generate。新增列保留原应用及代码类型。
-- 可重复执行：历史应用由数据库默认值自动归为标准模式。
USE ai_code_generate;
SET @mode_column_exists = (
    SELECT COUNT(*) FROM information_schema.columns
    WHERE table_schema = DATABASE() AND table_name = 'app' AND column_name = 'generationMode'
);
SET @mode_migration = IF(@mode_column_exists = 0,
    'ALTER TABLE app ADD COLUMN generationMode VARCHAR(32) NOT NULL DEFAULT ''standard'' COMMENT ''创建时固定的生成模式：standard/workflow'' AFTER codeGenType',
    'SELECT ''generationMode 已存在，跳过迁移'' AS result');
PREPARE mode_statement FROM @mode_migration;
EXECUTE mode_statement;
DEALLOCATE PREPARE mode_statement;
