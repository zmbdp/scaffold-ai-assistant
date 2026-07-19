package com.zmbdp.chat.service.service;

import com.zmbdp.chat.api.ai.domain.dto.AiConfigDTO;
import com.zmbdp.chat.api.ai.domain.dto.ToolConfigDTO;
import com.zmbdp.chat.api.ai.domain.dto.ToolTestReqDTO;
import com.zmbdp.chat.api.ai.domain.vo.AiConfigVO;
import com.zmbdp.chat.api.ai.domain.vo.ModelVO;
import com.zmbdp.chat.api.ai.domain.vo.ToolTestResultVO;
import com.zmbdp.chat.api.ai.domain.vo.ToolVO;
import com.zmbdp.chat.api.knowledge.domain.dto.KnowledgeSourceReqDTO;
import com.zmbdp.chat.api.knowledge.domain.vo.KnowledgeDocumentVO;
import com.zmbdp.chat.api.knowledge.domain.vo.KnowledgeSourceVO;
import com.zmbdp.chat.service.domain.entity.SysAiOperationLog;
import com.zmbdp.common.domain.domain.vo.BasePageVO;

import java.util.List;

/**
 * B端管理服务（AI 业务相关）
 * <p>
 * 由 chat-service 提供给 admin-service 通过 Feign 调用，包含知识源管理、AI 配置管理、
 * 工具管理、操作日志查询等功能。
 * <p>
 * <b>职责边界</b>：用户管理（sys_user 表 CRUD）已在 zmbdp-admin-service 中实现，本服务不再重复开发。
 *
 * @author 稚名不带撇
 */
public interface IAdminService {

    /*=============================================    前端调用    =============================================*/

    /**
     * 分页查询知识源列表
     *
     * @param pageNo   页码
     * @param pageSize 每页数量
     * @param type     类型过滤（doc/javadoc/config/code，可选）
     * @return 知识源分页结果
     */
    BasePageVO<KnowledgeSourceVO> listKnowledgeSources(Integer pageNo, Integer pageSize, String type);

    /**
     * 获取知识源详情
     *
     * @param id 知识源ID
     * @return 知识源 VO
     */
    KnowledgeSourceVO getKnowledgeSource(Long id);

    /**
     * 新增知识源
     *
     * @param dto 知识源请求 DTO
     * @return 新建后的知识源 VO（含生成的 ID）
     */
    KnowledgeSourceVO createKnowledgeSource(KnowledgeSourceReqDTO dto);

    /**
     * 更新知识源
     *
     * @param id  知识源ID
     * @param dto 知识源请求 DTO
     */
    void updateKnowledgeSource(Long id, KnowledgeSourceReqDTO dto);

    /**
     * 删除知识源（级联删除）
     *
     * @param id 知识源ID
     */
    void deleteKnowledgeSource(Long id);

    /**
     * 获取 AI 配置
     * <p>
     * 返回运行时业务参数 + 脱敏后的 api-key。
     *
     * @return AI 配置 VO
     */
    AiConfigVO getAiConfig();

    /**
     * 更新 AI 配置
     * <p>
     * 仅更新 sys_argument 表的 ai.* 运行时参数，api-key 等基础设施配置不通过本接口修改。
     *
     * @param dto AI 配置更新请求
     */
    void updateAiConfig(AiConfigDTO dto);

    /**
     * 获取可用模型列表
     *
     * @return 模型 VO 列表
     */
    List<ModelVO> listModels();

    /**
     * 测试 AI 连接
     *
     * @param model   模型名称
     * @param message 测试消息
     * @return true=连接成功，false=连接失败
     */
    boolean testAiConnection(String model, String message);

    /**
     * 获取工具列表
     *
     * @return 工具 VO 列表
     */
    List<ToolVO> listTools();

    /**
     * 更新工具配置
     *
     * @param name 工具名称
     * @param dto  工具配置更新请求
     */
    void updateToolConfig(String name, ToolConfigDTO dto);

    /**
     * 测试工具调用
     *
     * @param name 工具名称
     * @param dto  测试请求
     * @return 测试结果
     */
    ToolTestResultVO testTool(String name, ToolTestReqDTO dto);

    /*=============================================    远程调用    =============================================*/

    /**
     * 分页查询 AI 操作日志
     *
     * @param pageNo        页码
     * @param pageSize      每页数量
     * @param operationType AI 操作类型过滤（CHAT/RETRIEVE/EMBEDDING/RERANK，可选）
     * @return 操作日志分页结果
     */
    BasePageVO<SysAiOperationLog> listOperationLogs(Integer pageNo, Integer pageSize, String operationType);

    /**
     * 查询单次 AI 调用详情
     *
     * @param operationId AI 操作日志ID
     * @return 操作日志实体（含完整 Prompt、响应、工具调用链路、Token 消耗）
     */
    SysAiOperationLog getOperationLogDetail(Long operationId);

    /*=============================================    内部调用    =============================================*/

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
}
