package com.zmbdp.chat.service.service;

import com.zmbdp.chat.api.chat.domain.vo.DocumentVO;
import org.springframework.ai.document.Document;

import java.util.List;
import java.util.Map;

/**
 * 向量存储服务
 * <p>
 * 基于 Milvus 实现向量存储与检索；封装 Embedding 生成和向量 CRUD。
 * <p>
 * <b>集合名</b>：{@code scaffold_knowledge}
 * <p>
 * <b>不涉及缓存</b>：向量检索结果不缓存（每次实时查询 Milvus），不经过二级缓存。
 *
 * @author 稚名不带撇
 */
public interface IVectorStoreService {

    /*=============================================    内部调用    =============================================*/

    /**
     * 初始化 Milvus 集合和索引（幂等）
     * <p>
     * 集合不存在则创建，索引不存在则创建。
     * <ul>
     *     <li>向量索引：HNSW，metric_type=IP，efConstruction=200，M=16，efRuntime=100</li>
     *     <li>标量索引：对 document_id、source_type、module、category 建立标量索引</li>
     * </ul>
     */
    void initCollection();

    /**
     * 将文档转换为向量并写入 Milvus
     * <p>
     * 遍历文档列表，对每个分块调用 EmbeddingModel 生成向量，构建 Milvus InsertReq 并执行插入。
     * 插入成功后将 document_id 加入布隆过滤器。
     *
     * @param documents 文档列表（已分块，每个 Document 含 content、metadata）
     */
    void addDocuments(List<Document> documents);

    /**
     * 向量相似性检索
     * <p>
     * 执行 Embedding → Milvus 检索（topK×3 候选）→ Reranking 重排序 → 截取 topK 条。
     *
     * @param query 查询文本
     * @param topK  返回数量（1-20）
     * @return 检索到的文档列表（含 documentId、score、chunkIndex，按相似度降序）
     */
    List<DocumentVO> search(String query, int topK);

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
    List<DocumentVO> searchWithFilter(String query, int topK, Map<String, String> filters);

    /**
     * 按条件删除向量数据
     *
     * @param filters 删除条件
     */
    void deleteByFilter(Map<String, String> filters);

    /**
     * 删除指定文档的所有向量分块
     * <p>
     * 先通过布隆过滤器快速检查 document_id 是否存在（避免无效的 Milvus 查询），
     * 再构建 Milvus DeleteReq 执行删除。
     *
     * @param documentId 文档ID
     */
    void deleteByDocumentId(Long documentId);

    /**
     * 获取 Milvus 中分块总数
     *
     * @return 知识库文档分块数量
     */
    long getDocumentCount();
}
