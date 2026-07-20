package com.zmbdp.chat.service.service;

import com.zmbdp.chat.api.knowledge.domain.dto.KnowledgeSourceReqDTO;
import com.zmbdp.chat.api.knowledge.domain.dto.SyncReqDTO;
import com.zmbdp.chat.api.knowledge.domain.vo.KnowledgeDocumentVO;
import com.zmbdp.chat.api.knowledge.domain.vo.KnowledgeSourceVO;
import com.zmbdp.chat.api.knowledge.domain.vo.SyncResultVO;
import com.zmbdp.common.domain.domain.vo.BasePageVO;

/**
 * 知识库管理服务
 * <p>
 * 知识库管理（B 端 CRUD），管理知识源、文档；由 admin-service 通过 Feign 调用。
 * <p>
 * <b>核心依赖</b>：SysAiKnowledgeSourceMapper、SysAiDocumentMapper、{@link IKnowledgeLoaderService}、
 * {@link IVectorStoreService}、SnowflakeIdService。
 *
 * @author 稚名不带撇
 */
public interface IKnowledgeService {

    /*=============================================    前端调用    =============================================*/

    /**
     * 分页查询知识源列表
     *
     * @param pageNo   页码
     * @param pageSize 每页数量
     * @param type     类型过滤（doc/javadoc/config/code，可选）
     * @return 知识源分页结果
     */
    BasePageVO<KnowledgeSourceVO> listSources(Integer pageNo, Integer pageSize, String type);

    /**
     * 获取知识源详情
     *
     * @param id 知识源ID
     * @return 知识源详情
     */
    KnowledgeSourceVO getSource(Long id);

    /**
     * 新增知识源
     *
     * @param dto 知识源请求 DTO
     * @return 新建后的知识源 VO（含生成的 ID）
     */
    KnowledgeSourceVO createSource(KnowledgeSourceReqDTO dto);

    /**
     * 更新知识源
     *
     * @param id  知识源ID
     * @param dto 知识源请求 DTO
     */
    void updateSource(Long id, KnowledgeSourceReqDTO dto);

    /**
     * 删除知识源
     * <p>
     * 级联删除：Milvus 物理删除 + MySQL 软删除。
     *
     * @param id 知识源ID
     */
    void deleteSource(Long id);

    /**
     * 分页查询文档列表
     *
     * @param pageNo   页码
     * @param pageSize 每页数量
     * @param sourceId 知识源ID过滤（可选）
     * @param status   状态过滤（ACTIVE/DELETED/SYNCING，可选）
     * @return 文档分页结果
     */
    BasePageVO<KnowledgeDocumentVO> listDocuments(Integer pageNo, Integer pageSize, Long sourceId, String status);

    /**
     * 获取文档详情（含完整内容）
     *
     * @param id 文档ID
     * @return 文档详情
     */
    KnowledgeDocumentVO getDocument(Long id);

    /**
     * 删除文档
     * <p>
     * Milvus 物理删除 + MySQL 软删除。
     *
     * @param id 文档ID
     */
    void deleteDocument(Long id);

    /**
     * 触发知识同步
     * <p>
     * 委托给 {@link IKnowledgeLoaderService#syncKnowledge} 执行同步。
     *
     * @param dto 同步请求（含 sourceType、force 参数）
     * @return 同步结果统计
     */
    SyncResultVO sync(SyncReqDTO dto);
}
