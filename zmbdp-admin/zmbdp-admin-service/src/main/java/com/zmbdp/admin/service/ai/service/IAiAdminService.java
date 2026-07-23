package com.zmbdp.admin.service.ai.service;

import com.zmbdp.chat.api.ai.domain.dto.AiConfigDTO;
import com.zmbdp.chat.api.ai.domain.dto.ToolConfigDTO;
import com.zmbdp.chat.api.ai.domain.dto.ToolTestReqDTO;
import com.zmbdp.chat.api.ai.domain.vo.AiConfigVO;
import com.zmbdp.chat.api.ai.domain.vo.ModelVO;
import com.zmbdp.chat.api.ai.domain.vo.ToolTestResultVO;
import com.zmbdp.chat.api.ai.domain.vo.ToolVO;
import com.zmbdp.chat.api.chat.domain.dto.RetrieveReqDTO;
import com.zmbdp.chat.api.chat.domain.vo.DocumentVO;
import com.zmbdp.chat.api.feedback.domain.vo.FeedbackAdminVO;
import com.zmbdp.chat.api.knowledge.domain.dto.KnowledgeSourceReqDTO;
import com.zmbdp.chat.api.knowledge.domain.dto.SyncReqDTO;
import com.zmbdp.chat.api.knowledge.domain.vo.KnowledgeDocumentVO;
import com.zmbdp.chat.api.knowledge.domain.vo.KnowledgeSourceVO;
import com.zmbdp.chat.api.knowledge.domain.vo.SyncResultVO;
import com.zmbdp.chat.api.operationlog.domain.vo.OperationLogVO;
import com.zmbdp.chat.api.statistics.domain.vo.AiMetricsVO;
import com.zmbdp.chat.api.statistics.domain.vo.ConversationStatisticsVO;
import com.zmbdp.chat.api.statistics.domain.vo.FeedbackStatisticsVO;
import com.zmbdp.chat.api.statistics.domain.vo.HotQuestionVO;
import com.zmbdp.chat.api.statistics.domain.vo.ToolStatisticsVO;
import com.zmbdp.chat.api.statistics.domain.vo.UserStatisticsVO;
import com.zmbdp.chat.api.system.domain.vo.SystemHealthVO;
import com.zmbdp.common.domain.domain.vo.BasePageVO;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

/**
 * B端 AI 管理业务编排服务
 * <p>
 * admin-service 的核心 AI 管理服务，通过 Feign 调用 chat-service 的各管理接口，
 * 为B端管理后台提供知识库管理、AI 配置管理、工具管理、操作日志查询、统计分析等能力。
 * <p>
 * <b>命名说明</b>：本接口命名为 {@code IAiAdminService} 而非 {@code IAdminService}，
 * 以避免与 chat-service 的 {@code com.zmbdp.chat.service.service.IAdminService} 混淆
 * <p>
 * <b>职责边界</b>：
 * <ul>
 *     <li>本接口仅做业务编排和 Feign 透传，不实现具体业务逻辑</li>
 *     <li>具体业务逻辑由 chat-service 的各 Service 实现</li>
 *     <li>B端操作审计由 Controller 层的 {@code @LogAction} 注解自动记录</li>
 * </ul>
 *
 * @author 稚名不带撇
 */
public interface IAiAdminService {

    /* ============================================= 知识库管理 ============================================= */

    /**
     * 获取知识源列表（分页）
     *
     * @param pageNo   页码
     * @param pageSize 每页数量
     * @param type     类型过滤（可选）
     * @return 知识源分页结果
     */
    BasePageVO<KnowledgeSourceVO> listSources(Integer pageNo, Integer pageSize, String type);

    /**
     * 新增知识源
     *
     * @param dto 知识源请求 DTO
     * @return 新建后的知识源 VO
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
     * 删除知识源（级联删除）
     *
     * @param id 知识源ID
     */
    void deleteSource(Long id);

    /**
     * 触发知识同步（异步）
     * <p>
     * 通过 MQ 发送同步消息，立即返回"已提交"提示。
     * chat-service 消费端异步执行同步流程，前端通过文档列表查看同步结果。
     *
     * @param dto 同步请求（含 sourceType、force 参数）
     * @return 提示信息（如"知识同步任务已提交，请稍后通过文档列表查看同步结果"）
     */
    String syncKnowledge(SyncReqDTO dto);

    /**
     * 获取文档列表（分页）
     *
     * @param pageNo   页码
     * @param pageSize 每页数量
     * @param sourceId 知识源ID过滤（可选）
     * @param status   状态过滤（可选）
     * @return 文档分页结果
     */
    BasePageVO<KnowledgeDocumentVO> listDocuments(Integer pageNo, Integer pageSize, Long sourceId, String status);

    /**
     * 获取文档详情
     *
     * @param id 文档ID
     * @return 文档详情 VO
     */
    KnowledgeDocumentVO getDocument(Long id);

    /**
     * 删除文档（级联删除向量）
     *
     * @param id 文档ID
     */
    void deleteDocument(Long id);

    /**
     * 知识源召回测试
     *
     * @param request 检索请求
     * @return 命中的文档分块列表
     */
    List<DocumentVO> retrieveTest(RetrieveReqDTO request);

    /**
     * 上传文档到指定知识源
     * <p>
     * 通过 Feign 调用 chat-service 的 {@code KnowledgeApi.uploadDocument}，
     * 将上传的文件保存到知识源 path 目录下并立即向量化。
     *
     * @param knowledgeSourceId 知识源ID
     * @param file              上传的文件（MultipartFile）
     * @return 新插入的文档 VO（含生成的 ID）
     */
    KnowledgeDocumentVO uploadDocument(Long knowledgeSourceId, MultipartFile file);

    /* ============================================= AI 配置管理 ============================================= */

    /**
     * 获取 AI 配置
     *
     * @return AI 配置 VO
     */
    AiConfigVO getAiConfig();

    /**
     * 更新 AI 配置
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
     */
    void testAiConnection();

    /* ============================================= 工具管理 ============================================= */

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

    /* ============================================= 操作日志 ============================================= */

    /**
     * 获取 AI 调用链路日志列表（分页）
     *
     * @param pageNo        页码
     * @param pageSize      每页数量
     * @param operationType 操作类型过滤（可选）
     * @param model         模型名称过滤（可选）
     * @param status        调用状态过滤（可选）
     * @param startDate     开始日期（可选）
     * @param endDate       结束日期（可选）
     * @return 操作日志分页结果
     */
    BasePageVO<OperationLogVO> listOperationLogs(Integer pageNo, Integer pageSize, String operationType,
                                                  String model, String status, Long startDate, Long endDate);

    /* ============================================= 统计分析 ============================================= */

    /**
     * 获取对话统计
     *
     * @return 对话统计 VO
     */
    ConversationStatisticsVO getConversationStatistics();

    /**
     * 获取热门问题
     *
     * @param limit 返回数量
     * @return 热门问题 VO 列表
     */
    List<HotQuestionVO> getHotQuestions(Integer limit);

    /**
     * 获取用户统计
     *
     * @param days 统计天数
     * @return 用户统计 VO
     */
    UserStatisticsVO getUserStatistics(Integer days);

    /**
     * 获取工具使用统计
     *
     * @return 工具使用统计 VO
     */
    ToolStatisticsVO getToolStatistics();

    /**
     * 获取 AI 调用指标
     *
     * @return AI 调用指标 VO
     */
    AiMetricsVO getAiMetrics();

    /**
     * 查看单次 AI 调用详情
     *
     * @param operationId AI 操作日志ID
     * @return 操作日志 VO（含完整字段）
     */
    OperationLogVO getOperationDetail(Long operationId);

    /**
     * 获取回答满意度统计
     *
     * @param startDate 起始日期（可选）
     * @param endDate   结束日期（可选）
     * @return 回答满意度统计 VO
     */
    FeedbackStatisticsVO getFeedbackStatistics(Long startDate, Long endDate);

    /* ============================================= 反馈管理 ============================================= */

    /**
     * B 端反馈明细分页查询
     * <p>
     * 通过 Feign 调用 chat-service 的 {@code FeedbackApi.listFeedbacks()}，
     * 单条记录同时返回反馈信息 + 对话问答摘要（question / answerSummary / model / sources）。
     *
     * @param pageNo        页码（默认 1）
     * @param pageSize      每页数量（默认 20）
     * @param feedbackType  反馈类型过滤（LIKE/DISLIKE，可选）
     * @param dislikeReason 点踩原因过滤（OUTDATED/IRRELEVANT/CODE_ERROR/OTHER，可选）
     * @param userId        用户ID过滤（可选）
     * @param startDate     起始日期（格式：20260712，可选）
     * @param endDate       结束日期（格式：20260712，可选）
     * @return 反馈明细分页结果
     */
    BasePageVO<FeedbackAdminVO> listFeedbacks(Integer pageNo, Integer pageSize,
                                               String feedbackType, String dislikeReason,
                                               Long userId, Long startDate, Long endDate);

    /* ============================================= 系统管理 ============================================= */

    /**
     * 获取系统健康状态
     * <p>
     * 通过 Feign 调用 chat-service 的 SystemApi.health()，检测 5 个核心组件
     * （MySQL/Redis/Nacos/Milvus/LLM）的连通性。
     * <p>
     * <b>降级策略</b>：单个组件检测失败不影响其他组件，overallStatus 为 UP/PARTIAL/DOWN。
     *
     * @return 系统健康状态 VO
     */
    SystemHealthVO getSystemHealth();
}
