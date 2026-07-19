package com.zmbdp.chat.service.service;

import com.zmbdp.chat.api.ai.domain.vo.ModelVO;
import com.zmbdp.chat.service.config.ModelConfig;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.embedding.EmbeddingModel;

import java.util.List;

/**
 * 模型管理服务
 * <p>
 * 多模型管理与切换，为不同场景选择合适的模型；维护 ChatClient 和 EmbeddingModel 实例池。
 * <p>
 * 启动时从 Nacos 读取 {@code spring.ai.models} 配置列表，为每个 enabled=true 的模型创建 ChatClient 实例，
 * 缓存到 {@code Map<String, ChatClient> chatClientPool}。
 *
 * @author 稚名不带撇
 */
public interface IModelService {

    /*=============================================    内部调用    =============================================*/

    /**
     * 选择模型
     * <p>
     * 根据请求类型（文本/图文）和用户指定模型名，选择合适的模型配置。
     * 若指定模型不存在或未启用，抛 {@code ServiceException}。
     *
     * @param request 包含请求类型（文本/图文）、用户指定模型名
     * @return 选中的模型配置
     */
    ModelConfig selectModel(Object request);

    /**
     * 获取指定模型的 ChatClient
     * <p>
     * 从 ChatClient 实例池中获取指定模型的客户端。
     *
     * @param modelName 模型名称（如 deepseek-v4-flash）
     * @return 对应的 ChatClient 实例
     */
    ChatClient getChatClient(String modelName);

    /**
     * 获取默认文本对话 ChatClient
     * <p>
     * 获取配置中 {@code default=true} 的文本模型 ChatClient。
     *
     * @return 默认文本对话 ChatClient
     */
    ChatClient getDefaultChatClient();

    /**
     * 获取默认视觉模型名称
     * <p>
     * 返回 Nacos {@code spring.ai.models} 中 {@code default-model=true} 且 {@code type=TEXT_AND_IMAGE}
     * 的模型名称；未配置默认视觉模型时回退到首个启用的视觉模型；都没有则返回 null。
     * <p>
     * 用于图文对话场景的模型选择，避免在代码中硬编码模型名。
     *
     * @return 默认视觉模型名称，未配置返回 null
     */
    String getDefaultVisionModelName();

    /**
     * 获取 Embedding 模型
     * <p>
     * 返回用于向量生成的 Embedding 模型（text-embedding-v1），单例。
     *
     * @return 当前配置的 Embedding 模型
     */
    EmbeddingModel getEmbeddingModel();

    /**
     * 获取所有可用模型列表
     * <p>
     * 供 admin-service 查询，返回含 capabilities、isDefault 字段的模型列表。
     *
     * @return 所有可用模型列表
     */
    List<ModelVO> listModels();
}
