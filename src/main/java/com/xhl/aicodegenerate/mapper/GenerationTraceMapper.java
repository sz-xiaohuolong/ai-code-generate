package com.xhl.aicodegenerate.mapper;

import com.xhl.aicodegenerate.entity.GenerationTrace;
import com.xhl.aicodegenerate.entity.App;
import org.apache.ibatis.annotations.*;
import java.time.Instant;
import java.util.List;

/** 追踪存储使用固定列和参数绑定，更新受版本条件保护。 */
public interface GenerationTraceMapper {
    String COLUMNS = "trace_id AS traceId, root_span_id AS rootSpanId, trace_kind AS traceKind, request_id AS requestId, task_id AS taskId, user_id AS userId, app_id AS appId, creation_link_state AS creationLinkState, creation_trace_id AS creationTraceId, generation_mode AS generationMode, code_gen_type AS codeGenType, execution_status AS executionStatus, recording_status AS recordingStatus, started_at AS startedAt, finished_at AS finishedAt, expires_at AS expiresAt, duration_ms AS durationMs, service_instance_id AS serviceInstanceId, model_calls AS modelCalls, input_reported_calls AS inputReportedCalls, output_reported_calls AS outputReportedCalls, known_input_tokens AS knownInputTokens, known_output_tokens AS knownOutputTokens, failure_category AS failureCategory, safe_message AS safeMessage, artifact_ref AS artifactRef, artifact_digest AS artifactDigest, revision AS revision, last_persisted_sequence AS lastPersistedSequence";

    @Insert("INSERT INTO generation_trace (trace_id, root_span_id, trace_kind, request_id, task_id, user_id, app_id, creation_link_state, creation_trace_id, generation_mode, code_gen_type, execution_status, recording_status, started_at, finished_at, expires_at, duration_ms, service_instance_id, model_calls, input_reported_calls, output_reported_calls, known_input_tokens, known_output_tokens, failure_category, safe_message, artifact_ref, artifact_digest, revision, last_persisted_sequence) VALUES (#{traceId}, #{rootSpanId}, #{traceKind}, #{requestId}, #{taskId}, #{userId}, #{appId}, #{creationLinkState}, #{creationTraceId}, #{generationMode}, #{codeGenType}, #{executionStatus}, #{recordingStatus}, #{startedAt,typeHandler=com.xhl.aicodegenerate.observability.UtcInstantTypeHandler}, #{finishedAt,typeHandler=com.xhl.aicodegenerate.observability.UtcInstantTypeHandler}, #{expiresAt,typeHandler=com.xhl.aicodegenerate.observability.UtcInstantTypeHandler}, #{durationMs}, #{serviceInstanceId}, #{modelCalls}, #{inputReportedCalls}, #{outputReportedCalls}, #{knownInputTokens}, #{knownOutputTokens}, #{failureCategory}, #{safeMessage}, #{artifactRef}, #{artifactDigest}, #{revision}, #{lastPersistedSequence})")
    void insert(GenerationTrace value);

    @Update("UPDATE generation_trace SET root_span_id = #{rootSpanId}, trace_kind = #{traceKind}, request_id = #{requestId}, task_id = #{taskId}, user_id = #{userId}, app_id = #{appId}, creation_link_state = #{creationLinkState}, creation_trace_id = #{creationTraceId}, generation_mode = #{generationMode}, code_gen_type = #{codeGenType}, execution_status = #{executionStatus}, recording_status = #{recordingStatus}, started_at = #{startedAt,typeHandler=com.xhl.aicodegenerate.observability.UtcInstantTypeHandler}, finished_at = #{finishedAt,typeHandler=com.xhl.aicodegenerate.observability.UtcInstantTypeHandler}, expires_at = #{expiresAt,typeHandler=com.xhl.aicodegenerate.observability.UtcInstantTypeHandler}, duration_ms = #{durationMs}, service_instance_id = #{serviceInstanceId}, model_calls = #{modelCalls}, input_reported_calls = #{inputReportedCalls}, output_reported_calls = #{outputReportedCalls}, known_input_tokens = #{knownInputTokens}, known_output_tokens = #{knownOutputTokens}, failure_category = #{failureCategory}, safe_message = #{safeMessage}, artifact_ref = #{artifactRef}, artifact_digest = #{artifactDigest}, revision = #{revision}, last_persisted_sequence = #{lastPersistedSequence} WHERE trace_id = #{traceId} AND revision < #{revision}")
    int update(GenerationTrace value);

    @Results(id = "traceFields", value = {
        @Result(column = "startedAt", property = "startedAt", typeHandler = com.xhl.aicodegenerate.observability.UtcInstantTypeHandler.class),
        @Result(column = "finishedAt", property = "finishedAt", typeHandler = com.xhl.aicodegenerate.observability.UtcInstantTypeHandler.class),
        @Result(column = "expiresAt", property = "expiresAt", typeHandler = com.xhl.aicodegenerate.observability.UtcInstantTypeHandler.class)
    })
    @Select("SELECT " + COLUMNS + " FROM generation_trace WHERE trace_id = #{traceId}")
    GenerationTrace find(@Param("traceId") String traceId);

    @ResultMap("traceFields")
    @Select("SELECT " + COLUMNS + " FROM generation_trace WHERE trace_id = #{id} FOR UPDATE")
    GenerationTrace lock(@Param("id") String id);

    @Select("SELECT id, userId, isDelete FROM app WHERE id = #{id}")
    App findApp(@Param("id") Long id);

    @Select("SELECT id, userId, isDelete FROM app WHERE id = #{id} FOR UPDATE")
    App lockApp(@Param("id") Long id);

    @ResultMap("traceFields")
    @Select("SELECT " + COLUMNS + " FROM generation_trace WHERE task_id = #{id}")
    GenerationTrace byTask(@Param("id") String id);

    @ResultMap("traceFields")
    @Select("SELECT " + COLUMNS + " FROM generation_trace WHERE request_id = #{id}")
    GenerationTrace byRequest(@Param("id") String id);

    @Select("SELECT trace_id FROM generation_trace WHERE app_id = #{appId} AND user_id = #{userId} "
            + "AND trace_kind = 'CREATE' AND creation_link_state = 'BOUND' "
            + "AND expires_at > #{now,typeHandler=com.xhl.aicodegenerate.observability.UtcInstantTypeHandler} "
            + "ORDER BY started_at DESC, trace_id DESC LIMIT 1")
    String creationForApp(@Param("appId") Long appId, @Param("userId") Long userId, @Param("now") Instant now);

    @ResultMap("traceFields")
    @Select("<script>SELECT " + COLUMNS + " FROM generation_trace WHERE user_id = #{userId} AND app_id = #{appId} AND (trace_kind = 'GENERATION' OR creation_link_state = 'BOUND') AND expires_at &gt; #{now,typeHandler=com.xhl.aicodegenerate.observability.UtcInstantTypeHandler} "
            + "<if test='before != null'>AND (started_at &lt; #{before,typeHandler=com.xhl.aicodegenerate.observability.UtcInstantTypeHandler} OR (started_at = #{before,typeHandler=com.xhl.aicodegenerate.observability.UtcInstantTypeHandler} AND trace_id &lt; #{beforeId})) </if>"
            + "ORDER BY started_at DESC, trace_id DESC LIMIT #{limit}</script>")
    List<GenerationTrace> list(@Param("userId") Long userId, @Param("appId") Long appId, @Param("now") Instant now,
                               @Param("before") Instant before, @Param("beforeId") String beforeId, @Param("limit") int limit);

    @Select("SELECT trace_id FROM generation_trace WHERE app_id = #{appId} LIMIT 100")
    List<String> forApp(@Param("appId") Long appId);

    @Select("SELECT trace_id FROM generation_trace WHERE expires_at <= #{now,typeHandler=com.xhl.aicodegenerate.observability.UtcInstantTypeHandler} OR (app_id IS NOT NULL AND NOT EXISTS "
            + "(SELECT 1 FROM app WHERE app.id = generation_trace.app_id AND app.isDelete = 0)) LIMIT 100")
    List<String> expired(@Param("now") Instant now);

    @Delete("DELETE FROM generation_trace WHERE trace_id = #{id}")
    void delete(@Param("id") String id);

    @Update("UPDATE generation_trace SET recording_status = 'PARTIAL' WHERE trace_id = #{id} AND service_instance_id = #{instanceId}")
    void markIncomplete(@Param("id") String id, @Param("instanceId") String instanceId);

    @Update("UPDATE generation_trace SET recording_status = 'PARTIAL' WHERE service_instance_id = #{instanceId} AND recording_status != 'PARTIAL'")
    void markInstanceIncomplete(@Param("instanceId") String instanceId);

    @ResultMap("traceFields")
    @Select("<script>SELECT " + COLUMNS + " FROM generation_trace WHERE execution_status = 'RUNNING' "
            + "AND expires_at &gt; #{now,typeHandler=com.xhl.aicodegenerate.observability.UtcInstantTypeHandler} "
            + "<if test='cursor != null'>AND trace_id &gt; #{cursor} </if>ORDER BY trace_id LIMIT #{limit}</script>")
    List<GenerationTrace> unfinished(@Param("cursor") String cursor, @Param("now") Instant now, @Param("limit") int limit);
}
