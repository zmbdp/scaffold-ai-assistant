package com.zmbdp.chat.service.service.impl;

import com.alibaba.cloud.ai.document.DocumentWithScore;
import com.alibaba.cloud.ai.model.RerankModel;
import com.alibaba.cloud.ai.model.RerankRequest;
import com.alibaba.cloud.ai.model.RerankResponse;
import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.zmbdp.chat.api.chat.domain.vo.DocumentVO;
import com.zmbdp.chat.service.config.MilvusConfig;
import com.zmbdp.chat.service.constant.AiCacheConstants;
import com.zmbdp.chat.service.domain.entity.SysAiDocument;
import com.zmbdp.chat.service.mapper.SysAiDocumentMapper;
import com.zmbdp.chat.service.service.IModelService;
import com.zmbdp.chat.service.service.IVectorStoreService;
import com.zmbdp.common.bloomfilter.service.BloomFilterService;
import com.zmbdp.common.core.utils.JsonUtil;
import com.zmbdp.common.snowflake.service.SnowflakeIdService;
import io.milvus.client.MilvusServiceClient;
import io.milvus.grpc.DataType;
import io.milvus.grpc.DescribeCollectionResponse;
import io.milvus.grpc.SearchResults;
import io.milvus.param.IndexType;
import io.milvus.param.MetricType;
import io.milvus.param.R;
import io.milvus.param.collection.HasCollectionParam;
import io.milvus.param.collection.LoadCollectionParam;
import io.milvus.param.collection.CreateCollectionParam;
import io.milvus.param.collection.DropCollectionParam;
import io.milvus.param.collection.ReleaseCollectionParam;
import io.milvus.param.collection.DescribeCollectionParam;
import io.milvus.param.collection.FieldType;
import io.milvus.param.dml.InsertParam;
import io.milvus.param.dml.SearchParam;
import io.milvus.param.dml.DeleteParam;
import io.milvus.param.index.CreateIndexParam;
import io.milvus.response.DescCollResponseWrapper;
import io.milvus.response.SearchResultsWrapper;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 向量存储服务实现类
 * <p>
 * 基于 Milvus 2.4.x 实现 vector CRUD 与相似性检索，封装 Embedding 生成。
 * <p>
 * <b>集合结构</b>（{@code scaffold_knowledge}，集合名从 Nacos {@code spring.ai.milvus.collection-name} 读取）：
 * <ul>
 *     <li>{@code id}：Int64 主键，由 {@link SnowflakeIdService} 生成（不启用 AutoID）</li>
 *     <li>{@code vector}：FloatVector，维度由 Nacos {@code spring.ai.milvus.embedding-dimension} 决定（默认 1536，text-embedding-v1 输出）</li>
 *     <li>{@code content}：VarChar，最大 65535，分块文本</li>
 *     <li>{@code document_id}：Int64，关联 sys_ai_document 表</li>
 *     <li>{@code chunk_index}：Int32，分块序号</li>
 *     <li>{@code metadata}：JSON，元数据（source_type、source_path、module、category、title、create_time）</li>
 * </ul>
 * <p>
 * <b>索引</b>（参数从 Nacos {@code spring.ai.milvus.index-type/metric-type/index-parameters/query-parameters} 读取）：
 * <ul>
 *     <li>向量索引：HNSW，metric_type=IP，efConstruction=200，M=16，efRuntime=100</li>
 *     <li>标量索引：{@code document_id} 字段（用于按文档ID删除）</li>
 * </ul>
 * <p>
 * <b>配置与代码对齐说明</b>：
 * <ul>
 *     <li>Nacos 的 {@code query-parameters.efRuntime} 在代码中转换为 Milvus HNSW 实际查询参数 {@code ef}（语义一致，仅参数名差异）</li>
 *     <li>向量字段名、内容字段名等固定字段名常量在 {@link AiCacheConstants} 中定义，不通过 Nacos 配置</li>
 * </ul>
 * <p>
 * <b>不涉及缓存</b>：向量检索结果不缓存，每次实时查询 Milvus。
 * <p>
 * <b>布隆过滤器复用说明</b>：
 * 当前实现复用脚手架统一的 {@link BloomFilterService} 单例（key=脚手架硬编码的 filter 名），
 * 通过 key 前缀区分（{@code document_id:{id}}）避免与脚手架原有元素冲突。
 * {@link AiCacheConstants#BLOOM_FILTER_DOCUMENT_IDS} 常量保留作为后续独立 filter 改造的命名建议。
 *
 * @author 稚名不带撇
 */
@Slf4j
@Service
public class VectorStoreServiceImpl implements IVectorStoreService {

    /**
     * Milvus 客户端
     */
    @Autowired
    private MilvusServiceClient milvusServiceClient;

    /**
     * Milvus 配置（从 Nacos {@code spring.ai.milvus.*} 读取，支持热刷新）
     */
    @Autowired
    private MilvusConfig milvusConfig;

    /**
     * 模型管理服务（用于获取 EmbeddingModel）
     */
    @Autowired
    private IModelService modelService;

    /**
     * 雪花 ID 生成服务（用于生成向量记录主键）
     */
    @Autowired
    private SnowflakeIdService snowflakeIdService;

    /**
     * 布隆过滤器服务（用于 document_id 存在性前置过滤）
     */
    @Autowired
    private BloomFilterService bloomFilterService;

    /**
     * 文档 mapper（用于启动时从 MySQL 预热布隆过滤器）
     */
    @Autowired
    private SysAiDocumentMapper sysAiDocumentMapper;

    /**
     * 重排序模型（由 DashScopeRerankAutoConfiguration 自动装配）
     * <p>
     * 通过 {@code spring.ai.dashscope.rerank.model} 配置项指定模型名（默认 gte-rerank）。
     * 使用 {@code required = false} 注入，未配置 rerank 模型时跳过 Reranking 步骤
     * （与设计文档 6.3.1 节"若未配置 Reranking 模型，跳过重排序步骤"一致）。
     */
    @Autowired(required = false)
    private RerankModel rerankModel;

    /**
     * Reranking 超时时间（毫秒，来自 Nacos {@code scaffold.rag.rerank-timeout}，默认 5000ms）
     * <p>
     * 超时降级为 Milvus 原始排序（与设计文档 6.3.1 节降级策略一致）。
     */
    @Value("${scaffold.rag.rerank-timeout:5000}")
    private long rerankTimeoutMs;

    /**
     * 维度不一致时是否自动重建集合（从 Nacos {@code spring.ai.milvus.auto-rebuild-on-dimension-mismatch} 读取，默认 true）
     * <p>
     * <b>启用场景</b>：开发/测试环境调整 Embedding 模型导致维度变化（如 text-embedding-v1=1536 →
     * qwen3-text-embedding=1024），需要自动清理旧向量并重建集合，避免插入/检索时维度不匹配报错。
     * <p>
     * <b>关闭场景</b>：生产环境为避免误删数据，可设为 false，此时维度不一致仅记录 ERROR 日志，
     * 由管理员手动确认后通过 B 端接口或 DBA 工具处理。
     * <p>
     * <b>重建副作用</b>：自动 drop 旧集合 + 清空 sys_ai_document 表所有 ACTIVE 记录
     * （因为对应的 Milvus 向量已随集合删除而失效），后续由启动同步流程重新灌入。
     */
    @Value("${spring.ai.milvus.auto-rebuild-on-dimension-mismatch:true}")
    private boolean autoRebuildOnDimensionMismatch;

    /**
     * VarChar 字段最大长度（content 字段）
     */
    private static final int CONTENT_MAX_LENGTH = 65535;

    /**
     * 检索候选集扩大因子（topK × 3 作为 Milvus 检索数量，为后续 Reranking 预留空间）
     * <p>
     * 设计文档 06-RAG流程设计.md 6.3 节描述为 topK×2，此处取 ×3 是为了给 Reranking 提供更大的候选池，
     * 提升最终 topK 结果的相关性（业界普遍实践）。
     */
    private static final int CANDIDATE_EXPAND_FACTOR = 3;

    /**
     * Milvus 检索候选集上限（避免 topK 过大时单次检索数据量过大）
     */
    private static final int MAX_CANDIDATE_TOPK = 100;

    /**
     * 布隆过滤器中 document_id 的 key 前缀
     */
    private static final String BLOOM_DOCUMENT_ID_PREFIX = "document_id:";

    /**
     * GSON 实例（用于 metadata Map → JsonElement 转换）
     * <p>
     * Milvus SDK 2.4.5 的 JSON 字段要求值类型为 {@link JsonElement}，直接传 {@code Map} 会在
     * {@code ParamUtils.checkFieldData} 的 {@code case JSON} 校验失败（{@code value instanceof JsonElement} 为 false），
     * 而错误消息表又没有 JSON 类型条目，导致 {@code String.format(null, ...)} 触发 NPE。
     * 因此必须先把 Map 转成 JsonObject 再传给 Milvus。
     */
    private static final Gson GSON = new Gson();

    /*=============================================    生命周期    =============================================*/

    /**
     * 启动时初始化 Milvus 集合（幂等）并预热布隆过滤器
     * <p>
     * 由 Spring 容器在 Bean 初始化后调用，确保集合和索引已创建并加载到内存；
     * 同时从 MySQL {@code sys_ai_document} 表加载所有 {@code document_id} 到布隆过滤器，
     * 避免后续检索时对不存在的 document_id 进行无效的 Milvus 查询（缓存穿透防护）。
     */
    @PostConstruct
    public void init() {
        try {
            initCollection();
        } catch (Exception e) {
            log.error("Milvus 集合初始化失败，向量服务将不可用", e);
        }
        try {
            warmupBloomFilter();
        } catch (Exception e) {
            log.error("布隆过滤器预热失败，document_id 存在性过滤将退化为直接查询 Milvus", e);
        }
    }

    /**
     * 预热布隆过滤器
     * <p>
     * 从 MySQL {@code sys_ai_document} 表查询所有 {@code id}，批量加入布隆过滤器。
     * <p>
     * <b>设计依据</b：
     * <ul>
     *     <li>启动时一次性加载，避免运行时每次检索都查询 MySQL 验证 document_id 存在性</li>
     *     <li>布隆过滤器可能有误判（判存在可能不存在），但不会漏判（判不存在一定不存在），
     *     因此可用于快速过滤掉一定不存在的 document_id</li>
     *     <li>知识同步新增/删除文档时，{@link #addDocuments} 和 {@link #deleteByDocumentId}
     *     会同步更新布隆过滤器</li>
     * </ul>
     * <p>
     * <b>异常处理</b>：预热失败不影响向量检索主流程，仅记录错误日志
     * （检索时 {@code mightContain} 返回 false 会跳过过滤，直接查询 Milvus）。
     */
    private void warmupBloomFilter() {
        com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<SysAiDocument> wrapper =
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<>();
        wrapper.select(SysAiDocument::getId);
        List<Object> idList = sysAiDocumentMapper.selectObjs(wrapper);
        if (idList == null || idList.isEmpty()) {
            log.info("布隆过滤器预热完成：sys_ai_document 表无数据，跳过预热");
            return;
        }
        List<String> keys = new ArrayList<>(idList.size());
        for (Object id : idList) {
            if (id != null) {
                keys.add(BLOOM_DOCUMENT_ID_PREFIX + id);
            }
        }
        bloomFilterService.putAll(keys);
        log.info("布隆过滤器预热完成：加载 document_id 数量 = {}", keys.size());
    }

    /*=============================================    内部调用    =============================================*/

    /**
     * 初始化 Milvus 集合和索引（幂等）
     * <p>
     * 启动时按以下顺序处理：
     * <ol>
     *     <li>集合不存在 → 直接创建</li>
     *     <li>集合存在 + 维度一致 → 仅 ensureCollectionLoaded 复用</li>
     *     <li>集合存在 + 维度不一致 + auto-rebuild=true → release + drop + 重建 + 清空 sys_ai_document 表</li>
     *     <li>集合存在 + 维度不一致 + auto-rebuild=false → 仅记录 ERROR 日志，由管理员手动处理</li>
     * </ol>
     * 创建后执行 loadCollection 加载到内存。
     * <ul>
     *     <li>向量索引：HNSW，metric_type=IP，efConstruction=200，M=16，efRuntime=100（参数从 Nacos 读取）</li>
     *     <li>标量索引：对 {@code document_id} 建立标量索引（支持按文档ID删除）</li>
     * </ul>
     */
    @Override
    public void initCollection() {
        String collectionName = milvusConfig.getCollectionName();
        try {
            // 1. 检查集合是否存在
            R<Boolean> hasCollectionR = milvusServiceClient.hasCollection(
                    HasCollectionParam.newBuilder()
                            .withCollectionName(collectionName)
                            .build());
            if (hasCollectionR == null || hasCollectionR.getData() == null) {
                log.error("检查 Milvus 集合存在性失败：{}", hasCollectionR);
                return;
            }

            if (Boolean.TRUE.equals(hasCollectionR.getData())) {
                // 2. 集合已存在，校验维度是否与配置一致
                Integer existingDim = getExistingVectorDimension(collectionName);
                int configuredDim = milvusConfig.getEmbeddingDimension();

                if (existingDim == null) {
                    log.warn("无法获取 Milvus 集合 {} 的现有维度，跳过维度校验，直接复用", collectionName);
                    ensureCollectionLoaded(collectionName);
                    return;
                }

                if (existingDim == configuredDim) {
                    log.info("Milvus 集合 {} 已存在且维度一致（dim={}），跳过创建", collectionName, configuredDim);
                    ensureCollectionLoaded(collectionName);
                    return;
                }

                // 维度不一致
                log.warn("Milvus 集合 {} 维度不一致：现有={}, 配置={}", collectionName, existingDim, configuredDim);
                if (!autoRebuildOnDimensionMismatch) {
                    log.error("自动重建已禁用（spring.ai.milvus.auto-rebuild-on-dimension-mismatch=false），" +
                            "维度不匹配将导致向量插入/检索失败，请手动处理：drop collection {} 后重启服务", collectionName);
                    return;
                }

                // 自动重建：先 release 再 drop（已 load 的集合直接 drop 会失败）
                log.warn("启动维度自动重建：release + drop collection {}", collectionName);
                releaseCollectionQuietly(collectionName);
                dropCollectionQuietly(collectionName);
                // 同步清空 sys_ai_document 表（对应的 Milvus 向量已随集合删除而失效，
                // 保留记录会污染增量同步的 hash 对比，物理删除让启动同步流程重新识别为新增文件）
                cleanupAllDocuments();
                log.warn("sys_ai_document 表已清空，等待启动同步流程重新灌入数据");
                // 落到下面的创建流程
            }

            // 3. 创建集合 + 索引 + 加载
            createCollectionWithIndex(collectionName);
        } catch (Exception e) {
            log.error("初始化 Milvus 集合 {} 异常", collectionName, e);
        }
    }

    /**
     * 创建集合 + 向量索引 + 标量索引 + 加载到内存
     * <p>
     * 提取自 initCollection()，供初次创建和维度重建场景复用。
     *
     * @param collectionName 集合名
     */
    private void createCollectionWithIndex(String collectionName) {
        // 1. 创建集合（含字段定义）
        CreateCollectionParam createCollectionParam = CreateCollectionParam.newBuilder()
                .withCollectionName(collectionName)
                .withDescription("Scaffold AI Knowledge Base")
                .addFieldType(FieldType.newBuilder()
                        .withName("id")
                        .withDataType(DataType.Int64)
                        .withPrimaryKey(true)
                        .withAutoID(false)
                        .build())
                .addFieldType(FieldType.newBuilder()
                        .withName(AiCacheConstants.MILVUS_VECTOR_FIELD)
                        .withDataType(DataType.FloatVector)
                        .withDimension(milvusConfig.getEmbeddingDimension())
                        .build())
                .addFieldType(FieldType.newBuilder()
                        .withName(AiCacheConstants.MILVUS_CONTENT_FIELD)
                        .withDataType(DataType.VarChar)
                        .withMaxLength(CONTENT_MAX_LENGTH)
                        .build())
                .addFieldType(FieldType.newBuilder()
                        .withName(AiCacheConstants.MILVUS_DOCUMENT_ID_FIELD)
                        .withDataType(DataType.Int64)
                        .build())
                .addFieldType(FieldType.newBuilder()
                        .withName(AiCacheConstants.MILVUS_CHUNK_INDEX_FIELD)
                        .withDataType(DataType.Int32)
                        .build())
                .addFieldType(FieldType.newBuilder()
                        .withName(AiCacheConstants.MILVUS_METADATA_FIELD)
                        .withDataType(DataType.JSON)
                        .build())
                .build();
        R<io.milvus.param.RpcStatus> createR = milvusServiceClient.createCollection(createCollectionParam);
        if (createR == null || createR.getStatus() != R.Status.Success.getCode()) {
            log.error("创建 Milvus 集合失败：{}", createR);
            return;
        }
        log.info("Milvus 集合 {} 创建成功（dim={}）", collectionName, milvusConfig.getEmbeddingDimension());

        // 2. 创建向量索引（HNSW + IP，参数从 Nacos 读取）
        IndexType indexType = IndexType.valueOf(milvusConfig.getIndexType().toUpperCase());
        MetricType metricType = MetricType.valueOf(milvusConfig.getMetricType().toUpperCase());
        String indexExtraParam = JsonUtil.classToJson(milvusConfig.getIndexParameters());
        CreateIndexParam createIndexParam = CreateIndexParam.newBuilder()
                .withCollectionName(collectionName)
                .withFieldName(AiCacheConstants.MILVUS_VECTOR_FIELD)
                .withIndexType(indexType)
                .withMetricType(metricType)
                .withExtraParam(indexExtraParam)
                .build();
        R<io.milvus.param.RpcStatus> indexR = milvusServiceClient.createIndex(createIndexParam);
        if (indexR == null || indexR.getStatus() != R.Status.Success.getCode()) {
            log.error("创建 Milvus 向量索引失败：{}", indexR);
            return;
        }
        log.info("Milvus 集合 {} 向量索引创建成功（{} + {}，参数：{}）", collectionName, indexType, metricType, indexExtraParam);

        // 3. 创建 document_id 标量索引（支持按文档ID高效删除）
        CreateIndexParam scalarIndexParam = CreateIndexParam.newBuilder()
                .withCollectionName(collectionName)
                .withFieldName(AiCacheConstants.MILVUS_DOCUMENT_ID_FIELD)
                .withIndexType(IndexType.STL_SORT)
                .build();
        R<io.milvus.param.RpcStatus> scalarIndexR = milvusServiceClient.createIndex(scalarIndexParam);
        if (scalarIndexR == null || scalarIndexR.getStatus() != R.Status.Success.getCode()) {
            log.warn("创建 document_id 标量索引失败（不影响主流程）：{}", scalarIndexR);
        } else {
            log.info("Milvus 集合 {} document_id 标量索引创建成功", collectionName);
        }

        // 4. 加载集合到内存
        ensureCollectionLoaded(collectionName);
    }

    /**
     * 查询现有集合的向量字段维度
     * <p>
     * 通过 describeCollection 获取集合 schema，找到 {@link AiCacheConstants#MILVUS_VECTOR_FIELD}
     * 字段并返回其维度。如果集合不存在、describe 失败、或向量字段未找到，返回 null。
     * <p>
     * SDK 2.4.5 中 {@code describeCollection} 返回 {@code R<DescribeCollectionResponse>}，
     * 通过 {@link DescCollResponseWrapper} 包装后可按 {@link FieldType} 访问字段元信息。
     *
     * @param collectionName 集合名
     * @return 现有向量维度；获取失败时返回 null
     */
    private Integer getExistingVectorDimension(String collectionName) {
        try {
            R<DescribeCollectionResponse> descR = milvusServiceClient.describeCollection(
                    DescribeCollectionParam.newBuilder()
                            .withCollectionName(collectionName)
                            .build());
            if (descR == null || descR.getStatus() != R.Status.Success.getCode()) {
                log.warn("describeCollection 失败：{}", descR);
                return null;
            }
            DescCollResponseWrapper wrapper = new DescCollResponseWrapper(descR.getData());
            for (FieldType field : wrapper.getFields()) {
                if (AiCacheConstants.MILVUS_VECTOR_FIELD.equals(field.getName())) {
                    return field.getDimension();
                }
            }
            log.warn("集合 {} 中未找到向量字段 {}", collectionName, AiCacheConstants.MILVUS_VECTOR_FIELD);
            return null;
        } catch (Exception e) {
            log.warn("获取集合 {} 现有维度异常：{}", collectionName, e.getMessage());
            return null;
        }
    }

    /**
     * 释放已 load 的集合（drop 前必须先 release，否则会失败）
     * <p>
     * 静默处理异常，未 load 时 release 会失败但可忽略。
     *
     * @param collectionName 集合名
     */
    private void releaseCollectionQuietly(String collectionName) {
        try {
            milvusServiceClient.releaseCollection(
                    ReleaseCollectionParam.newBuilder()
                            .withCollectionName(collectionName)
                            .build());
            log.info("Milvus 集合 {} 已释放", collectionName);
        } catch (Exception e) {
            log.debug("release 集合 {} 异常（可能未加载，忽略）：{}", collectionName, e.getMessage());
        }
    }

    /**
     * 删除集合（drop collection）
     * <p>
     * 静默处理异常，调用前应先调用 {@link #releaseCollectionQuietly}。
     *
     * @param collectionName 集合名
     */
    private void dropCollectionQuietly(String collectionName) {
        try {
            R<io.milvus.param.RpcStatus> dropR = milvusServiceClient.dropCollection(
                    DropCollectionParam.newBuilder()
                            .withCollectionName(collectionName)
                            .build());
            if (dropR != null && dropR.getStatus() == R.Status.Success.getCode()) {
                log.info("Milvus 集合 {} 已删除", collectionName);
            } else {
                log.warn("drop 集合 {} 失败：{}", collectionName, dropR);
            }
        } catch (Exception e) {
            log.warn("drop 集合 {} 异常：{}", collectionName, e.getMessage());
        }
    }

    /**
     * 清空 sys_ai_document 表所有记录
     * <p>
     * 维度重建场景下调用：Milvus 向量已随集合 drop 失效，对应的 sys_ai_document 记录
     * 不再有效。物理删除全部记录，让后续启动同步流程把所有文件识别为"新增文件"重新向量化。
     * <p>
     * 同时清理 Redis 布隆过滤器中的 document_id 元素（调用 {@link BloomFilterService#clear()}
     * 清空整个布隆过滤器），避免脏数据。
     */
    private void cleanupAllDocuments() {
        try {
            int deleted = sysAiDocumentMapper.delete(
                    new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<SysAiDocument>().ge("id", 0L));
            log.warn("sys_ai_document 表已清空，删除记录数：{}", deleted);
            // 清理布隆过滤器中所有 document_id 元素
            try {
                bloomFilterService.clear();
                log.warn("布隆过滤器已清空（document_ids）");
            } catch (Exception e) {
                log.warn("清空布隆过滤器失败（不影响主流程）：{}", e.getMessage());
            }
        } catch (Exception e) {
            log.error("清空 sys_ai_document 表异常，需手动清理后再触发同步", e);
        }
    }

    /**
     * 确保集合已加载到内存（幂等）
     *
     * @param collectionName 集合名
     */
    private void ensureCollectionLoaded(String collectionName) {
        try {
            R<io.milvus.param.RpcStatus> loadR = milvusServiceClient.loadCollection(
                    LoadCollectionParam.newBuilder()
                            .withCollectionName(collectionName)
                            .build());
            if (loadR != null && loadR.getStatus() == R.Status.Success.getCode()) {
                log.info("Milvus 集合 {} 已加载到内存", collectionName);
            }
        } catch (Exception e) {
            // 集合已加载时再次 load 会抛异常，这里吞掉
            log.debug("Milvus 集合 {} 可能已加载：{}", collectionName, e.getMessage());
        }
    }

    /**
     * 将文档转换为向量并写入 Milvus
     * <p>
     * 遍历文档列表，对每个分块调用 EmbeddingModel 生成向量，构建 Milvus InsertReq 并执行插入。
     * 插入成功后将 document_id 加入布隆过滤器。
     *
     * @param documents 文档列表（已分块，每个 Document 含 content、metadata）
     */
    @Override
    public void addDocuments(List<Document> documents) {
        if (documents == null || documents.isEmpty()) {
            return;
        }
        String collectionName = milvusConfig.getCollectionName();
        EmbeddingModel embeddingModel = modelService.getEmbeddingModel();

        // 构建列式数据
        List<Long> idList = new ArrayList<>(documents.size());
        List<List<Float>> vectorList = new ArrayList<>(documents.size());
        List<String> contentList = new ArrayList<>(documents.size());
        List<Long> documentIdList = new ArrayList<>(documents.size());
        List<Integer> chunkIndexList = new ArrayList<>(documents.size());
        // Milvus JSON 字段要求值类型为 JsonElement（见 GSON 字段注释说明），不能用 List<Map>
        List<JsonElement> metadataList = new ArrayList<>(documents.size());

        // 记录本次写入的 documentId 集合（用于回填布隆过滤器）
        Set<Long> documentIdSet = new HashSet<>();

        for (Document doc : documents) {
            String content = doc.getText();
            if (content == null || content.isEmpty()) {
                log.warn("文档分块内容为空，跳过：{}", doc.getMetadata());
                continue;
            }
            // 截断超长内容
            if (content.length() > CONTENT_MAX_LENGTH) {
                content = content.substring(0, CONTENT_MAX_LENGTH);
            }

            // 生成向量（Embedding 调用，可能因网络抖动/超时失败，外层 try-catch 已包裹）
            float[] vector;
            try {
                vector = embeddingModel.embed(content);
            } catch (Exception e) {
                // Embedding 调用失败（如 DashScope 超时），抛出异常让上层 processAddedFile 触发回滚
                // 避免出现 sys_ai_document 有记录但 Milvus 无向量的数据不一致
                throw new RuntimeException("Embedding 调用失败，content 长度 = " + content.length(), e);
            }
            List<Float> vectorFloatList = new ArrayList<>(vector.length);
            for (float v : vector) {
                vectorFloatList.add(v);
            }

            // 从 metadata 提取 documentId 和 chunkIndex（metadata 可能为 null，extractXxx 已处理）
            Map<String, Object> metadata = doc.getMetadata();
            Long documentId = extractLong(metadata, AiCacheConstants.MILVUS_DOCUMENT_ID_FIELD);
            Integer chunkIndex = extractInt(metadata, AiCacheConstants.MILVUS_CHUNK_INDEX_FIELD);

            // 生成主键
            long id = snowflakeIdService.nextId();

            idList.add(id);
            vectorList.add(vectorFloatList);
            contentList.add(content);
            // Milvus 标量字段不允许 null 元素（SDK ParamUtils.checkFieldData 会 NPE），这里兜底
            // documentId 为 null 时填 0（业务上不会存在 id=0 的文档，检索时不会命中）
            documentIdList.add(documentId != null ? documentId : 0L);
            // chunkIndex 为 null 时填 0（表示未分块或分块信息丢失）
            chunkIndexList.add(chunkIndex != null ? chunkIndex : 0);
            // Milvus JSON 字段要求值类型为 JsonElement（SDK 2.4.5 的 case JSON 校验 instanceof JsonElement），
            // 直接传 Map 会校验失败，且 SDK 错误消息表无 JSON 条目导致 String.format(null) 触发 NPE。
            // 这里用 GSON 把 Map 转成 JsonObject 再传入；metadata 为 null 时转空 JsonObject。
            Map<String, Object> safeMetadata = metadata != null ? metadata : new HashMap<>();
            JsonElement jsonElement = GSON.toJsonTree(safeMetadata);
            metadataList.add(jsonElement);

            if (documentId != null) {
                documentIdSet.add(documentId);
            }
        }

        if (idList.isEmpty()) {
            log.warn("无有效文档分块可写入 Milvus");
            return;
        }

        try {
            // 构建 InsertParam（列式数据）
            InsertParam insertParam = InsertParam.newBuilder()
                    .withCollectionName(collectionName)
                    .withFields(List.of(
                            new InsertParam.Field("id", idList),
                            new InsertParam.Field(AiCacheConstants.MILVUS_VECTOR_FIELD, vectorList),
                            new InsertParam.Field(AiCacheConstants.MILVUS_CONTENT_FIELD, contentList),
                            new InsertParam.Field(AiCacheConstants.MILVUS_DOCUMENT_ID_FIELD, documentIdList),
                            new InsertParam.Field(AiCacheConstants.MILVUS_CHUNK_INDEX_FIELD, chunkIndexList),
                            new InsertParam.Field(AiCacheConstants.MILVUS_METADATA_FIELD, metadataList)
                    ))
                    .build();
            R<io.milvus.grpc.MutationResult> insertR = milvusServiceClient.insert(insertParam);
            if (insertR == null || insertR.getStatus() != R.Status.Success.getCode()) {
                log.error("写入 Milvus 失败：{}", insertR);
                return;
            }
            log.info("写入 Milvus 成功：共 {} 条分块", idList.size());

            // 回填布隆过滤器（document_id 存在性前置过滤用）
            for (Long documentId : documentIdSet) {
                bloomFilterService.put(BLOOM_DOCUMENT_ID_PREFIX + documentId);
            }
        } catch (Exception e) {
            log.error("写入 Milvus 集合 {} 异常", collectionName, e);
        }
    }

    /**
     * 向量相似性检索
     * <p>
     * 执行流程：
     * <ol>
     *     <li>Embedding 生成查询向量</li>
     *     <li>Milvus 检索 topK×3 候选分块（按相似度降序）</li>
     *     <li>调用 RerankModel 对候选集重排序（gte-rerank 模型）</li>
     *     <li>截取 topK 条返回</li>
     * </ol>
     * <p>
     * <b>Reranking 降级策略</b>：
     * <ul>
     *     <li>未配置 RerankModel Bean（{@code spring.ai.dashscope.rerank.model} 未设置）→ 跳过重排序</li>
     *     <li>RerankModel 调用失败 → 降级为 Milvus 原始排序（记录 warning 日志）</li>
     *     <li>RerankModel 超时（&gt;{@code scaffold.rag.rerank-timeout}，默认 5000ms）→ 降级为 Milvus 原始排序</li>
     * </ul>
     *
     * @param query 查询文本
     * @param topK  返回数量（1-20）
     * @return 检索到的文档列表（含 score、chunkIndex，按相关性降序）
     */
    @Override
    public List<DocumentVO> search(String query, int topK) {
        return doSearch(query, topK, null);
    }

    /**
     * 带元数据过滤的向量检索
     * <p>
     * 在 {@link #search} 基础上增加元数据过滤条件（如 module、source_type）。
     *
     * @param query   查询文本
     * @param topK    返回数量
     * @param filters 元数据过滤条件（如 {@code {"source_type": "doc", "module": "common-cache"}}）
     * @return 检索到的文档列表
     */
    @Override
    public List<DocumentVO> searchWithFilter(String query, int topK, Map<String, String> filters) {
        return doSearch(query, topK, filters);
    }

    /**
     * 执行向量检索的内部方法
     * <p>
     * 流程：Embedding → Milvus 检索（topK×3 候选）→ Reranking 重排序 → 截取 topK 条。
     *
     * @param query   查询文本
     * @param topK    最终返回数量
     * @param filters 元数据过滤条件（可为 null）
     * @return 检索结果列表
     */
    private List<DocumentVO> doSearch(String query, int topK, Map<String, String> filters) {
        if (query == null || query.isEmpty() || topK <= 0) {
            return Collections.emptyList();
        }
        String collectionName = milvusConfig.getCollectionName();

        try {
            // 1. 生成查询向量
            EmbeddingModel embeddingModel = modelService.getEmbeddingModel();
            float[] queryVector = embeddingModel.embed(query);
            List<Float> queryVectorList = new ArrayList<>(queryVector.length);
            for (float v : queryVector) {
                queryVectorList.add(v);
            }

            // 2. 构建 SearchParam（候选集 = topK × 3，为 Reranking 预留空间）
            int candidateTopK = Math.min(topK * CANDIDATE_EXPAND_FACTOR, MAX_CANDIDATE_TOPK);
            MetricType metricType = MetricType.valueOf(milvusConfig.getMetricType().toUpperCase());
            String searchParams = buildSearchParams();
            SearchParam.Builder builder = SearchParam.newBuilder()
                    .withCollectionName(collectionName)
                    .withVectorFieldName(AiCacheConstants.MILVUS_VECTOR_FIELD)
                    .withFloatVectors(List.of(queryVectorList))
                    .withTopK(candidateTopK)
                    .withMetricType(metricType)
                    .withParams(searchParams)
                    .withOutFields(List.of(
                            AiCacheConstants.MILVUS_CONTENT_FIELD,
                            AiCacheConstants.MILVUS_DOCUMENT_ID_FIELD,
                            AiCacheConstants.MILVUS_CHUNK_INDEX_FIELD,
                            AiCacheConstants.MILVUS_METADATA_FIELD
                    ));

            // 元数据过滤条件（基于 metadata JSON 字段构造表达式）
            String expr = buildFilterExpr(filters);
            if (expr != null && !expr.isEmpty()) {
                builder.withExpr(expr);
            }

            // 3. 执行检索
            R<SearchResults> searchR = milvusServiceClient.search(builder.build());
            if (searchR == null || searchR.getStatus() != R.Status.Success.getCode()) {
                log.error("Milvus 检索失败：{}", searchR);
                return Collections.emptyList();
            }

            // 4. 解析 Milvus 检索结果（获取全部候选集，未截取 topK）
            SearchResultsWrapper wrapper = new SearchResultsWrapper(searchR.getData().getResults());
            List<SearchResultsWrapper.IDScore> idScoreList = wrapper.getIDScore(0);
            if (idScoreList == null || idScoreList.isEmpty()) {
                log.info("Milvus 检索无结果：query = {}, topK = {}", query, topK);
                return Collections.emptyList();
            }
            List<DocumentVO> candidates = new ArrayList<>(idScoreList.size());
            for (int i = 0; i < idScoreList.size(); i++) {
                SearchResultsWrapper.IDScore idScore = idScoreList.get(i);
                DocumentVO vo = new DocumentVO();
                vo.setScore((double) idScore.getScore());

                // 提取字段值
                Object contentObj = wrapper.getFieldData(AiCacheConstants.MILVUS_CONTENT_FIELD, 0).get(i);
                if (contentObj != null) {
                    vo.setContent(contentObj.toString());
                }
                Object chunkIndexObj = wrapper.getFieldData(AiCacheConstants.MILVUS_CHUNK_INDEX_FIELD, 0).get(i);
                if (chunkIndexObj instanceof Number) {
                    vo.setChunkIndex(((Number) chunkIndexObj).intValue());
                }
                Object documentIdObj = wrapper.getFieldData(AiCacheConstants.MILVUS_DOCUMENT_ID_FIELD, 0).get(i);
                if (documentIdObj instanceof Number) {
                    vo.setDocumentId(((Number) documentIdObj).longValue());
                }

                // 从 metadata 提取 title、module、sourcePath
                Object metadataObj = wrapper.getFieldData(AiCacheConstants.MILVUS_METADATA_FIELD, 0).get(i);
                if (metadataObj instanceof Map) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> metadata = (Map<String, Object>) metadataObj;
                    vo.setTitle((String) metadata.get("title"));
                    vo.setModule((String) metadata.get("module"));
                    vo.setSourcePath((String) metadata.get("source_path"));
                }
                candidates.add(vo);
            }
            log.info("Milvus 检索成功：query = {}, topK = {}, 候选{}条", query, topK, candidates.size());

            // 5. Reranking 重排序（降级策略：未配置/失败/超时 → 返回 Milvus 原始 topK）
            List<DocumentVO> reranked = rerankCandidates(query, candidates, topK);

            // 6. 截取 topK 条返回（rerankCandidates 内部已截取，此处仅兜底）
            int finalSize = Math.min(topK, reranked.size());
            List<DocumentVO> result = new ArrayList<>(finalSize);
            for (int i = 0; i < finalSize; i++) {
                result.add(reranked.get(i));
            }
            return result;
        } catch (Exception e) {
            log.error("向量检索异常：query = {}, topK = {}", query, topK, e);
            return Collections.emptyList();
        }
    }

    /**
     * 对 Milvus 检索候选集执行 Reranking 重排序
     * <p>
     * <b>执行流程</b>：
     * <ol>
     *     <li>未配置 {@link RerankModel} Bean → 跳过重排序，按 Milvus 原始相似度排序截取 topK</li>
     *     <li>候选集数量 ≤ topK → 无需重排序（无候选可淘汰）</li>
     *     <li>构建 {@link RerankRequest}（query + 候选分块 content），通过 CompletableFuture + get(timeout) 调用 RerankModel</li>
     *     <li>根据 RerankResponse 的 score 降序排序，用 rerank score 覆盖 DocumentVO.score</li>
     *     <li>截取 topK 条返回</li>
     * </ol>
     * <p>
     * <b>降级策略</b>（与设计文档 6.3.1 节一致）：
     * <ul>
     *     <li>RerankModel 调用抛异常 → 降级为 Milvus 原始排序，记录 warning 日志</li>
     *     <li>RerankModel 调用超时（&gt;{@link #rerankTimeoutMs}）→ 降级为 Milvus 原始排序，记录 warning 日志</li>
     *     <li>RerankResponse 结果数与候选集不一致 → 降级为 Milvus 原始排序（避免错位）</li>
     * </ul>
     *
     * @param query      用户查询文本
     * @param candidates Milvus 检索候选集（按 Milvus 相似度降序）
     * @param topK       最终返回数量
     * @return 重排序后的候选集（已截取 topK 条）；降级时返回 Milvus 原始 topK
     */
    private List<DocumentVO> rerankCandidates(String query, List<DocumentVO> candidates, int topK) {
        // 1. 未配置 RerankModel → 跳过重排序
        if (rerankModel == null) {
            log.debug("未配置 RerankModel Bean，跳过 Reranking，使用 Milvus 原始排序");
            return truncateByMilvusScore(candidates, topK);
        }
        // 2. 候选集数量 ≤ topK → 无需重排序
        if (candidates.size() <= topK) {
            log.debug("候选集数量 {} ≤ topK {}，跳过 Reranking", candidates.size(), topK);
            return new ArrayList<>(candidates);
        }
        // 3. 构建重排序请求（将 DocumentVO 转为 Spring AI Document，仅保留 content）
        List<Document> documents = new ArrayList<>(candidates.size());
        for (DocumentVO candidate : candidates) {
            String content = candidate.getContent() != null ? candidate.getContent() : "";
            documents.add(new Document(content));
        }
        RerankRequest rerankRequest = new RerankRequest(query, documents);

        // 4. 调用 RerankModel（带超时控制）
        RerankResponse rerankResponse;
        try {
            rerankResponse = invokeRerankWithTimeout(rerankRequest);
        } catch (TimeoutException e) {
            log.warn("Reranking 调用超时（>{}ms），降级为 Milvus 原始排序：query = {}", rerankTimeoutMs, query);
            return truncateByMilvusScore(candidates, topK);
        } catch (Exception e) {
            log.warn("Reranking 调用失败，降级为 Milvus 原始排序：query = {}, error = {}", query, e.getMessage());
            return truncateByMilvusScore(candidates, topK);
        }
        if (rerankResponse == null || CollectionUtils.isEmpty(rerankResponse.getResults())) {
            log.warn("Reranking 返回空结果，降级为 Milvus 原始排序：query = {}", query);
            return truncateByMilvusScore(candidates, topK);
        }

        // 5. 解析 RerankResponse，按 rerank score 重排序
        List<DocumentWithScore> rerankResults = rerankResponse.getResults();
        if (rerankResults.size() != candidates.size()) {
            log.warn("Reranking 返回结果数 {} 与候选集数 {} 不一致，降级为 Milvus 原始排序：query = {}",
                    rerankResults.size(), candidates.size(), query);
            return truncateByMilvusScore(candidates, topK);
        }
        // 按 rerank score 降序排序，取 topN 对应的候选索引
        List<Integer> sortedIndices = new ArrayList<>(rerankResults.size());
        for (int i = 0; i < rerankResults.size(); i++) {
            sortedIndices.add(i);
        }
        sortedIndices.sort((i1, i2) -> {
            Double s1 = rerankResults.get(i1).getScore();
            Double s2 = rerankResults.get(i2).getScore();
            return Double.compare(s2 != null ? s2 : 0.0, s1 != null ? s1 : 0.0);
        });

        // 6. 用 rerank score 覆盖 DocumentVO.score，按新顺序截取 topK
        List<DocumentVO> reranked = new ArrayList<>(Math.min(topK, sortedIndices.size()));
        for (int i = 0; i < Math.min(topK, sortedIndices.size()); i++) {
            int originalIdx = sortedIndices.get(i);
            DocumentVO vo = candidates.get(originalIdx);
            Double rerankScore = rerankResults.get(originalIdx).getScore();
            if (rerankScore != null) {
                vo.setScore(rerankScore);
            }
            reranked.add(vo);
        }
        log.info("Reranking 重排序成功：query = {}, 候选{}条, 返回{}条", query, candidates.size(), reranked.size());
        return reranked;
    }

    /**
     * 调用 RerankModel 并设置超时
     * <p>
     * 使用 {@link CompletableFuture} + {@code get(timeout, unit)} 实现，
     * 超时抛出 {@link TimeoutException}，由调用方降级处理。
     *
     * @param rerankRequest 重排序请求
     * @return 重排序响应
     * @throws TimeoutException 调用超时
     * @throws ExecutionException 调用过程抛出异常
     * @throws InterruptedException 线程被中断
     */
    private RerankResponse invokeRerankWithTimeout(RerankRequest rerankRequest)
            throws TimeoutException, ExecutionException, InterruptedException {
        CompletableFuture<RerankResponse> future = CompletableFuture.supplyAsync(() -> rerankModel.call(rerankRequest));
        return future.get(rerankTimeoutMs, TimeUnit.MILLISECONDS);
    }

    /**
     * 按 Milvus 原始相似度降序截取 topK（Reranking 降级时使用）
     *
     * @param candidates 候选集
     * @param topK       返回数量
     * @return 截取后的列表（保持原顺序）
     */
    private List<DocumentVO> truncateByMilvusScore(List<DocumentVO> candidates, int topK) {
        int size = Math.min(topK, candidates.size());
        return new ArrayList<>(candidates.subList(0, size));
    }

    /**
     * 构建 Milvus 检索参数 JSON
     * <p>
     * 从 Nacos {@code spring.ai.milvus.query-parameters} 读取，并将 {@code efRuntime} 转换为
     * Milvus HNSW 实际查询参数 {@code ef}（语义一致，仅参数名差异）。
     * <p>
     * 转换示例：{@code {efRuntime: 100}} → {@code {"ef":100}}
     *
     * @return Milvus 检索参数 JSON 字符串
     */
    private String buildSearchParams() {
        Map<String, Object> queryParameters = milvusConfig.getQueryParameters();
        if (queryParameters == null || queryParameters.isEmpty()) {
            return "{\"ef\":100}";
        }
        // 转换 efRuntime -> ef（Milvus HNSW 实际参数名）
        Map<String, Object> converted = new HashMap<>(queryParameters);
        Object efRuntime = converted.remove("efRuntime");
        if (efRuntime != null && !converted.containsKey("ef")) {
            converted.put("ef", efRuntime);
        }
        return JsonUtil.classToJson(converted);
    }

    /**
     * 构建元数据过滤表达式
     * <p>
     * 将 {@code {"source_type":"doc","module":"common-cache"}} 转换为
     * {@code metadata["source_type"] == "doc" && metadata["module"] == "common-cache"}。
     *
     * @param filters 过滤条件 Map
     * @return Milvus 表达式字符串；无过滤条件返回 null
     */
    private String buildFilterExpr(Map<String, String> filters) {
        if (filters == null || filters.isEmpty()) {
            return null;
        }
        StringBuilder expr = new StringBuilder();
        for (Map.Entry<String, String> entry : filters.entrySet()) {
            if (expr.length() > 0) {
                expr.append(" && ");
            }
            expr.append("metadata[\"").append(entry.getKey()).append("\"] == \"").append(entry.getValue()).append("\"");
        }
        return expr.toString();
    }

    /**
     * 按条件删除向量数据
     *
     * @param filters 删除条件
     */
    @Override
    public void deleteByFilter(Map<String, String> filters) {
        if (filters == null || filters.isEmpty()) {
            log.warn("按条件删除向量数据：未提供过滤条件，跳过");
            return;
        }
        String collectionName = milvusConfig.getCollectionName();
        String expr = buildFilterExpr(filters);
        try {
            DeleteParam deleteParam = DeleteParam.newBuilder()
                    .withCollectionName(collectionName)
                    .withExpr(expr)
                    .build();
            R<io.milvus.grpc.MutationResult> deleteR = milvusServiceClient.delete(deleteParam);
            if (deleteR == null || deleteR.getStatus() != R.Status.Success.getCode()) {
                log.error("按条件删除向量数据失败：expr = {}, {}", expr, deleteR);
                return;
            }
            log.info("按条件删除向量数据成功：expr = {}", expr);
        } catch (Exception e) {
            log.error("按条件删除向量数据异常：expr = {}", expr, e);
        }
    }

    /**
     * 删除指定文档的所有向量分块
     * <p>
     * 先通过布隆过滤器快速检查 document_id 是否存在（避免无效的 Milvus 查询），
     * 再构建 Milvus DeleteReq 执行删除。
     *
     * @param documentId 文档ID
     */
    @Override
    public void deleteByDocumentId(Long documentId) {
        if (documentId == null) {
            return;
        }
        String collectionName = milvusConfig.getCollectionName();

        // 1. 布隆过滤器快速检查
        if (!bloomFilterService.mightContain(BLOOM_DOCUMENT_ID_PREFIX + documentId)) {
            log.info("布隆过滤器判断 document_id = {} 不存在，跳过 Milvus 删除", documentId);
            return;
        }

        // 2. 构建 DeleteReq 执行删除
        String expr = AiCacheConstants.MILVUS_DOCUMENT_ID_FIELD + " == " + documentId;
        try {
            DeleteParam deleteParam = DeleteParam.newBuilder()
                    .withCollectionName(collectionName)
                    .withExpr(expr)
                    .build();
            R<io.milvus.grpc.MutationResult> deleteR = milvusServiceClient.delete(deleteParam);
            if (deleteR == null || deleteR.getStatus() != R.Status.Success.getCode()) {
                log.error("按 document_id 删除向量数据失败：documentId = {}, {}", documentId, deleteR);
                return;
            }
            log.info("按 document_id 删除向量数据成功：documentId = {}", documentId);
        } catch (Exception e) {
            log.error("按 document_id 删除向量数据异常：documentId = {}", documentId, e);
        }
    }

    /**
     * 获取 Milvus 中分块总数
     *
     * @return 知识库文档分块数量
     */
    @Override
    public long getDocumentCount() {
        String collectionName = milvusConfig.getCollectionName();
        try {
            R<io.milvus.grpc.GetCollectionStatisticsResponse> statsR = milvusServiceClient.getCollectionStatistics(
                    io.milvus.param.collection.GetCollectionStatisticsParam.newBuilder()
                            .withCollectionName(collectionName)
                            .build());
            if (statsR == null || statsR.getStatus() != R.Status.Success.getCode()) {
                log.error("获取 Milvus 集合统计失败：{}", statsR);
                return 0L;
            }
            // statsList 中 key="row_count" 的项是分块总数
            long count = 0L;
            for (io.milvus.grpc.KeyValuePair kv : statsR.getData().getStatsList()) {
                if ("row_count".equals(kv.getKey())) {
                    try {
                        count = Long.parseLong(kv.getValue());
                    } catch (NumberFormatException e) {
                        log.warn("解析 row_count 失败：{}", kv.getValue());
                    }
                    break;
                }
            }
            log.info("Milvus 集合 {} 分块总数：{}", collectionName, count);
            return count;
        } catch (Exception e) {
            log.error("获取 Milvus 集合统计异常：{}", collectionName, e);
            return 0L;
        }
    }

    /*=============================================    私有方法    =============================================*/

    /**
     * 从 metadata Map 提取 Long 值
     *
     * @param metadata 元数据
     * @param key      键
     * @return Long 值；不存在返回 null
     */
    private Long extractLong(Map<String, Object> metadata, String key) {
        if (metadata == null) {
            return null;
        }
        Object value = metadata.get(key);
        if (value instanceof Number) {
            return ((Number) value).longValue();
        }
        if (value instanceof String) {
            try {
                return Long.parseLong((String) value);
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }

    /**
     * 从 metadata Map 提取 Integer 值
     *
     * @param metadata 元数据
     * @param key      键
     * @return Integer 值；不存在返回 null
     */
    private Integer extractInt(Map<String, Object> metadata, String key) {
        if (metadata == null) {
            return null;
        }
        Object value = metadata.get(key);
        if (value instanceof Number) {
            return ((Number) value).intValue();
        }
        if (value instanceof String) {
            try {
                return Integer.parseInt((String) value);
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }
}