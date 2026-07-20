package com.zmbdp.chat.service.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.core.type.TypeReference;
import com.zmbdp.admin.api.config.domain.dto.ArgumentDTO;
import com.zmbdp.admin.api.config.domain.dto.ArgumentEditReqDTO;
import com.zmbdp.admin.api.config.feign.ArgumentServiceApi;
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
import com.zmbdp.chat.service.constant.AiCacheConstants;
import com.zmbdp.chat.service.domain.entity.SysAiOperationLog;
import com.zmbdp.chat.service.mapper.SysAiOperationLogMapper;
import com.zmbdp.chat.service.mq.CacheInvalidateProducer;
import com.zmbdp.chat.service.service.IAdminService;
import com.zmbdp.chat.service.service.IKnowledgeService;
import com.zmbdp.chat.service.service.IModelService;
import com.zmbdp.chat.service.service.ToolRegistryService;
import com.zmbdp.common.core.utils.DesensitizeUtil;
import com.zmbdp.common.core.utils.JsonUtil;
import com.zmbdp.common.domain.domain.Result;
import com.zmbdp.common.domain.domain.ResultCode;
import com.zmbdp.common.domain.domain.dto.BasePageDTO;
import com.zmbdp.common.domain.domain.vo.BasePageVO;
import com.zmbdp.common.domain.exception.ServiceException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.context.config.annotation.RefreshScope;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

import java.lang.reflect.Method;
import java.time.ZoneOffset;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * B端管理服务实现类（AI 业务相关）
 * <p>
 * 由 chat-service 提供给 admin-service 通过 Feign 调用，包含知识源管理、AI 配置管理、
 * 工具管理、操作日志查询等功能。
 * <p>
 * <b>职责边界</b>：
 * <ul>
 *     <li>知识源管理：委托给 {@link IKnowledgeService}，避免重复实现</li>
 *     <li>AI 配置管理：运行时参数走 sys_argument 表（Feign 调用），基础设施配置走 Nacos（{@code @Value} 读取）</li>
 *     <li>工具管理：启用状态走 sys_argument 表，使用统计走 sys_ai_operation_log 表</li>
 *     <li>操作日志：查询 sys_ai_operation_log 表</li>
 * </ul>
 * <p>
 * <b>参数校验</b>：DTO 上使用 Jakarta Validation 注解，Controller 层通过 {@code @Valid} 触发校验，
 * Service 层不重复手写 if 校验。
 *
 * @author 稚名不带撇
 */
@Slf4j
@Service
@RefreshScope
public class AdminServiceImpl implements IAdminService {

    /*=============================================    配置项常量    =============================================*/

    /**
     * sys_argument 表中工具启用状态的 configKey 前缀
     */
    private static final String TOOL_CONFIG_KEY_PREFIX = "ai.tool.";

    /**
     * sys_argument 表中工具启用状态的 configKey 后缀
     */
    private static final String TOOL_CONFIG_KEY_SUFFIX = ".enabled";

    /**
     * sys_argument 表中温度参数的 configKey
     */
    private static final String CONFIG_KEY_TEMPERATURE = "ai.temperature";

    /**
     * sys_argument 表中最大 Token 数的 configKey
     */
    private static final String CONFIG_KEY_MAX_TOKENS = "ai.max_tokens";

    /**
     * sys_argument 表中 RAG 检索数量的 configKey
     */
    private static final String CONFIG_KEY_TOP_K = "ai.top_k";

    /**
     * sys_argument 表中是否启用 RAG 的 configKey
     */
    private static final String CONFIG_KEY_ENABLE_RAG = "ai.enable_rag";

    /**
     * sys_argument 表中是否启用工具调用的 configKey
     */
    private static final String CONFIG_KEY_ENABLE_TOOLS = "ai.enable_tools";

    /**
     * sys_argument 表中所有 AI 运行时参数的 configKey 列表
     */
    private static final List<String> AI_RUNTIME_CONFIG_KEYS = Arrays.asList(
            CONFIG_KEY_TEMPERATURE,
            CONFIG_KEY_MAX_TOKENS,
            CONFIG_KEY_TOP_K,
            CONFIG_KEY_ENABLE_RAG,
            CONFIG_KEY_ENABLE_TOOLS
    );

    /**
     * API Key 脱敏保留前缀位数
     */
    private static final int API_KEY_DESENSITIZE_PREFIX = 4;

    /**
     * API Key 脱敏保留后缀位数
     */
    private static final int API_KEY_DESENSITIZE_SUFFIX = 4;

    /**
     * 视觉模型类型（对应 spring.ai.models 中 type=TEXT_AND_IMAGE 的模型）
     */
    private static final String MODEL_TYPE_VISION = "TEXT_AND_IMAGE";

    /**
     * 默认分页大小（参数兜底用）
     */
    private static final int DEFAULT_PAGE_SIZE = 20;

    /**
     * 工具调用默认超时时间（秒，与 05-Agent工具设计.md 5.4 节一致）
     */
    private static final int DEFAULT_TOOL_TIMEOUT_SECONDS = 30;

    /*=============================================    Nacos 基础设施配置    =============================================*/

    /**
     * API Key（来自 Nacos 配置 spring.ai.dashscope.api-key）
     * <p>
     * 属基础设施配置，不通过 sys_argument 表管理，仅在 getAiConfig 时脱敏返回。
     */
    @Value("${spring.ai.dashscope.api-key:}")
    private String apiKey;

    /**
     * Embedding 模型名称（来自 Nacos 配置 spring.ai.dashscope.embedding.model）
     */
    @Value("${spring.ai.dashscope.embedding.model:}")
    private String embeddingModel;

    /**
     * 工具调用超时时间（秒，来自 Nacos 配置 {@code scaffold.tool.timeout}，默认 30 秒）
     * <p>
     * 用于 {@link #testTool} 测试工具调用时的超时控制。
     */
    @Value("${scaffold.tool.timeout:30}")
    private Integer toolTimeoutSeconds;

    /*=============================================    依赖注入    =============================================*/

    /**
     * 知识库管理服务（知识源管理委托给它）
     */
    @Autowired
    private IKnowledgeService knowledgeService;

    /**
     * 模型管理服务（模型列表查询、ChatClient 获取）
     */
    @Autowired
    private IModelService modelService;

    /**
     * AI 操作日志 Mapper（查询 sys_ai_operation_log 表）
     */
    @Autowired
    private SysAiOperationLogMapper sysAiOperationLogMapper;

    /**
     * 参数服务远程调用 Api（Feign，查/更新 sys_argument 表）
     */
    @Autowired
    private ArgumentServiceApi argumentServiceApi;

    /**
     * Agent 工具注册服务（工具启用状态管理）
     */
    @Autowired
    private ToolRegistryService toolRegistryService;

    /**
     * 缓存失效广播生产者（AI_CONFIG 缓存空间失效）
     */
    @Autowired
    private CacheInvalidateProducer cacheInvalidateProducer;

    /*=============================================    知识源管理（委托给 IKnowledgeService）    =============================================*/

    /**
     * 分页查询知识源列表
     *
     * @param pageNo   页码
     * @param pageSize 每页数量
     * @param type     类型过滤（doc/javadoc/config/code，可选）
     * @return 知识源分页结果
     */
    @Override
    public BasePageVO<KnowledgeSourceVO> listKnowledgeSources(Integer pageNo, Integer pageSize, String type) {
        return knowledgeService.listSources(pageNo, pageSize, type);
    }

    /**
     * 获取知识源详情
     *
     * @param id 知识源ID
     * @return 知识源 VO
     */
    @Override
    public KnowledgeSourceVO getKnowledgeSource(Long id) {
        return knowledgeService.getSource(id);
    }

    /**
     * 新增知识源
     *
     * @param dto 知识源请求 DTO
     * @return 新建后的知识源 VO（含生成的 ID）
     */
    @Override
    public KnowledgeSourceVO createKnowledgeSource(KnowledgeSourceReqDTO dto) {
        return knowledgeService.createSource(dto);
    }

    /**
     * 更新知识源
     *
     * @param id  知识源ID
     * @param dto 知识源请求 DTO
     */
    @Override
    public void updateKnowledgeSource(Long id, KnowledgeSourceReqDTO dto) {
        knowledgeService.updateSource(id, dto);
    }

    /**
     * 删除知识源（级联删除）
     *
     * @param id 知识源ID
     */
    @Override
    public void deleteKnowledgeSource(Long id) {
        knowledgeService.deleteSource(id);
    }

    /*=============================================    AI 配置管理    =============================================*/

    /**
     * 获取 AI 配置
     * <p>
     * 执行流程：
     * <ol>
     *     <li>通过 Feign 调用 ArgumentServiceApi 查询 sys_argument 表中 ai.* 运行时可调配置项</li>
     *     <li>从 Nacos 配置读取基础设施配置项（api-key、embedding_model）</li>
     *     <li>从 IModelService 读取模型列表，识别默认文本模型和默认视觉模型</li>
     *     <li>构建 AiConfigVO，api-key 脱敏显示（仅前后4位）</li>
     * </ol>
     *
     * @return AI 配置 VO
     */
    @Override
    public AiConfigVO getAiConfig() {
        // 1. 查询 sys_argument 表的运行时参数
        Map<String, String> runtimeConfigMap = queryRuntimeConfigMap();
        // 2. 查询模型列表，识别默认文本模型和默认视觉模型
        List<ModelVO> models = modelService.listModels();
        String defaultModelName = null;
        String defaultVisionModelName = null;
        if (!CollectionUtils.isEmpty(models)) {
            for (ModelVO model : models) {
                if (Boolean.TRUE.equals(model.getEnabled())) {
                    if (MODEL_TYPE_VISION.equals(model.getType())) {
                        // 视觉模型取第一个启用的 TEXT_AND_IMAGE 类型
                        if (defaultVisionModelName == null) {
                            defaultVisionModelName = model.getName();
                        }
                    } else {
                        // 文本模型取第一个启用的非视觉模型作为默认
                        if (defaultModelName == null) {
                            defaultModelName = model.getName();
                        }
                    }
                }
            }
        }
        // 3. 构建 AiConfigVO
        AiConfigVO vo = new AiConfigVO();
        vo.setDefaultModel(defaultModelName);
        vo.setDefaultVisionModel(defaultVisionModelName);
        vo.setTemperature(parseDouble(runtimeConfigMap.get(CONFIG_KEY_TEMPERATURE)));
        vo.setMaxTokens(parseInt(runtimeConfigMap.get(CONFIG_KEY_MAX_TOKENS)));
        vo.setTopK(parseInt(runtimeConfigMap.get(CONFIG_KEY_TOP_K)));
        vo.setEnableRag(parseBoolean(runtimeConfigMap.get(CONFIG_KEY_ENABLE_RAG)));
        vo.setEnableTools(parseBoolean(runtimeConfigMap.get(CONFIG_KEY_ENABLE_TOOLS)));
        vo.setEmbeddingModel(StringUtils.hasText(embeddingModel) ? embeddingModel : null);
        // 4. api-key 脱敏显示（仅前后4位）
        if (StringUtils.hasText(apiKey)) {
            vo.setApiKey(DesensitizeUtil.desensitize(apiKey, API_KEY_DESENSITIZE_PREFIX, API_KEY_DESENSITIZE_SUFFIX));
        }
        return vo;
    }

    /**
     * 更新 AI 配置
     * <p>
     * 执行流程：
     * <ol>
     *     <li>参数校验由 DTO 上的 Jakarta Validation 注解完成（Controller 层 @Valid 触发）</li>
     *     <li>遍历 AiConfigDTO 的非空字段，通过 Feign 调用 ArgumentServiceApi 更新 sys_argument 表</li>
     * </ol>
     * <p>
     * <b>注意</b>：sys_argument 表只存运行时可调参数（ai.temperature/ai.max_tokens/ai.top_k/ai.enable_rag/ai.enable_tools），
     * api-key、模型名称等基础设施配置统一在 Nacos 管理，不通过此接口修改。
     *
     * @param dto AI 配置更新请求
     */
    @Override
    public void updateAiConfig(AiConfigDTO dto) {
        // 遍历 DTO 的非空字段，更新 sys_argument 表
        if (dto.getTemperature() != null) {
            updateArgumentValue(CONFIG_KEY_TEMPERATURE, String.valueOf(dto.getTemperature()));
        }
        if (dto.getMaxTokens() != null) {
            updateArgumentValue(CONFIG_KEY_MAX_TOKENS, String.valueOf(dto.getMaxTokens()));
        }
        if (dto.getTopK() != null) {
            updateArgumentValue(CONFIG_KEY_TOP_K, String.valueOf(dto.getTopK()));
        }
        if (dto.getEnableRag() != null) {
            updateArgumentValue(CONFIG_KEY_ENABLE_RAG, String.valueOf(dto.getEnableRag()));
        }
        if (dto.getEnableTools() != null) {
            updateArgumentValue(CONFIG_KEY_ENABLE_TOOLS, String.valueOf(dto.getEnableTools()));
        }
        log.info("更新 AI 配置完成：temperature = {}, maxTokens = {}, topK = {}, enableRag = {}, enableTools = {}",
                dto.getTemperature(), dto.getMaxTokens(), dto.getTopK(), dto.getEnableRag(), dto.getEnableTools());

        // 失效整个 AI_CONFIG 缓存空间（删 L2 Redis 通配符 key + 广播删所有实例 L1 Caffeine）
        cacheInvalidateProducer.invalidateAll(AiCacheConstants.AI_CONFIG, AiCacheConstants.AI_CONFIG_REDIS_PREFIX + "*");
    }

    /**
     * 获取可用模型列表
     *
     * @return 模型 VO 列表
     */
    @Override
    public List<ModelVO> listModels() {
        return modelService.listModels();
    }

    /**
     * 测试 AI 连接
     * <p>
     * 调用指定模型的 ChatClient 发送一条测试消息，验证模型连接是否正常。
     *
     * @param model   模型名称（为空时使用默认模型）
     * @param message 测试消息（为空时使用默认测试消息）
     * @return true=连接成功，false=连接失败
     */
    @Override
    public boolean testAiConnection(String model, String message) {
        try {
            ChatClient chatClient = StringUtils.hasText(model)
                    ? modelService.getChatClient(model)
                    : modelService.getDefaultChatClient();
            String testMessage = StringUtils.hasText(message) ? message : "你好，请回复'连接正常'";
            ChatResponse response = chatClient.prompt().user(testMessage).call().chatResponse();
            boolean success = response != null && response.getResult() != null
                    && response.getResult().getOutput() != null
                    && StringUtils.hasText(response.getResult().getOutput().getText());
            log.info("AI 连接测试：model = {}, success = {}", model, success);
            return success;
        } catch (Exception e) {
            log.warn("AI 连接测试失败：model = {}, error = {}", model, e.getMessage());
            return false;
        }
    }

    /*=============================================    工具管理    =============================================*/

    /**
     * 获取工具列表
     * <p>
     * 执行流程：
     * <ol>
     *     <li>通过 ToolRegistryService 获取全量工具名列表（含禁用状态）</li>
     *     <li>通过反射读取工具 Bean 的 @Tool 注解 description 属性</li>
     *     <li>从 sys_ai_operation_log 表查询各工具的使用统计（解析 tool_calls JSON 字段，统计 lastUsedTime/usedCount）</li>
     *     <li>构建 ToolVO 列表返回</li>
     * </ol>
     * <p>
     * <b>config 字段说明</b>：工具参数配置（如 knowledge.allowed-paths、knowledge.max-file-size 等）
     * 属基础设施配置，统一在 Nacos 管理，B 端通过 Nacos 配置页面修改，故 ToolVO.config 返回 null。
     *
     * @return 工具 VO 列表
     */
    @Override
    public List<ToolVO> listTools() {
        // 1. 获取全量工具名列表（含禁用状态）
        List<String> allToolNames = toolRegistryService.getAllToolNames();
        if (CollectionUtils.isEmpty(allToolNames)) {
            return new ArrayList<>();
        }
        // 2. 查询工具使用统计（从 sys_ai_operation_log 解析 tool_calls JSON 字段）
        Map<String, ToolUsageStat> usageStats = queryToolUsageStats();
        // 3. 构建 ToolVO 列表
        List<ToolVO> list = new ArrayList<>(allToolNames.size());
        for (String toolName : allToolNames) {
            ToolVO vo = new ToolVO();
            vo.setName(toolName);
            vo.setDescription(getToolDescription(toolName));
            vo.setEnabled(toolRegistryService.isToolEnabled(toolName));
            // config 字段：工具参数配置由 Nacos 统一管理，不通过 sys_argument 表，这里返回 null
            vo.setConfig(null);
            ToolUsageStat stat = usageStats.get(toolName);
            if (stat != null) {
                vo.setLastUsedTime(stat.lastUsedTime);
                vo.setUsedCount(stat.usedCount);
            } else {
                vo.setLastUsedTime(null);
                vo.setUsedCount(0);
            }
            list.add(vo);
        }
        return list;
    }

    /**
     * 更新工具配置
     * <p>
     * 执行流程：
     * <ol>
     *     <li>通过 Feign 调用 ArgumentServiceApi 更新 sys_argument 表的 ai.tool.{name}.enabled 配置项</li>
     *     <li>触发 ToolRegistryService 刷新工具启用状态</li>
     * </ol>
     * <p>
     * <b>工具参数配置（dto.config）说明</b>：工具参数配置（如 knowledge.allowed-paths、knowledge.max-file-size 等）
     * 属基础设施配置，统一在 Nacos 管理，
     * 不通过 sys_argument 表修改，故本方法仅更新 enabled 状态，dto.config 字段被忽略。
     * 管理员如需修改工具参数，请通过 Nacos 配置页面修改 {@code knowledge.*} 配置项。
     *
     * @param name 工具名称
     * @param dto  工具配置更新请求
     */
    @Override
    public void updateToolConfig(String name, ToolConfigDTO dto) {
        if (!StringUtils.hasText(name)) {
            throw new ServiceException("工具名称不能为空", ResultCode.INVALID_PARA.getCode());
        }
        // 1. 更新 sys_argument 表的工具启用状态
        if (dto.getEnabled() != null) {
            String configKey = TOOL_CONFIG_KEY_PREFIX + name + TOOL_CONFIG_KEY_SUFFIX;
            updateArgumentValue(configKey, String.valueOf(dto.getEnabled()));
            // 2. 触发 ToolRegistryService 刷新工具启用状态
            toolRegistryService.refreshTool(name, dto.getEnabled());
        }
        // 工具参数配置（dto.config）由 Nacos 统一管理，不通过 sys_argument 表修改，这里忽略
        log.info("更新工具配置完成：name = {}, enabled = {}", name, dto.getEnabled());
    }

    /**
     * 测试工具调用
     * <p>
     * 执行流程：
     * <ol>
     *     <li>通过 ToolRegistryService 获取指定工具的 Bean 实例</li>
     *     <li>通过反射找到 Bean 上标注 {@link Tool} 注解的方法</li>
     *     <li>从 dto.params 中按方法参数名取值，并根据参数类型转换</li>
     *     <li>使用 CompletableFuture + get(timeout, unit) 实现超时控制（默认 30 秒，可配置 {@code scaffold.tool.timeout}）</li>
     *     <li>返回执行结果（含成功状态、结果内容、耗时、错误信息）</li>
     * </ol>
     *
     * @param name 工具名称（@Tool 注解方法名，如 readFile）
     * @param dto  测试请求（params 为参数名 → 参数值的映射）
     * @return 测试结果
     */
    @Override
    public ToolTestResultVO testTool(String name, ToolTestReqDTO dto) {
        ToolTestResultVO result = new ToolTestResultVO();
        // 1. 获取工具 Bean 实例
        Object toolBean = toolRegistryService.getToolBean(name);
        if (toolBean == null) {
            result.setSuccess(false);
            result.setErrorMsg("工具不存在或未注册：name=" + name);
            result.setDuration(0L);
            return result;
        }
        // 2. 通过反射找到 @Tool 注解方法
        Method toolMethod = findToolMethod(toolBean.getClass());
        if (toolMethod == null) {
            result.setSuccess(false);
            result.setErrorMsg("工具方法未找到：在 " + toolBean.getClass().getSimpleName() + " 中未找到 @Tool 注解方法");
            result.setDuration(0L);
            return result;
        }
        // 3. 构建方法参数（从 dto.params 中按参数名取值并转换类型）
        Map<String, Object> params = (dto != null && dto.getParams() != null) ? dto.getParams() : new HashMap<>();
        Object[] args;
        try {
            args = buildMethodArgs(toolMethod, params);
        } catch (ServiceException e) {
            result.setSuccess(false);
            result.setErrorMsg(e.getMessage());
            result.setDuration(0L);
            return result;
        }
        // 4. 执行工具调用（带超时控制）
        long startTime = System.currentTimeMillis();
        try {
            String toolResult = invokeToolWithTimeout(toolBean, toolMethod, args);
            long duration = System.currentTimeMillis() - startTime;
            result.setSuccess(true);
            result.setResult(toolResult);
            result.setDuration(duration);
            result.setErrorMsg(null);
            log.info("工具测试调用成功：name = {}, duration = {}ms", name, duration);
        } catch (TimeoutException e) {
            long duration = System.currentTimeMillis() - startTime;
            result.setSuccess(false);
            result.setErrorMsg("工具执行超时（超过 " + toolTimeoutSeconds + " 秒）");
            result.setDuration(duration);
            log.warn("工具测试调用超时：name = {}, timeout = {}s", name, toolTimeoutSeconds);
        } catch (Exception e) {
            long duration = System.currentTimeMillis() - startTime;
            result.setSuccess(false);
            result.setErrorMsg("工具执行失败：" + e.getMessage());
            result.setDuration(duration);
            log.warn("工具测试调用失败：name = {}, error = {}", name, e.getMessage(), e);
        }
        return result;
    }

    /*=============================================    操作日志查询    =============================================*/

    /**
     * 分页查询 AI 操作日志
     *
     * @param pageNo        页码
     * @param pageSize      每页数量
     * @param operationType AI 操作类型过滤（CHAT/RETRIEVE/EMBEDDING/RERANK，可选）
     * @return 操作日志分页结果
     */
    @Override
    public BasePageVO<SysAiOperationLog> listOperationLogs(Integer pageNo, Integer pageSize, String operationType) {
        // 参数兜底
        if (pageNo == null || pageNo < 1) {
            pageNo = 1;
        }
        if (pageSize == null || pageSize < 1) {
            pageSize = DEFAULT_PAGE_SIZE;
        }
        // 构建查询条件
        LambdaQueryWrapper<SysAiOperationLog> queryWrapper = new LambdaQueryWrapper<>();
        if (StringUtils.hasText(operationType)) {
            queryWrapper.eq(SysAiOperationLog::getOperationType, operationType);
        }
        queryWrapper.orderByDesc(SysAiOperationLog::getCreateTime);
        // 分页查询
        Page<SysAiOperationLog> page = new Page<>(pageNo, pageSize);
        IPage<SysAiOperationLog> result = sysAiOperationLogMapper.selectPage(page, queryWrapper);
        // 转换为 VO
        BasePageVO<SysAiOperationLog> vo = new BasePageVO<>();
        vo.setTotals(result.getTotal() > 0 ? (int) result.getTotal() : 0);
        vo.setTotalPages(BasePageDTO.calculateTotalPages(result.getTotal(), pageSize));
        vo.setList(result.getRecords() != null ? result.getRecords() : new ArrayList<>());
        return vo;
    }

    /**
     * 查询单次 AI 调用详情
     *
     * @param operationId AI 操作日志ID
     * @return 操作日志实体（含完整 Prompt、响应、工具调用链路、Token 消耗）
     */
    @Override
    public SysAiOperationLog getOperationLogDetail(Long operationId) {
        SysAiOperationLog log = sysAiOperationLogMapper.selectById(operationId);
        if (log == null) {
            throw new ServiceException(ResultCode.AI_OPERATION_LOG_NOT_FOUND);
        }
        return log;
    }

    /*=============================================    文档列表查询（委托给 IKnowledgeService）    =============================================*/

    /**
     * 分页查询文档列表
     *
     * @param pageNo   页码
     * @param pageSize 每页数量
     * @param sourceId 知识源ID过滤（可选）
     * @param status   状态过滤（ACTIVE/DELETED/SYNCING，可选）
     * @return 文档分页结果
     */
    @Override
    public BasePageVO<KnowledgeDocumentVO> listDocuments(Integer pageNo, Integer pageSize, Long sourceId, String status) {
        return knowledgeService.listDocuments(pageNo, pageSize, sourceId, status);
    }

    /*=============================================    私有方法    =============================================*/

    /**
     * 查询 sys_argument 表中所有 AI 运行时参数，返回 configKey → value 的 Map
     * <p>
     * 通过 Feign 调用 ArgumentServiceApi.getByConfigKeys 批量查询，避免多次网络请求。
     *
     * @return configKey → value 的 Map；配置项不存在则不包含在 Map 中
     */
    private Map<String, String> queryRuntimeConfigMap() {
        Map<String, String> result = new HashMap<>();
        try {
            List<ArgumentDTO> arguments = argumentServiceApi.getByConfigKeys(AI_RUNTIME_CONFIG_KEYS);
            if (!CollectionUtils.isEmpty(arguments)) {
                for (ArgumentDTO arg : arguments) {
                    if (arg != null && StringUtils.hasText(arg.getConfigKey()) && arg.getValue() != null) {
                        result.put(arg.getConfigKey(), arg.getValue());
                    }
                }
            }
        } catch (Exception e) {
            log.warn("查询 AI 运行时参数失败，使用默认值：{}", e.getMessage());
        }
        return result;
    }

    /**
     * 更新 sys_argument 表中指定 configKey 的 value
     * <p>
     * 先查询现有配置项获取 name（保持 name 不变，避免触发 "参数名称已存在" 校验），
     * 然后调用 Feign 接口更新 value。
     *
     * @param configKey 参数业务主键
     * @param value     新的参数值
     */
    private void updateArgumentValue(String configKey, String value) {
        try {
            // 先查询现有配置项，获取原 name（保持 name 不变）
            ArgumentDTO existing = argumentServiceApi.getByConfigKey(configKey);
            if (existing == null || !StringUtils.hasText(existing.getConfigKey())) {
                log.warn("配置项不存在，跳过更新：{}", configKey);
                return;
            }
            // 构建更新请求
            ArgumentEditReqDTO editReq = new ArgumentEditReqDTO();
            editReq.setConfigKey(configKey);
            editReq.setName(existing.getName());
            editReq.setValue(value);
            if (StringUtils.hasText(existing.getRemark())) {
                editReq.setRemark(existing.getRemark());
            }
            // 调用 Feign 更新
            Result<Long> result = argumentServiceApi.editArgument(editReq);
            if (result == null || result.getCode() != ResultCode.SUCCESS.getCode()) {
                log.warn("更新配置项失败：configKey = {}, result = {}", configKey, result);
            }
        } catch (Exception e) {
            log.warn("更新配置项异常：configKey = {}, value = {}, error = {}", configKey, value, e.getMessage());
        }
    }

    /**
     * 安全解析 Integer 字符串
     *
     * @param value 字符串值
     * @return Integer 值；解析失败返回 null
     */
    private Integer parseInt(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * 安全解析 Double 字符串
     *
     * @param value 字符串值
     * @return Double 值；解析失败返回 null
     */
    private Double parseDouble(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        try {
            return Double.parseDouble(value);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * 安全解析 Boolean 字符串
     *
     * @param value 字符串值
     * @return Boolean 值；解析失败返回 null
     */
    private Boolean parseBoolean(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        return Boolean.parseBoolean(value);
    }

    /*=============================================    工具管理辅助方法    =============================================*/

    /**
     * 查询工具使用统计
     * <p>
     * 从 sys_ai_operation_log 表查询所有 tool_calls 非空的记录，
     * 解析 tool_calls JSON 字段（格式：{@code [{"name":"readFile","success":true,"duration":45,"summary":"..."}]}），
     * 按 name 分组统计调用次数（usedCount）和最后使用时间（lastUsedTime）。
     * <p>
     * <b>性能说明</b>：B 端管理接口调用频率低，全量查询可接受。tool_calls 字段格式与
     * {@link ToolRegistryService} 维护的工具名（@Tool 方法名）一致。
     *
     * @return 工具名 → 使用统计的 Map
     */
    private Map<String, ToolUsageStat> queryToolUsageStats() {
        Map<String, ToolUsageStat> statsMap = new HashMap<>();
        try {
            LambdaQueryWrapper<SysAiOperationLog> wrapper = new LambdaQueryWrapper<>();
            wrapper.isNotNull(SysAiOperationLog::getToolCalls)
                    .ne(SysAiOperationLog::getToolCalls, "")
                    .ne(SysAiOperationLog::getToolCalls, "[]")
                    .select(SysAiOperationLog::getToolCalls, SysAiOperationLog::getCreateTime);
            List<SysAiOperationLog> records = sysAiOperationLogMapper.selectList(wrapper);
            if (CollectionUtils.isEmpty(records)) {
                return statsMap;
            }
            for (SysAiOperationLog record : records) {
                List<Map<String, Object>> toolCalls;
                try {
                    toolCalls = JsonUtil.jsonToClass(record.getToolCalls(),
                            new TypeReference<List<Map<String, Object>>>() {
                            });
                } catch (Exception e) {
                    log.debug("解析 tool_calls JSON 失败：operationId = {}", record.getId());
                    continue;
                }
                if (CollectionUtils.isEmpty(toolCalls)) {
                    continue;
                }
                long recordTime = record.getCreateTime() != null
                        ? record.getCreateTime().toInstant(ZoneOffset.UTC).toEpochMilli() : 0L;
                for (Map<String, Object> call : toolCalls) {
                    String toolName = (String) call.get("name");
                    if (!StringUtils.hasText(toolName)) {
                        continue;
                    }
                    ToolUsageStat stat = statsMap.computeIfAbsent(toolName, k -> new ToolUsageStat());
                    stat.usedCount++;
                    if (recordTime > stat.lastUsedTime) {
                        stat.lastUsedTime = recordTime;
                    }
                }
            }
        } catch (Exception e) {
            log.warn("查询工具使用统计失败：{}", e.getMessage());
        }
        return statsMap;
    }

    /**
     * 通过反射读取工具 Bean 的 @Tool 注解 description 属性
     *
     * @param toolName 工具名（@Tool 方法名）
     * @return 工具描述；工具不存在或未标注 @Tool 返回 null
     */
    private String getToolDescription(String toolName) {
        Object toolBean = toolRegistryService.getToolBean(toolName);
        if (toolBean == null) {
            return null;
        }
        Method toolMethod = findToolMethod(toolBean.getClass());
        if (toolMethod == null) {
            return null;
        }
        Tool toolAnnotation = toolMethod.getAnnotation(Tool.class);
        return toolAnnotation != null ? toolAnnotation.description() : null;
    }

    /**
     * 找到类中标注 {@link Tool} 注解的方法
     * <p>
     * 每个工具类只有一个 @Tool 注解方法，返回第一个匹配的方法。
     *
     * @param toolClass 工具类
     * @return @Tool 注解方法；不存在返回 null
     */
    private Method findToolMethod(Class<?> toolClass) {
        for (Method method : toolClass.getMethods()) {
            if (method.isAnnotationPresent(Tool.class)) {
                return method;
            }
        }
        return null;
    }

    /**
     * 根据方法参数名和类型，从 params Map 中取值并转换类型
     * <p>
     * <b>参数名获取</b>：依赖 Spring Boot 3.x 默认开启的 {@code -parameters} 编译选项，
     * 通过 {@code Parameter.getName()} 获取真实参数名（如 filePath、keyword）。
     *
     * @param method 工具方法
     * @param params 参数名 → 参数值的映射
     * @return 方法参数数组
     * @throws ServiceException 参数缺失或类型转换失败时抛出
     */
    private Object[] buildMethodArgs(Method method, Map<String, Object> params) {
        java.lang.reflect.Parameter[] parameters = method.getParameters();
        Object[] args = new Object[parameters.length];
        for (int i = 0; i < parameters.length; i++) {
            String paramName = parameters[i].getName();
            Class<?> paramType = parameters[i].getType();
            Object value = params.get(paramName);
            try {
                args[i] = convertArgValue(value, paramType);
            } catch (Exception e) {
                throw new ServiceException(
                        "参数 " + paramName + " 类型转换失败：" + value + " → " + paramType.getSimpleName(),
                        ResultCode.INVALID_PARA.getCode());
            }
        }
        return args;
    }

    /**
     * 参数值类型转换
     * <p>
     * 支持基本类型（String/Integer/Long/Boolean/Double/Float）的转换。
     * 基本类型参数值为 null 时返回默认值（0/false）。
     *
     * @param value      原始值（可能为 null）
     * @param targetType 目标类型
     * @return 转换后的值
     */
    private Object convertArgValue(Object value, Class<?> targetType) {
        if (value == null) {
            if (targetType == int.class || targetType == Integer.class) {
                return 0;
            }
            if (targetType == long.class || targetType == Long.class) {
                return 0L;
            }
            if (targetType == boolean.class || targetType == Boolean.class) {
                return false;
            }
            if (targetType == double.class || targetType == Double.class) {
                return 0.0;
            }
            if (targetType == float.class || targetType == Float.class) {
                return 0.0f;
            }
            return null;
        }
        if (targetType == String.class) {
            return value.toString();
        }
        String strValue = value.toString();
        if (targetType == int.class || targetType == Integer.class) {
            return Integer.parseInt(strValue);
        }
        if (targetType == long.class || targetType == Long.class) {
            return Long.parseLong(strValue);
        }
        if (targetType == boolean.class || targetType == Boolean.class) {
            return Boolean.parseBoolean(strValue);
        }
        if (targetType == double.class || targetType == Double.class) {
            return Double.parseDouble(strValue);
        }
        if (targetType == float.class || targetType == Float.class) {
            return Float.parseFloat(strValue);
        }
        // 其他类型直接返回原值
        return value;
    }

    /**
     * 带超时控制的方法调用
     * <p>
     * 使用 {@link CompletableFuture#supplyAsync} 在独立线程执行工具方法，
     * 通过 {@link CompletableFuture#get(long, TimeUnit)} 控制超时（默认 30 秒，
     * 可配置 {@code scaffold.tool.timeout}）。
     * <p>
     * <b>异常传递</b>：工具方法内部抛出的异常会被 {@link java.lang.reflect.InvocationTargetException}
     * 包装，这里解包后原样抛出，由 {@link #testTool} 的 catch 块统一处理。
     *
     * @param toolBean 工具 Bean 实例
     * @param method   工具方法
     * @param args     方法参数
     * @return 工具方法返回值（toString 后的字符串）
     * @throws TimeoutException 工具执行超时
     * @throws Exception        工具方法内部抛出的异常
     */
    private String invokeToolWithTimeout(Object toolBean, Method method, Object[] args)
            throws TimeoutException, Exception {
        method.setAccessible(true);
        CompletableFuture<String> future = CompletableFuture.supplyAsync(() -> {
            try {
                Object result = method.invoke(toolBean, args);
                return result != null ? result.toString() : null;
            } catch (java.lang.reflect.InvocationTargetException e) {
                Throwable target = e.getTargetException();
                throw new RuntimeException(target != null ? target : e);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
        try {
            return future.get(toolTimeoutSeconds, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            throw e;
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof Exception) {
                throw (Exception) cause;
            }
            throw e;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("工具调用被中断", e);
        }
    }

    /**
     * 工具使用统计内部类
     */
    private static class ToolUsageStat {
        /**
         * 最后使用时间（毫秒时间戳）
         */
        long lastUsedTime = 0;
        /**
         * 累计使用次数
         */
        int usedCount = 0;
    }
}