package com.zmbdp.chat.api.knowledge.feign;

import com.zmbdp.chat.api.chat.domain.dto.RetrieveReqDTO;
import com.zmbdp.chat.api.chat.domain.vo.DocumentVO;
import com.zmbdp.chat.api.knowledge.domain.dto.KnowledgeSourceReqDTO;
import com.zmbdp.chat.api.knowledge.domain.dto.SyncReqDTO;
import com.zmbdp.chat.api.knowledge.domain.vo.KnowledgeDocumentVO;
import com.zmbdp.chat.api.knowledge.domain.vo.KnowledgeSourceVO;
import com.zmbdp.chat.api.knowledge.domain.vo.SyncResultVO;
import com.zmbdp.common.domain.domain.Result;
import com.zmbdp.common.domain.domain.vo.BasePageVO;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.List;

/**
 * 知识库管理远程调用 Api
 * <p>
 * 提供知识源 CRUD、文档管理、知识同步、召回测试等能力。
 * <p>
 * <b>上传文档接口</b>（{@code POST /knowledge/documents/upload}）因涉及 MultipartFile，
 * 由 admin-service 直接代理而非通过 Feign 调用（Feign 对文件上传支持有限），
 * 因此本接口不包含上传方法。
 * <p>
 * <b>分页约定</b>：参数统一使用 {@code pageNo}/{@code pageSize}（与脚手架 BasePageReqDTO 字段名一致），
 * 响应统一使用 {@link BasePageVO}（含 totals/totalPages/list 字段）。
 *
 * @author 稚名不带撇
 */
@FeignClient(contextId = "knowledgeApi", name = "zmbdp-chat-service", path = "/knowledge")
public interface KnowledgeApi {

    /**
     * 获取知识源列表（分页）
     *
     * @param pageNo   页码，默认 1
     * @param pageSize 每页数量，默认 10
     * @param type     类型过滤（doc/javadoc/config/code，可选）
     * @return 知识源分页结果
     */
    @GetMapping("/sources")
    Result<BasePageVO<KnowledgeSourceVO>> getSources(@RequestParam(value = "pageNo", defaultValue = "1") Integer pageNo,
                                                     @RequestParam(value = "pageSize", defaultValue = "10") Integer pageSize,
                                                     @RequestParam(value = "type", required = false) String type);

    /**
     * 新增知识源
     *
     * @param dto 知识源请求 DTO
     * @return 新建后的知识源 VO（含生成的 ID）
     */
    @PostMapping("/sources")
    Result<KnowledgeSourceVO> addSource(@RequestBody KnowledgeSourceReqDTO dto);

    /**
     * 更新知识源
     * <p>
     * 仅更新传入的非空字段；若修改 path 或 chunkSize/chunkOverlap，需手动触发知识同步以重建向量。
     *
     * @param id  知识源ID
     * @param dto 知识源请求 DTO
     * @return 操作结果
     */
    @PutMapping("/sources/{id}")
    Result<Void> updateSource(@PathVariable("id") Long id, @RequestBody KnowledgeSourceReqDTO dto);

    /**
     * 删除知识源（级联删除）
     * <p>
     * 同时删除该知识源下所有文档（MySQL 软删除）和向量分块（Milvus 物理删除），操作不可逆。
     *
     * @param id 知识源ID
     * @return 操作结果
     */
    @DeleteMapping("/sources/{id}")
    Result<Void> deleteSource(@PathVariable("id") Long id);

    /**
     * 触发知识同步
     * <p>
     * 扫描知识源路径 → 哈希比对增量更新 → 分块 → 向量化 → 写入 Milvus。
     *
     * @param dto 同步请求（含 sourceType、force 参数）
     * @return 同步结果统计
     */
    @PostMapping("/sync")
    Result<SyncResultVO> sync(@RequestBody SyncReqDTO dto);

    /**
     * 获取文档列表（分页）
     *
     * @param pageNo   页码，默认 1
     * @param pageSize 每页数量，默认 10
     * @param sourceId 知识源ID过滤（可选）
     * @param status   状态过滤（ACTIVE/DELETED/SYNCING，可选）
     * @return 文档分页结果
     */
    @GetMapping("/documents")
    Result<BasePageVO<KnowledgeDocumentVO>> getDocuments(@RequestParam(value = "pageNo", defaultValue = "1") Integer pageNo,
                                                         @RequestParam(value = "pageSize", defaultValue = "10") Integer pageSize,
                                                         @RequestParam(value = "sourceId", required = false) Long sourceId,
                                                         @RequestParam(value = "status", required = false) String status);

    /**
     * 获取文档详情（含完整内容）
     *
     * @param id 文档ID
     * @return 文档详情 VO
     */
    @GetMapping("/documents/{id}")
    Result<KnowledgeDocumentVO> getDocument(@PathVariable("id") Long id);

    /**
     * 删除文档（级联删除向量）
     * <p>
     * MySQL 软删除文档记录 + Milvus 物理删除对应向量分块。
     *
     * @param id 文档ID
     * @return 操作结果
     */
    @DeleteMapping("/documents/{id}")
    Result<Void> deleteDocument(@PathVariable("id") Long id);

    /**
     * 知识源召回测试
     * <p>
     * 输入测试问题，返回检索到的文档分块及相似度分数。
     * 复用 {@code /chat/retrieve} 的 RAG 检索能力（Embedding → Milvus 检索 → Reranking），
     * 不调用 LLM，仅返回检索结果，用于验证知识库质量。
     *
     * @param request 检索请求（含问题、topK、过滤条件）
     * @return 命中的文档分块列表（按相似度降序，已 Reranking）
     */
    @PostMapping("/retrieve-test")
    Result<List<DocumentVO>> retrieveTest(@RequestBody RetrieveReqDTO request);
}
