package com.zmbdp.chat.service.controller;

import com.zmbdp.chat.api.chat.domain.dto.RetrieveReqDTO;
import com.zmbdp.chat.api.chat.domain.vo.DocumentVO;
import com.zmbdp.chat.api.knowledge.domain.dto.KnowledgeSourceReqDTO;
import com.zmbdp.chat.api.knowledge.domain.dto.SyncReqDTO;
import com.zmbdp.chat.api.knowledge.domain.vo.KnowledgeDocumentVO;
import com.zmbdp.chat.api.knowledge.domain.vo.KnowledgeSourceVO;
import com.zmbdp.chat.api.knowledge.domain.vo.SyncResultVO;
import com.zmbdp.chat.api.knowledge.feign.KnowledgeApi;
import com.zmbdp.chat.service.service.IKnowledgeService;
import com.zmbdp.chat.service.service.IVectorStoreService;
import com.zmbdp.common.domain.domain.Result;
import com.zmbdp.common.domain.domain.vo.BasePageVO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.util.StringUtils;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 知识库管理 Controller（chat-service 端）
 * <p>
 * 实现 {@link KnowledgeApi} Feign 接口，提供知识源 CRUD、文档管理、文档上传、知识同步、召回测试等能力
 * <p>
 * <b>召回测试</b>：{@link #retrieveTest} 复用 {@link IVectorStoreService} 的 RAG 检索能力
 * （Embedding → Milvus 检索 → Reranking），与 {@link ChatController#retrieveContext} 共用同一套检索逻辑。
 *
 * @author 稚名不带撇
 */
@Slf4j
@RestController
@RequestMapping("/knowledge")
public class KnowledgeController implements KnowledgeApi {

    /**
     * 默认 topK（从 Nacos 配置 {@code scaffold.rag.top-k} 读取，范围 1-20）
     */
    @Value("${scaffold.rag.top-k:5}")
    private int defaultTopK;

    /**
     * 知识库管理服务
     */
    @Autowired
    private IKnowledgeService knowledgeService;

    /**
     * 向量存储服务（召回测试复用其 RAG 检索能力）
     */
    @Autowired
    private IVectorStoreService vectorStoreService;

    /**
     * 获取知识源列表（分页）
     *
     * @param pageNo   页码，默认 1
     * @param pageSize 每页数量，默认 10
     * @param type     类型过滤（doc/javadoc/config/code，可选）
     * @return 知识源分页结果
     */
    @Override
    public Result<BasePageVO<KnowledgeSourceVO>> getSources(Integer pageNo, Integer pageSize, String type) {
        log.info("获取知识源列表：pageNo = {}, pageSize = {}, type = {}", pageNo, pageSize, type);
        BasePageVO<KnowledgeSourceVO> result = knowledgeService.listSources(pageNo, pageSize, type);
        return Result.success(result);
    }

    /**
     * 新增知识源
     *
     * @param dto 知识源请求 DTO
     * @return 新建后的知识源 VO（含生成的 ID）
     */
    @Override
    public Result<KnowledgeSourceVO> addSource(@Validated KnowledgeSourceReqDTO dto) {
        log.info("新增知识源：name = {}, type = {}", dto.getName(), dto.getType());
        KnowledgeSourceVO vo = knowledgeService.createSource(dto);
        return Result.success(vo);
    }

    /**
     * 更新知识源
     *
     * @param id  知识源ID
     * @param dto 知识源请求 DTO
     * @return 操作结果
     */
    @Override
    public Result<Void> updateSource(Long id, @Validated KnowledgeSourceReqDTO dto) {
        log.info("更新知识源：id = {}, name = {}", id, dto.getName());
        knowledgeService.updateSource(id, dto);
        return Result.success();
    }

    /**
     * 删除知识源（级联删除）
     *
     * @param id 知识源ID
     * @return 操作结果
     */
    @Override
    public Result<Void> deleteSource(Long id) {
        log.info("删除知识源：id = {}", id);
        knowledgeService.deleteSource(id);
        return Result.success();
    }

    /**
     * 触发知识同步
     *
     * @param dto 同步请求（含 sourceType、force 参数）
     * @return 同步结果统计
     */
    @Override
    public Result<SyncResultVO> sync(@Validated SyncReqDTO dto) {
        log.info("触发知识同步：sourceType = {}, force = {}", dto.getSourceType(), dto.getForce());
        SyncResultVO result = knowledgeService.sync(dto);
        return Result.success(result);
    }

    /**
     * 获取文档列表（分页）
     *
     * @param pageNo   页码，默认 1
     * @param pageSize 每页数量，默认 10
     * @param sourceId 知识源ID过滤（可选）
     * @param status   状态过滤（ACTIVE/DELETED/SYNCING，可选）
     * @return 文档分页结果
     */
    @Override
    public Result<BasePageVO<KnowledgeDocumentVO>> getDocuments(Integer pageNo, Integer pageSize, Long sourceId, String status) {
        log.info("获取文档列表：pageNo = {}, pageSize = {}, sourceId = {}, status = {}", pageNo, pageSize, sourceId, status);
        BasePageVO<KnowledgeDocumentVO> result = knowledgeService.listDocuments(pageNo, pageSize, sourceId, status);
        return Result.success(result);
    }

    /**
     * 获取文档详情（含完整内容）
     *
     * @param id 文档ID
     * @return 文档详情 VO
     */
    @Override
    public Result<KnowledgeDocumentVO> getDocument(Long id) {
        log.info("获取文档详情：id = {}", id);
        KnowledgeDocumentVO vo = knowledgeService.getDocument(id);
        return Result.success(vo);
    }

    /**
     * 删除文档（级联删除向量）
     *
     * @param id 文档ID
     * @return 操作结果
     */
    @Override
    public Result<Void> deleteDocument(Long id) {
        log.info("删除文档：id = {}", id);
        knowledgeService.deleteDocument(id);
        return Result.success();
    }

    /**
     * 知识源召回测试
     * <p>
     * 复用 {@code /chat/retrieve} 的 RAG 检索能力（Embedding → Milvus 检索 → Reranking），
     * 不调用 LLM，仅返回检索结果，用于验证知识库质量。
     *
     * @param request 检索请求（含问题、topK、过滤条件）
     * @return 命中的文档分块列表（按相似度降序，已 Reranking）
     */
    @Override
    public Result<List<DocumentVO>> retrieveTest(@Validated RetrieveReqDTO request) {
        log.info("知识源召回测试：question = {}, topK = {}, sourceType = {}, module = {}",
                request.getQuestion(), request.getTopK(), request.getSourceType(), request.getModule());
        List<DocumentVO> list = doRetrieve(request);
        return Result.success(list);
    }

    /**
     * 上传文档到指定知识源
     * <p>
     * 将上传的文件保存到知识源 path 目录下，并立即对该文件执行分块、向量化、写入 Milvus。
     * <p>
     * <b>文件限制</b>：大小 ≤ 50MB；扩展名 ∈ {.md, .txt, .html, .java, .py, .xml, .json}。
     *
     * @param knowledgeSourceId 知识源ID
     * @param file              上传的文件
     * @return 新插入的文档 VO（含生成的 ID）
     */
    @Override
    public Result<KnowledgeDocumentVO> uploadDocument(Long knowledgeSourceId, MultipartFile file) {
        log.info("上传文档：knowledgeSourceId = {}, fileName = {}, size = {} 字节",
                knowledgeSourceId, file != null ? file.getOriginalFilename() : null, file != null ? file.getSize() : 0);
        KnowledgeDocumentVO vo = knowledgeService.uploadDocument(knowledgeSourceId, file);
        return Result.success(vo);
    }

    /*=============================================    私有方法    =============================================*/

    /**
     * 执行 RAG 检索
     * <p>
     * 当 sourceType 或 module 非空时，使用 {@link IVectorStoreService#searchWithFilter} 带过滤条件检索；
     * 否则使用 {@link IVectorStoreService#search} 默认检索。topK 为空时使用 Nacos 配置的默认值。
     *
     * @param request 检索请求
     * @return 命中的文档分块列表
     */
    private List<DocumentVO> doRetrieve(RetrieveReqDTO request) {
        int topK = request.getTopK() != null ? request.getTopK() : defaultTopK;
        boolean hasSourceType = StringUtils.hasText(request.getSourceType());
        boolean hasModule = StringUtils.hasText(request.getModule());
        if (hasSourceType || hasModule) {
            Map<String, String> filters = new HashMap<>(4);
            if (hasSourceType) {
                filters.put("source_type", request.getSourceType());
            }
            if (hasModule) {
                filters.put("module", request.getModule());
            }
            return vectorStoreService.searchWithFilter(request.getQuestion(), topK, filters);
        }
        List<DocumentVO> result = vectorStoreService.search(request.getQuestion(), topK);
        return result != null ? result : new ArrayList<>();
    }
}