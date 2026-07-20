package com.zmbdp.chat.service.config;

import io.milvus.client.MilvusServiceClient;
import io.milvus.param.ConnectParam;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.cloud.context.config.annotation.RefreshScope;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Map;

/**
 * Milvus 向量数据库配置
 * <p>
 * 同时承担两个职责：
 * <ol>
 *     <li>通过 {@code @ConfigurationProperties(prefix = "spring.ai.milvus")} 绑定 Nacos {@code zmbdp-chat-service-${env}.yaml}
 *     中 {@code spring.ai.milvus.*} 配置项（与 Spring AI 标准前缀对齐）</li>
 *     <li>通过 {@code @Bean} 暴露 {@link MilvusServiceClient} 单例</li>
 * </ol>
 * <p>
 * <b>配置项</b>（与 Nacos 配置一一对应，支持 Nacos 热刷新）：
 * <ul>
 *     <li>{@code host}：Milvus 服务地址（默认 127.0.0.1）</li>
 *     <li>{@code port}：Milvus 服务端口（默认 19530）</li>
 *     <li>{@code database-name}：Milvus 数据库名（默认 default）</li>
 *     <li>{@code collection-name}：Milvus 集合名（默认 scaffold_knowledge）</li>
 *     <li>{@code embedding-dimension}：向量维度（默认 1536，text-embedding-v1 输出维度）</li>
 *     <li>{@code index-type}：向量索引类型（默认 HNSW）</li>
 *     <li>{@code metric-type}：相似度度量类型（默认 IP）</li>
 *     <li>{@code index-parameters}：索引参数 Map（如 {@code {efConstruction: 200, M: 16}}）</li>
 *     <li>{@code query-parameters}：查询参数 Map（如 {@code {efRuntime: 100}}）</li>
 * </ul>
 * <p>
 * <b>SDK 版本</b>：milvus-sdk-java 2.4.5（与 docker-compose 中 Milvus v2.4.5 镜像版本对齐）。
 *
 * @author 稚名不带撇
 */
@Slf4j
@Data
@RefreshScope
@Configuration
@ConfigurationProperties(prefix = "spring.ai.milvus")
public class MilvusConfig {

    /**
     * Milvus 服务地址
     */
    private String host = "127.0.0.1";

    /**
     * Milvus 服务端口
     */
    private Integer port = 19530;

    /**
     * Milvus 数据库名
     */
    private String databaseName = "default";

    /**
     * Milvus 集合名
     */
    private String collectionName = "scaffold_knowledge";

    /**
     * 向量维度（text-embedding-v1 输出维度）
     */
    private Integer embeddingDimension = 1536;

    /**
     * 向量索引类型（HNSW、IVF_FLAT 等）
     */
    private String indexType = "HNSW";

    /**
     * 向量相似度度量类型（IP、L2、COSINE）
     */
    private String metricType = "IP";

    /**
     * 索引参数 Map（如 {@code {efConstruction: 200, M: 16}}）
     */
    private Map<String, Object> indexParameters;

    /**
     * 查询参数 Map（如 {@code {efRuntime: 100}}）
     */
    private Map<String, Object> queryParameters;

    /**
     * 构建 MilvusServiceClient Bean
     * <p>
     * MilvusServiceClient 是 milvus-sdk-java 2.4.x 的主客户端，
     * 封装了集合管理、向量 CRUD、向量检索等操作。
     *
     * @return MilvusServiceClient 实例
     */
    @Bean
    public MilvusServiceClient milvusServiceClient() {
        ConnectParam.Builder builder = ConnectParam.newBuilder()
                .withHost(host)
                .withPort(port != null ? port : 19530);
        if (databaseName != null && !databaseName.isEmpty()) {
            builder.withDatabaseName(databaseName);
        }
        ConnectParam connectParam = builder.build();
        log.info("MilvusServiceClient 初始化完成，连接地址：{}:{}/{}", host, port, databaseName);
        return new MilvusServiceClient(connectParam);
    }
}