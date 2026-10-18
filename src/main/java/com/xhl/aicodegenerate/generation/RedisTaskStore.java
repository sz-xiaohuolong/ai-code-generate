package com.xhl.aicodegenerate.generation;

import cn.hutool.json.JSONUtil;
import com.xhl.aicodegenerate.exception.BusinessException;
import com.xhl.aicodegenerate.exception.ErrorCode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Repository;
import java.time.Duration;
import java.util.List;

/** Lua 将幂等、应用租约、任务快照和成功指针作为同一所有权协议处理。 */
@Repository
public class RedisTaskStore {
    private final StringRedisTemplate redis;
    private final Duration lease;
    private final Duration retention;
    private static final DefaultRedisScript<String> CLAIM = new DefaultRedisScript<>("""
        local existing = redis.call('GET', KEYS[1])
        if existing then return existing end
        if redis.call('EXISTS', KEYS[2]) == 1 then return 'BUSY' end
        redis.call('SET', KEYS[2], ARGV[1], 'PX', ARGV[3])
        redis.call('SET', KEYS[1], ARGV[1], 'EX', ARGV[4])
        redis.call('SET', KEYS[3], ARGV[2], 'EX', ARGV[4])
        redis.call('SET', KEYS[4], ARGV[1], 'EX', ARGV[4])
        return ARGV[1]
        """, String.class);
    private static final DefaultRedisScript<Long> RENEW = new DefaultRedisScript<>("""
        if redis.call('GET', KEYS[1]) ~= ARGV[1] then return 0 end
        return redis.call('PEXPIRE', KEYS[1], ARGV[2])
        """, Long.class);
    private static final DefaultRedisScript<Long> SAVE = new DefaultRedisScript<>("""
        if redis.call('GET', KEYS[1]) ~= ARGV[1] then return 0 end
        local old = cjson.decode(redis.call('GET', KEYS[2]) or '{}')
        local incoming = cjson.decode(ARGV[2])
        local active = { STARTING=true, RUNNING=true, STOPPING=true }
        if not active[old.status] or not active[incoming.status] then return 0 end
        if (old.revision or 0) > (incoming.revision or 0) then return 0 end
        redis.call('SET', KEYS[2], ARGV[2], 'EX', ARGV[3])
        return 1
        """, Long.class);
    private static final DefaultRedisScript<Long> FINISH = new DefaultRedisScript<>("""
        if redis.call('GET', KEYS[1]) ~= ARGV[1] then return 0 end
        if ARGV[4] == 'SUCCEEDED' then
          if redis.call('EXISTS', KEYS[3]) == 1 then return 0 end
          redis.call('SET', KEYS[4], ARGV[1])
          redis.call('SADD', KEYS[5], ARGV[1])
        end
        redis.call('SET', KEYS[2], ARGV[2], 'EX', ARGV[3])
        redis.call('SET', KEYS[6], ARGV[2])
        redis.call('SADD', KEYS[7], ARGV[1])
        if ARGV[5] == '1' then redis.call('DEL', KEYS[1]) end
        return 1
        """, Long.class);
    private static final DefaultRedisScript<Long> INTERRUPT = new DefaultRedisScript<>("""
        if redis.call('GET', KEYS[1]) == ARGV[1] then return 0 end
        local value = redis.call('GET', KEYS[2])
        if value ~= ARGV[2] then return 0 end
        redis.call('SET', KEYS[2], ARGV[3], 'EX', ARGV[4])
        redis.call('SET', KEYS[3], ARGV[3])
        redis.call('SADD', KEYS[4], ARGV[1])
        return 1
        """, Long.class);

    @Autowired public RedisTaskStore(StringRedisTemplate redis) { this(redis, Duration.ofSeconds(45), Duration.ofHours(24)); }
    RedisTaskStore(StringRedisTemplate redis, Duration lease, Duration retention) {
        this.redis = redis; this.lease = lease; this.retention = retention;
    }
    static String versionKey(Long app) { return "generation:version:" + app; }
    private String lock(Long app) { return "generation:owner:" + app; }
    private String record(String id) { return "generation:task:" + id; }
    private String stop(String id) { return "generation:stop:" + id; }
    private String latestKey(Long app) { return "generation:latest:" + app; }

    public String claim(GenerationTask task) {
        ensureHistoryIds(task);
        String result = redis.execute(CLAIM, List.of("generation:request:" + task.getUserId() + ":" + task.getClientRequestId(),
                        lock(task.getAppId()), record(task.getId()), latestKey(task.getAppId())),
                task.getId(), JSONUtil.toJsonStr(task), String.valueOf(lease.toMillis()), String.valueOf(retention.toSeconds()));
        if ("BUSY".equals(result)) throw new BusinessException(ErrorCode.OPERATION_ERROR, "当前应用正在生成，请等待完成或停止后重试");
        if (result == null) throw new BusinessException(ErrorCode.OPERATION_ERROR, "任务存储不可用，请稍后重试");
        return result;
    }
    public GenerationTask load(String id) {
        String value = redis.opsForValue().get(record(id));
        return value == null ? null : JSONUtil.toBean(value, GenerationTask.class);
    }
    public String latest(Long app) { return redis.opsForValue().get(latestKey(app)); }
    public String published(Long app) { return redis.opsForValue().get(versionKey(app)); }
    public boolean owns(Long app, String id) { return id.equals(redis.opsForValue().get(lock(app))); }
    public boolean renew(Long app, String id) {
        return Long.valueOf(1).equals(redis.execute(RENEW, List.of(lock(app)), id, String.valueOf(lease.toMillis())));
    }
    public boolean save(GenerationTask task) {
        return Long.valueOf(1).equals(redis.execute(SAVE, List.of(lock(task.getAppId()), record(task.getId())),
                task.getId(), JSONUtil.toJsonStr(task), String.valueOf(retention.toSeconds())));
    }
    public boolean isPublished(Long app, String version) {
        return version.equals(published(app)) || Boolean.TRUE.equals(redis.opsForSet().isMember("generation:versions:" + app, version));
    }
    public void release(GenerationTask task) {
        redis.execute(new DefaultRedisScript<Long>("if redis.call('GET', KEYS[1]) == ARGV[1] then return redis.call('DEL', KEYS[1]) end return 0", Long.class),
                List.of(lock(task.getAppId())), task.getId());
    }
    public boolean finish(GenerationTask task) { return finish(task, true); }
    public boolean finish(GenerationTask task, boolean release) {
        if (!task.getStatus().terminal()) throw new IllegalArgumentException("只有终态可以释放应用");
        return Long.valueOf(1).equals(redis.execute(FINISH,
                List.of(lock(task.getAppId()), record(task.getId()), stop(task.getId()), versionKey(task.getAppId()), "generation:versions:" + task.getAppId(), "generation:history:" + task.getId(), "generation:history:pending"),
                task.getId(), JSONUtil.toJsonStr(task), String.valueOf(retention.toSeconds()), task.getStatus().name(), release ? "1" : "0"));
    }
    public void requestStop(String id) { redis.opsForValue().set(stop(id), "1", retention); }
    public boolean stopRequested(String id) { return Boolean.TRUE.equals(redis.hasKey(stop(id))); }

    public GenerationTask recoverInterrupted(GenerationTask task) {
        if (!task.getStatus().terminal() && !owns(task.getAppId(), task.getId())) {
            String original = JSONUtil.toJsonStr(task);
            ensureHistoryIds(task);
            task.setStatus(TaskStatus.INTERRUPTED); task.setMessage("服务执行已中断，请重新发起任务");
            task.setUpdatedAt(System.currentTimeMillis()); task.setRevision(task.getRevision() + 1);
            if (!Long.valueOf(1).equals(redis.execute(INTERRUPT, List.of(lock(task.getAppId()), record(task.getId()), "generation:history:" + task.getId(), "generation:history:pending"),
                    task.getId(), original, JSONUtil.toJsonStr(task), String.valueOf(retention.toSeconds())))) return load(task.getId());
        }
        return task;
    }
    private void ensureHistoryIds(GenerationTask task) {
        var generator = com.mybatisflex.core.keygen.KeyGeneratorFactory.getKeyGenerator(com.mybatisflex.core.keygen.KeyGenerators.snowFlakeId);
        if (task.getUserMessageId() == null) task.setUserMessageId(((Number) generator.generate(task, "userMessageId")).longValue());
        if (task.getAiMessageId() == null) task.setAiMessageId(((Number) generator.generate(task, "aiMessageId")).longValue());
    }
    public java.util.Set<String> pendingHistory() {
        var ids = redis.opsForSet().members("generation:history:pending");
        return ids == null ? java.util.Set.of() : ids;
    }
    public GenerationTask historyTask(String id) {
        String value = redis.opsForValue().get("generation:history:" + id);
        return value == null ? null : JSONUtil.toBean(value, GenerationTask.class);
    }
    public boolean claimHistory(String id, String owner) {
        return Boolean.TRUE.equals(redis.opsForValue().setIfAbsent("generation:history:owner:" + id, owner, Duration.ofSeconds(30)));
    }
    public void finishHistory(String id, String owner, boolean saved) {
        redis.execute(new DefaultRedisScript<Long>("""
            if redis.call('GET', KEYS[1]) ~= ARGV[1] then return 0 end
            if ARGV[2] == '1' then redis.call('DEL', KEYS[2]); redis.call('SREM', KEYS[3], ARGV[3]) end
            redis.call('DEL', KEYS[1]); return 1
            """, Long.class), List.of("generation:history:owner:" + id, "generation:history:" + id, "generation:history:pending"), owner, saved ? "1" : "0", id);
    }

}
