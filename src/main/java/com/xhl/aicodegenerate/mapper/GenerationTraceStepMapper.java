package com.xhl.aicodegenerate.mapper;

import com.xhl.aicodegenerate.entity.GenerationTraceStep;
import com.xhl.aicodegenerate.entity.App;
import org.apache.ibatis.annotations.*;
import java.time.Instant;
import java.util.List;

/** 追踪存储使用固定列和参数绑定，更新受版本条件保护。 */
public interface GenerationTraceStepMapper {
    String COLUMNS = "trace_id AS traceId, span_id AS spanId, parent_span_id AS parentSpanId, step_no AS stepNo, attempt_no AS attemptNo, step_kind AS stepKind, step_name AS stepName, status AS status, started_at AS startedAt, finished_at AS finishedAt, duration_ms AS durationMs, provider_alias AS providerAlias, model_name AS modelName, prompt_version AS promptVersion, config_digest AS configDigest, finish_reason AS finishReason, input_tokens AS inputTokens, output_tokens AS outputTokens, safe_metadata AS safeMetadata, error_category AS errorCategory, safe_message AS safeMessage, revision AS revision";

    @Insert("INSERT INTO generation_trace_step (trace_id, span_id, parent_span_id, step_no, attempt_no, step_kind, step_name, status, started_at, finished_at, duration_ms, provider_alias, model_name, prompt_version, config_digest, finish_reason, input_tokens, output_tokens, safe_metadata, error_category, safe_message, revision) VALUES (#{traceId}, #{spanId}, #{parentSpanId}, #{stepNo}, #{attemptNo}, #{stepKind}, #{stepName}, #{status}, #{startedAt,typeHandler=com.xhl.aicodegenerate.observability.UtcInstantTypeHandler}, #{finishedAt,typeHandler=com.xhl.aicodegenerate.observability.UtcInstantTypeHandler}, #{durationMs}, #{providerAlias}, #{modelName}, #{promptVersion}, #{configDigest}, #{finishReason}, #{inputTokens}, #{outputTokens}, #{safeMetadata}, #{errorCategory}, #{safeMessage}, #{revision})")
    void insert(GenerationTraceStep value);

    @Update("UPDATE generation_trace_step SET parent_span_id = #{parentSpanId}, step_no = #{stepNo}, attempt_no = #{attemptNo}, step_kind = #{stepKind}, step_name = #{stepName}, status = #{status}, started_at = #{startedAt,typeHandler=com.xhl.aicodegenerate.observability.UtcInstantTypeHandler}, finished_at = #{finishedAt,typeHandler=com.xhl.aicodegenerate.observability.UtcInstantTypeHandler}, duration_ms = #{durationMs}, provider_alias = #{providerAlias}, model_name = #{modelName}, prompt_version = #{promptVersion}, config_digest = #{configDigest}, finish_reason = #{finishReason}, input_tokens = #{inputTokens}, output_tokens = #{outputTokens}, safe_metadata = #{safeMetadata}, error_category = #{errorCategory}, safe_message = #{safeMessage}, revision = #{revision} WHERE trace_id = #{traceId} AND span_id = #{spanId} AND revision < #{revision}")
    int update(GenerationTraceStep value);

    @Results(id = "traceFields", value = {
        @Result(column = "startedAt", property = "startedAt", typeHandler = com.xhl.aicodegenerate.observability.UtcInstantTypeHandler.class),
        @Result(column = "finishedAt", property = "finishedAt", typeHandler = com.xhl.aicodegenerate.observability.UtcInstantTypeHandler.class)
    })
    @Select("SELECT " + COLUMNS + " FROM generation_trace_step WHERE trace_id = #{traceId} AND span_id = #{spanId}")
    GenerationTraceStep find(@Param("traceId") String traceId, @Param("spanId") String spanId);

    @ResultMap("traceFields")
    @Select("SELECT " + COLUMNS + " FROM generation_trace_step WHERE trace_id = #{traceId} AND step_no > #{cursor} ORDER BY step_no LIMIT #{limit}")
    List<GenerationTraceStep> list(@Param("traceId") String traceId, @Param("cursor") long cursor, @Param("limit") int limit);

    @Delete("DELETE FROM generation_trace_step WHERE trace_id = #{id}")
    void deleteTrace(@Param("id") String id);

    @Update("UPDATE generation_trace_step SET status = 'UNKNOWN' WHERE trace_id = #{id} AND status = 'RUNNING'")
    void interruptOpen(@Param("id") String id);
}
