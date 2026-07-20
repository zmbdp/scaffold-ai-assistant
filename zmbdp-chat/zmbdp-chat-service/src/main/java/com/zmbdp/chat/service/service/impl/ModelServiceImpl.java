package com.zmbdp.chat.service.service.impl;

import com.zmbdp.chat.api.ai.domain.vo.ModelVO;
import com.zmbdp.chat.api.chat.domain.dto.ChatStreamReqDTO;
import com.zmbdp.chat.service.config.ModelConfig;
import com.zmbdp.chat.service.config.ModelProperties;
import com.zmbdp.chat.service.service.IModelService;
import com.zmbdp.common.core.utils.BeanCopyUtil;
import com.zmbdp.common.domain.exception.ServiceException;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cloud.context.config.annotation.RefreshScope;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 模型管理服务实现类
 * <p>
 * 多模型管理与切换，维护 ChatClient 实例池和 EmbeddingModel 单例。
 * <p>
 * <b>初始化流程</b>（{@code @PostConstruct}）：
 * <ol>
 *     <li>从 {@link ModelProperties} 读取 {@code spring.ai.models} 配置列表（由 Nacos 绑定）</li>
 *     <li>为每个 {@code enabled=true} 的模型构建 {@link ChatClient} 实例，缓存到 {@code chatClientPool}</li>
 *     <li>识别 {@code defaultModel=true} 的文本模型，缓存到 {@code defaultTextModel}</li>
 * </ol>
 * <p>
 * <b>ChatClient 实例池设计说明</b>：
 * Spring AI Alibaba 1.0.0 GA 的 {@link ChatClient.Builder} 由 starter-dashscope 自动注入，
 * 通过 {@link ChatClient.Builder#build()} 创建的 ChatClient 实例本身是无状态的，
 * 实际模型切换在调用 {@code ChatClient.prompt().options(...)} 时通过 DashScopeChatOptions 动态指定。
 * 因此 {@code chatClientPool} 中每个模型共用同一个 Builder 构建的实例，但通过 modelName 索引，
 * 便于后续扩展为基于 {@code ChatClient.Builder.defaultOptions(...)} 的差异化实例构建。
 * <p>
 * <b>热刷新机制</b>：
 * 配合 {@link RefreshScope}，Nacos 配置变更时 {@link ModelProperties} 自动重新绑定，
 * 本 Bean 被销毁重建后 {@code @PostConstruct} 重新调用，自动重建 ChatClient 实例池和默认模型配置。
 *
 * @author 稚名不带撇
 */
@Slf4j
@Service
@RefreshScope
public class ModelServiceImpl implements IModelService {

    /**
     * ChatClient 构建器（Spring AI 自动注入）
     */
    @Autowired
    private ChatClient.Builder chatClientBuilder;

    /**
     * Embedding 模型（Spring AI 自动注入，text-embedding-v1 单例）
     */
    @Autowired
    private EmbeddingModel embeddingModel;

    /**
     * 多模型配置属性（绑定 Nacos spring.ai.models）
     */
    @Autowired
    private ModelProperties modelProperties;

    /**
     * ChatClient 实例池（key=模型名，value=ChatClient 实例）
     * <p>
     * 启动时为每个 {@code enabled=true} 的模型构建实例并存入此 Map。
     * 使用 {@link ConcurrentHashMap} 防止多线程初始化竞争。
     */
    private final Map<String, ChatClient> chatClientPool = new ConcurrentHashMap<>();

    /**
     * 模型配置列表（{@code @PostConstruct} 时从 {@link ModelProperties} 加载）
     */
    private volatile List<ModelConfig> modelConfigs = new ArrayList<>();

    /**
     * 默认文本模型配置（{@code defaultModel=true} 的 TEXT_ONLY 模型）
     */
    private volatile ModelConfig defaultTextModel;

    /**
     * 默认视觉模型配置（{@code defaultModel=true} 的 TEXT_AND_IMAGE 模型）
     */
    private volatile ModelConfig defaultVisionModel;

    /*=============================================    生命周期    =============================================*/

    /**
     * 初始化模型配置和 ChatClient 实例池
     * <p>
     * 执行流程：
     * <ol>
     *     <li>从 {@link ModelProperties} 读取模型配置列表</li>
     *     <li>为每个启用模型构建 ChatClient 并放入实例池</li>
     *     <li>识别默认文本模型</li>
     * </ol>
     */
    @PostConstruct
    public void init() {
        // 1. 加载模型配置
        loadModelConfigs();
        // 2. 初始化 ChatClient 实例池
        initChatClientPool();
        log.info("ModelServiceImpl 初始化完成：模型数 = {}, 默认文本模型 = {}, 默认视觉模型 = {}",
                modelConfigs.size(),
                defaultTextModel != null ? defaultTextModel.getName() : "未配置",
                defaultVisionModel != null ? defaultVisionModel.getName() : "未配置");
    }

    /**
     * 从 {@link ModelProperties} 加载模型配置列表
     */
    private void loadModelConfigs() {
        List<ModelConfig> configs = modelProperties.getModels();
        if (configs == null || configs.isEmpty()) {
            log.warn("未配置 spring.ai.models，使用 Spring AI 默认配置");
            this.modelConfigs = new ArrayList<>();
            return;
        }
        this.modelConfigs = configs;
        log.info("加载模型配置成功，共 {} 个模型", configs.size());
    }

    /**
     * 初始化 ChatClient 实例池
     * <p>
     * 遍历启用的模型配置，构建 ChatClient 实例并放入池中；
     * 识别默认文本模型（{@code defaultModel=true} 且类型为 TEXT_ONLY）；
     * 识别默认视觉模型（{@code defaultModel=true} 且类型为 TEXT_AND_IMAGE）。
     */
    private void initChatClientPool() {
        // 清空旧池（热刷新时调用）
        chatClientPool.clear();
        defaultTextModel = null;
        defaultVisionModel = null;

        for (ModelConfig config : modelConfigs) {
            if (!Boolean.TRUE.equals(config.getEnabled())) {
                log.info("模型 {} 未启用，跳过", config.getName());
                continue;
            }
            // 构建 ChatClient 实例（无状态，运行时通过 prompt options 切换模型）
            ChatClient chatClient = chatClientBuilder.build();
            chatClientPool.put(config.getName(), chatClient);
            log.info("模型 {} 已注册到 ChatClient 实例池", config.getName());

            // 识别默认文本模型
            if (Boolean.TRUE.equals(config.getDefaultModel()) && isTextModel(config)) {
                this.defaultTextModel = config;
            }
            // 识别默认视觉模型
            if (Boolean.TRUE.equals(config.getDefaultModel()) && isVisionModel(config)) {
                this.defaultVisionModel = config;
            }
        }
        // 兜底：未配置默认文本模型时，取第一个启用的文本模型
        if (defaultTextModel == null) {
            for (ModelConfig config : modelConfigs) {
                if (Boolean.TRUE.equals(config.getEnabled()) && isTextModel(config)) {
                    this.defaultTextModel = config;
                    log.info("未配置默认文本模型，使用首个启用的文本模型：{}", config.getName());
                    break;
                }
            }
        }
        // 兜底：未配置默认视觉模型时，取第一个启用的视觉模型
        if (defaultVisionModel == null) {
            for (ModelConfig config : modelConfigs) {
                if (Boolean.TRUE.equals(config.getEnabled()) && isVisionModel(config)) {
                    this.defaultVisionModel = config;
                    log.info("未配置默认视觉模型，使用首个启用的视觉模型：{}", config.getName());
                    break;
                }
            }
        }
    }

    /**
     * 判断是否为文本模型
     *
     * @param config 模型配置
     * @return true 表示是文本模型
     */
    private boolean isTextModel(ModelConfig config) {
        if ("TEXT_ONLY".equals(config.getType())) {
            return true;
        }
        List<String> capabilities = config.getCapabilities();
        return capabilities != null && capabilities.contains("TEXT");
    }

    /**
     * 判断是否为视觉多模态模型
     *
     * @param config 模型配置
     * @return true 表示是视觉模型
     */
    private boolean isVisionModel(ModelConfig config) {
        if ("TEXT_AND_IMAGE".equals(config.getType())) {
            return true;
        }
        List<String> capabilities = config.getCapabilities();
        return capabilities != null && capabilities.contains("IMAGE");
    }

    /*=============================================    内部调用    =============================================*/

    /**
     * 选择模型
     * <p>
     * 根据请求类型（文本/图文）和用户指定模型名，选择合适的模型配置。
     * 若指定模型不存在或未启用，抛 {@link ServiceException}。
     *
     * @param request 包含请求类型（文本/图文）、用户指定模型名（支持 {@link ChatStreamReqDTO}）
     * @return 选中的模型配置
     */
    @Override
    public ModelConfig selectModel(Object request) {
        // 兼容 ChatStreamReqDTO：用户指定了模型名则优先使用
        if (request instanceof ChatStreamReqDTO req) {
            String modelName = req.getModel();
            if (modelName != null && !modelName.isEmpty()) {
                ModelConfig config = findModelByName(modelName);
                if (config == null || !Boolean.TRUE.equals(config.getEnabled())) {
                    log.warn("用户指定的模型不存在或未启用：{}", modelName);
                    throw new ServiceException("指定的模型不存在或未启用：" + modelName);
                }
                return config;
            }
        }
        // 未指定模型名，返回默认文本模型
        if (defaultTextModel != null) {
            return defaultTextModel;
        }
        // 兜底：返回第一个启用的模型
        for (ModelConfig config : modelConfigs) {
            if (Boolean.TRUE.equals(config.getEnabled())) {
                return config;
            }
        }
        log.error("没有可用的模型配置，请检查 spring.ai.models 配置");
        throw new ServiceException("没有可用的模型配置");
    }

    /**
     * 获取指定模型的 ChatClient
     * <p>
     * 从 ChatClient 实例池中获取指定模型的客户端；若池中不存在，回退到默认实例。
     *
     * @param modelName 模型名称（如 deepseek-v4-flash）
     * @return 对应的 ChatClient 实例（不为 null）
     */
    @Override
    public ChatClient getChatClient(String modelName) {
        ChatClient client = chatClientPool.get(modelName);
        if (client != null) {
            return client;
        }
        log.warn("ChatClient 实例池中未找到模型 {}，回退到默认实例", modelName);
        return getDefaultChatClient();
    }

    /**
     * 获取默认文本对话 ChatClient
     * <p>
     * 获取配置中 {@code defaultModel=true} 的文本模型 ChatClient。
     * 若默认模型未配置，回退到池中任意一个实例；池为空时通过 Builder 现场构建。
     *
     * @return 默认文本对话 ChatClient（不为 null）
     */
    @Override
    public ChatClient getDefaultChatClient() {
        if (defaultTextModel != null) {
            ChatClient client = chatClientPool.get(defaultTextModel.getName());
            if (client != null) {
                return client;
            }
        }
        // 兜底1：从池中取任意一个
        if (!chatClientPool.isEmpty()) {
            return chatClientPool.values().iterator().next();
        }
        // 兜底2：现场构建（极端情况，配置缺失时仍能工作）
        log.warn("ChatClient 实例池为空，现场构建默认实例");
        return chatClientBuilder.build();
    }

    /**
     * 获取默认视觉模型名称
     * <p>
     * 返回 Nacos 配置的默认视觉模型名称（{@code default-model=true} 且 {@code type=TEXT_AND_IMAGE}）。
     * 未配置时由 {@link #initChatClientPool} 兜底取首个启用的视觉模型；都没有则返回 null。
     */
    @Override
    public String getDefaultVisionModelName() {
        return defaultVisionModel != null ? defaultVisionModel.getName() : null;
    }

    /**
     * 获取 Embedding 模型
     * <p>
     * 返回用于向量生成的 Embedding 模型（text-embedding-v1），单例。
     *
     * @return 当前配置的 Embedding 模型
     */
    @Override
    public EmbeddingModel getEmbeddingModel() {
        return embeddingModel;
    }

    /**
     * 获取所有可用模型列表
     * <p>
     * 供 admin-service 查询，返回含 capabilities、enabled 字段的模型列表。
     *
     * @return 所有可用模型列表
     */
    @Override
    public List<ModelVO> listModels() {
        return BeanCopyUtil.copyListProperties(modelConfigs, ModelVO.class);
    }

    /*=============================================    私有方法    =============================================*/

    /**
     * 按模型名查找模型配置
     *
     * @param name 模型名称
     * @return 模型配置；不存在返回 null
     */
    private ModelConfig findModelByName(String name) {
        for (ModelConfig config : modelConfigs) {
            if (name.equals(config.getName())) {
                return config;
            }
        }
        return null;
    }
}