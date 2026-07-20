package com.zmbdp.chat.service.service.impl;

import com.zmbdp.chat.api.system.domain.vo.SystemHealthVO;
import com.zmbdp.chat.service.config.MilvusConfig;
import com.zmbdp.chat.service.service.ISystemHealthService;
import io.milvus.client.MilvusServiceClient;
import io.milvus.param.R;
import io.milvus.param.collection.HasCollectionParam;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cloud.client.discovery.DiscoveryClient;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.Statement;
import java.time.format.DateTimeFormatter;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 系统健康检查服务实现类
 * <p>
 * 检测 5 个核心组件的连通性：
 * <ul>
 *     <li>MySQL：执行 {@code SELECT 1} 测试连接</li>
 *     <li>Redis：执行 {@code PING} 命令</li>
 *     <li>Nacos：通过 {@code DiscoveryClient.getServices()} 验证注册中心可达</li>
 *     <li>Milvus：调用 {@code MilvusServiceClient.hasCollection()} 验证向量库可达</li>
 *     <li>LLM：调用 {@code ChatClient} 发送极简 prompt 验证 DashScope API 可达</li>
 * </ul>
 * <p>
 * <b>降级策略</b>：单个组件检测失败不影响其他组件，{@code overallStatus} 为 PARTIAL（部分异常）或 DOWN（全部异常）。
 * <p>
 * <b>异常处理</b>：每个组件检测独立 try-catch，异常仅记录组件状态为 DOWN，不抛出。
 *
 * @author 稚名不带撇
 */
@Slf4j
@Service
public class SystemHealthServiceImpl implements ISystemHealthService {

    /**
     * 组件状态：正常
     */
    private static final String STATUS_UP = "UP";

    /**
     * 组件状态：异常
     */
    private static final String STATUS_DOWN = "DOWN";

    /**
     * 整体状态：全部正常
     */
    private static final String OVERALL_UP = "UP";

    /**
     * 整体状态：部分异常
     */
    private static final String OVERALL_PARTIAL = "PARTIAL";

    /**
     * 整体状态：全部异常
     */
    private static final String OVERALL_DOWN = "DOWN";

    /**
     * 检测时间格式
     */
    private static final DateTimeFormatter CHECK_TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /**
     * MySQL 数据源（检测 MySQL 连通性）
     */
    @Autowired
    private DataSource dataSource;

    /**
     * Redis 操作模板（检测 Redis 连通性）
     */
    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    /**
     * 服务发现客户端（检测 Nacos 注册中心连通性）
     */
    @Autowired
    private DiscoveryClient discoveryClient;

    /**
     * Milvus 客户端（检测向量库连通性）
     */
    @Autowired
    private MilvusServiceClient milvusServiceClient;

    /**
     * Milvus 配置（获取集合名称用于检测）
     */
    @Autowired
    private MilvusConfig milvusConfig;

    /**
     * ChatClient 构建器（检测 LLM API 连通性）
     */
    @Autowired
    private ChatClient.Builder chatClientBuilder;

    /**
     * 执行系统健康检查
     * <p>
     * 依次检测 5 个核心组件，每个组件独立计时和异常捕获。
     *
     * @return 系统健康状态 VO
     */
    @Override
    public SystemHealthVO checkHealth() {
        List<SystemHealthVO.Component> components = new ArrayList<>(5);

        // 1. 检测 MySQL
        components.add(checkMySQL());

        // 2. 检测 Redis
        components.add(checkRedis());

        // 3. 检测 Nacos
        components.add(checkNacos());

        // 4. 检测 Milvus
        components.add(checkMilvus());

        // 5. 检测 LLM
        components.add(checkLLM());

        // 计算整体状态
        long upCount = components.stream()
                .filter(c -> STATUS_UP.equals(c.getStatus()))
                .count();
        String overallStatus;
        if (upCount == components.size()) {
            overallStatus = OVERALL_UP;
        } else if (upCount == 0) {
            overallStatus = OVERALL_DOWN;
        } else {
            overallStatus = OVERALL_PARTIAL;
        }

        SystemHealthVO vo = new SystemHealthVO();
        vo.setOverallStatus(overallStatus);
        vo.setComponents(components);
        vo.setCheckTime(LocalDateTime.now().format(CHECK_TIME_FORMATTER));
        log.info("系统健康检查完成：overallStatus = {}, upCount = {}/{}", overallStatus, upCount, components.size());
        return vo;
    }

    /**
     * 检测 MySQL 连通性
     * <p>
     * 通过 {@link DataSource#getConnection()} 获取连接并执行 {@code SELECT 1}。
     *
     * @return MySQL 组件状态
     */
    private SystemHealthVO.Component checkMySQL() {
        long start = System.currentTimeMillis();
        try (Connection conn = dataSource.getConnection();
             Statement stmt = conn.createStatement()) {
            stmt.execute("SELECT 1");
            long latency = System.currentTimeMillis() - start;
            return new SystemHealthVO.Component("MySQL", STATUS_UP, latency, "连接正常");
        } catch (Exception e) {
            long latency = System.currentTimeMillis() - start;
            log.warn("MySQL 健康检查失败：{}", e.getMessage());
            return new SystemHealthVO.Component("MySQL", STATUS_DOWN, latency, "连接失败：" + e.getMessage());
        }
    }

    /**
     * 检测 Redis 连通性
     * <p>
     * 通过 {@link StringRedisTemplate#getConnectionFactory()} 执行 PING 命令。
     *
     * @return Redis 组件状态
     */
    private SystemHealthVO.Component checkRedis() {
        long start = System.currentTimeMillis();
        try {
            String pong = stringRedisTemplate.getConnectionFactory().getConnection().ping();
            long latency = System.currentTimeMillis() - start;
            return new SystemHealthVO.Component("Redis", STATUS_UP, latency, "连接正常");
        } catch (Exception e) {
            long latency = System.currentTimeMillis() - start;
            log.warn("Redis 健康检查失败：{}", e.getMessage());
            return new SystemHealthVO.Component("Redis", STATUS_DOWN, latency, "连接失败：" + e.getMessage());
        }
    }

    /**
     * 检测 Nacos 注册中心连通性
     * <p>
     * 通过 {@link DiscoveryClient#getServices()} 获取服务列表，能返回即说明 Nacos 可达。
     *
     * @return Nacos 组件状态
     */
    private SystemHealthVO.Component checkNacos() {
        long start = System.currentTimeMillis();
        try {
            List<String> services = discoveryClient.getServices();
            long latency = System.currentTimeMillis() - start;
            return new SystemHealthVO.Component("Nacos", STATUS_UP, latency,
                    "连接正常，已注册服务数：" + services.size());
        } catch (Exception e) {
            long latency = System.currentTimeMillis() - start;
            log.warn("Nacos 健康检查失败：{}", e.getMessage());
            return new SystemHealthVO.Component("Nacos", STATUS_DOWN, latency, "连接失败：" + e.getMessage());
        }
    }

    /**
     * 检测 Milvus 向量库连通性
     * <p>
     * 通过 {@link MilvusServiceClient#hasCollection(HasCollectionParam)} 检查目标集合是否存在，
     * 能返回结果即说明 Milvus 可达。
     *
     * @return Milvus 组件状态
     */
    private SystemHealthVO.Component checkMilvus() {
        long start = System.currentTimeMillis();
        try {
            String collectionName = milvusConfig.getCollectionName();
            R<Boolean> result = milvusServiceClient.hasCollection(
                    HasCollectionParam.newBuilder()
                            .withCollectionName(collectionName)
                            .build());
            long latency = System.currentTimeMillis() - start;
            if (result != null && result.getStatus() == R.Status.Success.getCode()) {
                boolean exists = Boolean.TRUE.equals(result.getData());
                return new SystemHealthVO.Component("Milvus", STATUS_UP, latency,
                        "连接正常，集合 " + collectionName + (exists ? " 已存在" : " 不存在"));
            } else {
                return new SystemHealthVO.Component("Milvus", STATUS_DOWN, latency,
                        "检测失败：" + (result == null ? "响应为空" : result));
            }
        } catch (Exception e) {
            long latency = System.currentTimeMillis() - start;
            log.warn("Milvus 健康检查失败：{}", e.getMessage());
            return new SystemHealthVO.Component("Milvus", STATUS_DOWN, latency, "连接失败：" + e.getMessage());
        }
    }

    /**
     * 检测 LLM（DashScope API）连通性
     * <p>
     * 通过 {@link ChatClient} 发送极简 prompt（"1"）验证 DashScope API 可达性。
     * 能返回响应即说明 API 可达且 API Key 有效。
     *
     * @return LLM 组件状态
     */
    private SystemHealthVO.Component checkLLM() {
        long start = System.currentTimeMillis();
        try {
            String response = chatClientBuilder.build()
                    .prompt()
                    .user("1")
                    .call()
                    .content();
            long latency = System.currentTimeMillis() - start;
            return new SystemHealthVO.Component("LLM", STATUS_UP, latency,
                    "DashScope API 可达" + (response != null && response.length() > 20
                            ? "" : "，响应：" + response));
        } catch (Exception e) {
            long latency = System.currentTimeMillis() - start;
            log.warn("LLM 健康检查失败：{}", e.getMessage());
            return new SystemHealthVO.Component("LLM", STATUS_DOWN, latency, "连接失败：" + e.getMessage());
        }
    }
}
