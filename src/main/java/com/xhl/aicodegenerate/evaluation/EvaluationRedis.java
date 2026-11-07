package com.xhl.aicodegenerate.evaluation;

import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.jedis.JedisClientConfiguration;
import org.springframework.data.redis.connection.jedis.JedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Properties;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** 独立本机 Redis，仅管理本对象启动的进程，绝不清空或关闭外部实例。 */
public final class EvaluationRedis implements AutoCloseable {
    private final Process process;
    private final int port;
    private final JedisConnectionFactory factory;
    private final StringRedisTemplate template;
    private final AtomicBoolean closed = new AtomicBoolean();

    private EvaluationRedis(Process process, int port, JedisConnectionFactory factory) {
        this.process = process;
        this.port = port;
        this.factory = factory;
        this.template = new StringRedisTemplate(factory);
    }

    public static EvaluationRedis start(Path runtime) {
        EvaluationRedis instance = null;
        Process process = null;
        JedisConnectionFactory factory = null;
        try {
            Files.createDirectories(runtime);
            int port;
            try (ServerSocket socket = new ServerSocket(0, 0, InetAddress.getByName("127.0.0.1"))) {
                port = socket.getLocalPort();
            }
            process = new ProcessBuilder("redis-server", "--bind", "127.0.0.1", "--port", Integer.toString(port),
                    "--save", "", "--appendonly", "no", "--daemonize", "no", "--protected-mode", "yes",
                    "--dir", runtime.toAbsolutePath().toString())
                    .redirectErrorStream(true).redirectOutput(runtime.resolve("redis.log").toFile()).start();
            factory = new JedisConnectionFactory(new RedisStandaloneConfiguration("127.0.0.1", port),
                    JedisClientConfiguration.builder().connectTimeout(Duration.ofSeconds(1))
                            .readTimeout(Duration.ofSeconds(1)).build());
            factory.afterPropertiesSet();
            instance = new EvaluationRedis(process, port, factory);
            instance.awaitOwnedServer();
            return instance;
        } catch (IOException | RuntimeException | LinkageError error) {
            if (instance != null) instance.close();
            else {
                if (factory != null) factory.destroy();
                if (process != null) stopProcess(process);
            }
            throw new EvaluationConfigurationException("无法启动专属 Redis；请确认 redis-server 位于 PATH，并检查本次 runtime/redis.log");
        }
    }

    private void awaitOwnedServer() {
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (process.isAlive() && System.nanoTime() < deadline) {
            try (var connection = factory.getConnection()) {
                Properties server = connection.serverCommands().info("server");
                if (server != null && Long.toString(process.pid()).equals(server.getProperty("process_id"))) return;
                throw new EvaluationConfigurationException("Redis 端口不属于本次启动的进程");
            } catch (EvaluationConfigurationException error) {
                throw error;
            } catch (RuntimeException unavailable) {
                try { Thread.sleep(50); }
                catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new EvaluationConfigurationException("专属 Redis 启动已中断");
                }
            }
        }
        throw new EvaluationConfigurationException("专属 Redis 未在启动时限内就绪");
    }

    public StringRedisTemplate template() { return template; }
    public int port() { return port; }
    public long processId() { return process.pid(); }

    @Override public void close() {
        if (!closed.compareAndSet(false, true)) return;
        try { factory.destroy(); }
        finally { stopProcess(process); }
    }

    private static void stopProcess(Process process) {
        process.destroy();
        try {
            if (!process.waitFor(3, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                if (!process.waitFor(3, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("专属 Redis 进程未能退出");
                }
            }
        } catch (InterruptedException error) {
            process.destroyForcibly();
            Thread.currentThread().interrupt();
        }
    }
}
