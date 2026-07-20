package com.zmbdp.admin.service.ai.controller;

import com.zmbdp.chat.api.chat.domain.dto.RetrieveReqDTO;
import com.zmbdp.chat.api.chat.domain.vo.DocumentVO;
import com.zmbdp.chat.api.knowledge.domain.dto.KnowledgeSourceReqDTO;
import com.zmbdp.chat.api.knowledge.domain.dto.SyncReqDTO;
import com.zmbdp.chat.api.knowledge.domain.vo.KnowledgeDocumentVO;
import com.zmbdp.chat.api.knowledge.domain.vo.KnowledgeSourceVO;
import com.zmbdp.chat.api.knowledge.domain.vo.SyncResultVO;
import com.zmbdp.common.domain.domain.Result;
import com.zmbdp.common.domain.domain.vo.BasePageVO;
import com.zmbdp.common.log.annotation.LogAction;
import com.zmbdp.admin.service.ai.service.IAiAdminService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

/**
 * B端知识库管理 Controller
 * <p>
 * 作为B端知识库管理的统一入口，通过 Feign 调用 chat-service 的 {@code KnowledgeApi}。
 * <p>
 * <b>接口路径</b>：
 * <ul>
 *     <li>{@code GET /knowledge/sources}：获取知识源列表（分页）</li>
 *     <li>{@code POST /knowledge/sources}：新增知识源</li>
 *     <li>{@code PUT /knowledge/sources/{id}}：更新知识源</li>
 *     <li>{@code DELETE /knowledge/sources/{id}}：删除知识源</li>
 *     <li>{@code POST /knowledge/sync}：触发知识同步</li>
 *     <li>{@code GET /knowledge/documents}：获取文档列表（分页）</li>
 *     <li>{@code GET /knowledge/documents/{id}}：获取文档详情</li>
 *     <li>{@code DELETE /knowledge/documents/{id}}：删除文档</li>
 *     <li>{@code POST /knowledge/documents/upload}：上传文档</li>
 *     <li>{@code POST /knowledge/retrieve-test}：召回测试</li>
 * </ul>
 * <p>
 * <b>网关路径映射</b>：前端请求 {@code /admin/knowledge/sources} → gateway StripPrefix=1 → 本 Controller {@code /knowledge/sources}
 * <p>
 * <b>操作审计</b>：新增/更新/删除知识源、删除文档、触发同步等管理操作通过 {@code @LogAction} 注解自动记录到 operation_log 表。
 *
 * @author 稚名不带撇
 */
@Slf4j
@RestController
@RequestMapping("/knowledge")
public class KnowledgeController {

    /**
     * B端 AI 管理业务编排服务
     */
    @Autowired
    private IAiAdminService aiAdminService;

    /* ============================================= 知识源管理 ============================================= */

    /**
     * 获取知识源列表（分页）
     *
     * @param pageNo   页码，默认 1
     * @param pageSize 每页数量，默认 10
     * @param type     类型过滤（doc/javadoc/config/code，可选）
     * @return 知识源分页结果
     */
    @GetMapping("/sources")
    public Result<BasePageVO<KnowledgeSourceVO>> listSources(
            @RequestParam(value = "pageNo", defaultValue = "1") Integer pageNo,
            @RequestParam(value = "pageSize", defaultValue = "10") Integer pageSize,
            @RequestParam(value = "type", required = false) String type) {
        return Result.success(aiAdminService.listSources(pageNo, pageSize, type));
    }

    /**
     * 新增知识源
     *
     * @param dto 知识源请求 DTO
     * @return 新建后的知识源 VO（含生成的 ID）
     */
    @PostMapping("/sources")
    @LogAction(value = "新增知识源", module = "knowledge", recordParams = true)
    public Result<KnowledgeSourceVO> createSource(@Validated @RequestBody KnowledgeSourceReqDTO dto) {
        return Result.success(aiAdminService.createSource(dto));
    }

    /**
     * 更新知识源
     *
     * @param id  知识源ID
     * @param dto 知识源请求 DTO
     * @return 操作结果
     */
    @PutMapping("/sources/{id}")
    @LogAction(value = "更新知识源", module = "knowledge", recordParams = true)
    public Result<Void> updateSource(@PathVariable("id") Long id, @Validated @RequestBody KnowledgeSourceReqDTO dto) {
        aiAdminService.updateSource(id, dto);
        return Result.success();
    }

    /**
     * 删除知识源（级联删除）
     * <p>
     * 同时删除该知识源下所有文档（MySQL 软删除）和向量分块（Milvus 物理删除），操作不可逆。
     *
     * @param id 知识源ID
     * @return 操作结果
     */
    @DeleteMapping("/sources/{id}")
    @LogAction(value = "删除知识源", module = "knowledge")
    public Result<Void> deleteSource(@PathVariable("id") Long id) {
        aiAdminService.deleteSource(id);
        return Result.success();
    }

    /**
     * 触发知识同步
     * <p>
     * 扫描知识源路径 → 哈希比对增量更新 → 分块 → 向量化 → 写入 Milvus。
     *
     * @param dto 同步请求（含 sourceType、force 参数）
     * @return 同步结果统计
     */
    @PostMapping("/sync")
    @LogAction(value = "知识同步", module = "knowledge", recordParams = true)
    public Result<SyncResultVO> syncKnowledge(@Validated @RequestBody SyncReqDTO dto) {
        return Result.success(aiAdminService.syncKnowledge(dto));
    }

    /* ============================================= 文档管理 ============================================= */

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
    public Result<BasePageVO<KnowledgeDocumentVO>> listDocuments(
            @RequestParam(value = "pageNo", defaultValue = "1") Integer pageNo,
            @RequestParam(value = "pageSize", defaultValue = "10") Integer pageSize,
            @RequestParam(value = "sourceId", required = false) Long sourceId,
            @RequestParam(value = "status", required = false) String status) {
        return Result.success(aiAdminService.listDocuments(pageNo, pageSize, sourceId, status));
    }

    /**
     * 获取文档详情（含完整内容）
     *
     * @param id 文档ID
     * @return 文档详情 VO
     */
    @GetMapping("/documents/{id}")
    public Result<KnowledgeDocumentVO> getDocument(@PathVariable("id") Long id) {
        return Result.success(aiAdminService.getDocument(id));
    }

    /**
     * 删除文档（级联删除向量）
     * <p>
     * MySQL 软删除文档记录 + Milvus 物理删除对应向量分块。
     *
     * @param id 文档ID
     * @return 操作结果
     */
    @DeleteMapping("/documents/{id}")
    @LogAction(value = "删除文档", module = "knowledge")
    public Result<Void> deleteDocument(@PathVariable("id") Long id) {
        aiAdminService.deleteDocument(id);
        return Result.success();
    }

    /**
     * 上传文档到指定知识源
     * <p>
     * 将上传的文件经 Feign 转发到 chat-service，由 chat-service 保存到知识源 path 目录下并立即执行
     * 分块、向量化、写入 Milvus，不等定时同步任务。
     * <p>
     * <b>文件限制</b>：大小 ≤ 50MB；扩展名 ∈ {.md, .txt, .html, .java, .py, .xml, .json}。
     *
     * @param knowledgeSourceId 知识源ID
     * @param file              上传的文件
     * @return 新插入的文档 VO（含生成的 ID）
     */
    @PostMapping(value = "/documents/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @LogAction(value = "上传文档", module = "knowledge", recordParams = true)
    public Result<KnowledgeDocumentVO> uploadDocument(
            @RequestParam("knowledgeSourceId") Long knowledgeSourceId,
            @RequestPart("file") MultipartFile file) {
        log.info("上传文档：knowledgeSourceId = {}, fileName = {}, size = {} 字节",
                knowledgeSourceId, file != null ? file.getOriginalFilename() : null,
                file != null ? file.getSize() : 0);
        return Result.success(aiAdminService.uploadDocument(knowledgeSourceId, file));
    }

    /* ============================================= 召回测试 ============================================= */

    /**
     * 知识源召回测试
     * <p>
     * 输入测试问题，返回检索到的文档分块及相似度分数，用于验证知识库质量。
     *
     * @param request 检索请求（含问题、topK、过滤条件）
     * @return 命中的文档分块列表（按相似度降序，已 Reranking）
     */
    @PostMapping("/retrieve-test")
    public Result<List<DocumentVO>> retrieveTest(@Validated @RequestBody RetrieveReqDTO request) {
        return Result.success(aiAdminService.retrieveTest(request));
    }
}