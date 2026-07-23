package com.zmbdp.admin.service.ai.service.impl;

import com.zmbdp.chat.api.ai.domain.dto.AiConfigDTO;
import com.zmbdp.chat.api.ai.domain.dto.ToolConfigDTO;
import com.zmbdp.chat.api.ai.domain.dto.ToolTestReqDTO;
import com.zmbdp.chat.api.ai.domain.vo.AiConfigVO;
import com.zmbdp.chat.api.ai.domain.vo.ModelVO;
import com.zmbdp.chat.api.ai.domain.vo.ToolTestResultVO;
import com.zmbdp.chat.api.ai.domain.vo.ToolVO;
import com.zmbdp.chat.api.ai.feign.AiConfigApi;
import com.zmbdp.chat.api.ai.feign.ToolsApi;
import com.zmbdp.chat.api.chat.domain.dto.RetrieveReqDTO;
import com.zmbdp.chat.api.chat.domain.vo.DocumentVO;
import com.zmbdp.chat.api.feedback.domain.vo.FeedbackAdminVO;
import com.zmbdp.chat.api.feedback.feign.FeedbackApi;
import com.zmbdp.chat.api.knowledge.constant.KnowledgeSyncMQConstants;
import com.zmbdp.chat.api.knowledge.domain.dto.KnowledgeSourceReqDTO;
import com.zmbdp.chat.api.knowledge.domain.dto.KnowledgeSyncMessage;
import com.zmbdp.chat.api.knowledge.domain.dto.SyncReqDTO;
import com.zmbdp.chat.api.knowledge.domain.vo.KnowledgeDocumentVO;
import com.zmbdp.chat.api.knowledge.domain.vo.KnowledgeSourceVO;
import com.zmbdp.chat.api.knowledge.domain.vo.SyncResultVO;
import com.zmbdp.chat.api.knowledge.feign.KnowledgeApi;
import com.zmbdp.chat.api.operationlog.domain.vo.OperationLogVO;
import com.zmbdp.chat.api.operationlog.feign.OperationLogApi;
import com.zmbdp.chat.api.statistics.domain.vo.AiMetricsVO;
import com.zmbdp.chat.api.statistics.domain.vo.ConversationStatisticsVO;
import com.zmbdp.chat.api.statistics.domain.vo.FeedbackStatisticsVO;
import com.zmbdp.chat.api.statistics.domain.vo.HotQuestionVO;
import com.zmbdp.chat.api.statistics.domain.vo.ToolStatisticsVO;
import com.zmbdp.chat.api.statistics.domain.vo.UserStatisticsVO;
import com.zmbdp.chat.api.statistics.feign.StatisticsApi;
import com.zmbdp.chat.api.system.domain.vo.SystemHealthVO;
import com.zmbdp.chat.api.system.feign.SystemApi;
import com.zmbdp.common.domain.domain.Result;
import com.zmbdp.common.domain.domain.ResultCode;
import com.zmbdp.common.domain.domain.vo.BasePageVO;
import com.zmbdp.common.domain.exception.ServiceException;
import com.zmbdp.admin.service.ai.service.IAiAdminService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

/**
 * B端 AI 管理业务编排服务实现
 * <p>
 * 通过 Feign 调用 chat-service 的各管理接口，实现B端管理业务编排。
 * <p>
 * <b>异常处理</b>：Feign 调用失败时抛出 {@link ServiceException}，由全局异常处理器统一处理。
 * chat-service 返回的业务错误（如知识源不存在、配置非法等）透传其错误信息。
 *
 * @author 稚名不带撇
 */
@Slf4j
@Service
public class AiAdminServiceImpl implements IAiAdminService {

    /**
     * 知识库管理 Feign 接口
     */
    @Autowired
    private KnowledgeApi knowledgeApi;

    /**
     * AI 配置管理 Feign 接口
     */
    @Autowired
    private AiConfigApi aiConfigApi;

    /**
     * 工具管理 Feign 接口
     */
    @Autowired
    private ToolsApi toolsApi;

    /**
     * 操作日志 Feign 接口
     */
    @Autowired
    private OperationLogApi operationLogApi;

    /**
     * 统计分析 Feign 接口
     */
    @Autowired
    private StatisticsApi statisticsApi;

    /**
     * 系统管理 Feign 接口（用于系统健康检查）
     */
    @Autowired
    private SystemApi systemApi;

    /**
     * 反馈管理 Feign 接口（B 端反馈明细分页查询）
     */
    @Autowired
    private FeedbackApi feedbackApi;

    /**
     * RabbitMQ 消息发送模板（用于知识同步异步化）
     */
    @Autowired
    private RabbitTemplate rabbitTemplate;

    /* ============================================= 知识库管理 ============================================= */

    /**
     * 获取知识源列表（分页）
     */
    @Override
    public BasePageVO<KnowledgeSourceVO> listSources(Integer pageNo, Integer pageSize, String type) {
        Result<BasePageVO<KnowledgeSourceVO>> result = knowledgeApi.getSources(pageNo, pageSize, type);
        return unwrap(result, "获取知识源列表失败");
    }

    /**
     * 新增知识源
     */
    @Override
    public KnowledgeSourceVO createSource(KnowledgeSourceReqDTO dto) {
        Result<KnowledgeSourceVO> result = knowledgeApi.addSource(dto);
        return unwrap(result, "新增知识源失败");
    }

    /**
     * 更新知识源
     */
    @Override
    public void updateSource(Long id, KnowledgeSourceReqDTO dto) {
        Result<Void> result = knowledgeApi.updateSource(id, dto);
        unwrap(result, "更新知识源失败");
    }

    /**
     * 删除知识源（级联删除）
     */
    @Override
    public void deleteSource(Long id) {
        Result<Void> result = knowledgeApi.deleteSource(id);
        unwrap(result, "删除知识源失败");
    }

    /**
     * 触发知识同步（异步）
     * <p>
     * 通过 MQ 发送同步消息到 chat-service，立即返回"已提交"提示。
     * chat-service 消费端 {@code KnowledgeSyncConsumer} 异步执行同步流程。
     * <p>
     * <b>异步化原因</b>：知识同步涉及扫描文件、Embedding 调用、Milvus 写入，
     * 耗时可能数分钟，同步 HTTP 调用会导致前端超时返回 500000。
     */
    @Override
    public String syncKnowledge(SyncReqDTO dto) {
        String sourceType = dto != null ? dto.getSourceType() : null;
        boolean force = dto != null && Boolean.TRUE.equals(dto.getForce());
        log.info("触发知识同步（MQ 异步）：sourceType = {}, force = {}", sourceType, force);
        try {
            KnowledgeSyncMessage message = new KnowledgeSyncMessage();
            message.setSourceType(sourceType);
            message.setForce(force);
            rabbitTemplate.convertAndSend(
                    KnowledgeSyncMQConstants.EXCHANGE,
                    KnowledgeSyncMQConstants.ROUTING_KEY,
                    message);
            log.info("知识同步 MQ 消息已发送：sourceType = {}, force = {}", sourceType, force);
            return "知识同步任务已提交，请稍后通过文档列表查看同步结果";
        } catch (Exception e) {
            log.error("发送知识同步 MQ 消息失败：sourceType = {}, force = {}", sourceType, force, e);
            throw new ServiceException("触发知识同步失败：" + e.getMessage());
        }
    }

    /**
     * 获取文档列表（分页）
     */
    @Override
    public BasePageVO<KnowledgeDocumentVO> listDocuments(Integer pageNo, Integer pageSize, Long sourceId, String status) {
        Result<BasePageVO<KnowledgeDocumentVO>> result = knowledgeApi.getDocuments(pageNo, pageSize, sourceId, status);
        return unwrap(result, "获取文档列表失败");
    }

    /**
     * 获取文档详情
     */
    @Override
    public KnowledgeDocumentVO getDocument(Long id) {
        Result<KnowledgeDocumentVO> result = knowledgeApi.getDocument(id);
        return unwrap(result, "获取文档详情失败");
    }

    /**
     * 删除文档（级联删除向量）
     */
    @Override
    public void deleteDocument(Long id) {
        Result<Void> result = knowledgeApi.deleteDocument(id);
        unwrap(result, "删除文档失败");
    }

    /**
     * 知识源召回测试
     */
    @Override
    public List<DocumentVO> retrieveTest(RetrieveReqDTO request) {
        Result<List<DocumentVO>> result = knowledgeApi.retrieveTest(request);
        return unwrap(result, "召回测试失败");
    }

    /**
     * 上传文档到指定知识源
     */
    @Override
    public KnowledgeDocumentVO uploadDocument(Long knowledgeSourceId, MultipartFile file) {
        Result<KnowledgeDocumentVO> result = knowledgeApi.uploadDocument(knowledgeSourceId, file);
        return unwrap(result, "上传文档失败");
    }

    /* ============================================= AI 配置管理 ============================================= */

    /**
     * 获取 AI 配置
     */
    @Override
    public AiConfigVO getAiConfig() {
        Result<AiConfigVO> result = aiConfigApi.getConfig();
        return unwrap(result, "获取 AI 配置失败");
    }

    /**
     * 更新 AI 配置
     */
    @Override
    public void updateAiConfig(AiConfigDTO dto) {
        Result<Void> result = aiConfigApi.updateConfig(dto);
        unwrap(result, "更新 AI 配置失败");
    }

    /**
     * 获取可用模型列表
     */
    @Override
    public List<ModelVO> listModels() {
        Result<List<ModelVO>> result = aiConfigApi.getModels();
        return unwrap(result, "获取模型列表失败");
    }

    /**
     * 测试 AI 连接
     */
    @Override
    public void testAiConnection() {
        Result<Void> result = aiConfigApi.testConnection();
        unwrap(result, "AI 连接测试失败");
    }

    /* ============================================= 工具管理 ============================================= */

    /**
     * 获取工具列表
     */
    @Override
    public List<ToolVO> listTools() {
        Result<List<ToolVO>> result = toolsApi.getTools();
        return unwrap(result, "获取工具列表失败");
    }

    /**
     * 更新工具配置
     */
    @Override
    public void updateToolConfig(String name, ToolConfigDTO dto) {
        Result<Void> result = toolsApi.updateToolConfig(name, dto);
        unwrap(result, "更新工具配置失败");
    }

    /**
     * 测试工具调用
     */
    @Override
    public ToolTestResultVO testTool(String name, ToolTestReqDTO dto) {
        Result<ToolTestResultVO> result = toolsApi.testTool(name, dto);
        return unwrap(result, "测试工具调用失败");
    }

    /* ============================================= 操作日志 ============================================= */

    /**
     * 获取 AI 调用链路日志列表（分页）
     */
    @Override
    public BasePageVO<OperationLogVO> listOperationLogs(Integer pageNo, Integer pageSize, String operationType,
                                                         String model, String status, Long startDate, Long endDate) {
        Result<BasePageVO<OperationLogVO>> result = operationLogApi.getLogs(pageNo, pageSize, operationType,
                model, status, startDate, endDate);
        return unwrap(result, "获取操作日志列表失败");
    }

    /* ============================================= 统计分析 ============================================= */

    /**
     * 获取对话统计
     */
    @Override
    public ConversationStatisticsVO getConversationStatistics() {
        Result<ConversationStatisticsVO> result = statisticsApi.getConversationStatistics();
        return unwrap(result, "获取对话统计失败");
    }

    /**
     * 获取热门问题
     */
    @Override
    public List<HotQuestionVO> getHotQuestions(Integer limit) {
        Result<List<HotQuestionVO>> result = statisticsApi.getHotQuestions(limit);
        return unwrap(result, "获取热门问题失败");
    }

    /**
     * 获取用户统计
     */
    @Override
    public UserStatisticsVO getUserStatistics(Integer days) {
        Result<UserStatisticsVO> result = statisticsApi.getUserStatistics(days);
        return unwrap(result, "获取用户统计失败");
    }

    /**
     * 获取工具使用统计
     */
    @Override
    public ToolStatisticsVO getToolStatistics() {
        Result<ToolStatisticsVO> result = statisticsApi.getToolStatistics();
        return unwrap(result, "获取工具使用统计失败");
    }

    /**
     * 获取 AI 调用指标
     */
    @Override
    public AiMetricsVO getAiMetrics() {
        Result<AiMetricsVO> result = statisticsApi.getAiMetrics();
        return unwrap(result, "获取 AI 调用指标失败");
    }

    /**
     * 查看单次 AI 调用详情
     */
    @Override
    public OperationLogVO getOperationDetail(Long operationId) {
        Result<OperationLogVO> result = statisticsApi.getOperationDetail(operationId);
        return unwrap(result, "获取 AI 调用详情失败");
    }

    /**
     * 获取回答满意度统计
     */
    @Override
    public FeedbackStatisticsVO getFeedbackStatistics(Long startDate, Long endDate) {
        Result<FeedbackStatisticsVO> result = statisticsApi.getFeedbackStatistics(startDate, endDate);
        return unwrap(result, "获取回答满意度统计失败");
    }

    /* ============================================= 反馈管理 ============================================= */

    /**
     * B 端反馈明细分页查询
     * <p>
     * 通过 Feign 调用 chat-service 的 {@code FeedbackApi.listFeedbacks()}，
     * 单条记录同时返回反馈信息 + 对话问答摘要（question / answerSummary / model / sources）。
     */
    @Override
    public BasePageVO<FeedbackAdminVO> listFeedbacks(Integer pageNo, Integer pageSize,
                                                      String feedbackType, String dislikeReason,
                                                      Long userId, Long startDate, Long endDate) {
        Result<BasePageVO<FeedbackAdminVO>> result = feedbackApi.listFeedbacks(
                pageNo, pageSize, feedbackType, dislikeReason, userId, startDate, endDate);
        return unwrap(result, "获取反馈明细列表失败");
    }

    /* ============================================= 系统管理 ============================================= */

    /**
     * 获取系统健康状态
     * <p>
     * 通过 Feign 调用 chat-service 的 SystemApi.health()，检测 5 个核心组件
     * （MySQL/Redis/Nacos/Milvus/LLM）的连通性。
     */
    @Override
    public SystemHealthVO getSystemHealth() {
        Result<SystemHealthVO> result = systemApi.health();
        return unwrap(result, "获取系统健康状态失败");
    }

    /* ============================================= 私有方法 ============================================= */

    /**
     * 解包 Feign 返回结果
     * <p>
     * 统一处理 Feign 调用的返回值：
     * <ul>
     *     <li>code 为 SUCCESS（200000）时返回 data</li>
     *     <li>其他情况抛出 {@link ServiceException}，透传 chat-service 的错误信息</li>
     * </ul>
     *
     * @param result     Feign 返回的 Result 对象
     * @param errorDesc  操作描述（用于日志和异常信息）
     * @param <T>        数据类型
     * @return 解包后的数据
     * @throws ServiceException 当 Feign 调用失败或业务错误时抛出
     */
    private <T> T unwrap(Result<T> result, String errorDesc) {
        if (result == null) {
            log.warn("{}：Feign 返回 null", errorDesc);
            throw new ServiceException(errorDesc);
        }
        if (result.getCode() != ResultCode.SUCCESS.getCode()) {
            log.warn("{}：code = {}, msg = {}", errorDesc, result.getCode(), result.getErrMsg());
            // 透传 chat-service 的业务错误信息（如"知识源不存在"、"参数非法"等）
            String errMsg = result.getErrMsg() != null ? result.getErrMsg() : errorDesc;
            throw new ServiceException(errMsg);
        }
        return result.getData();
    }
}