package com.zmbdp.chat.service.service;

import com.zmbdp.chat.api.chat.domain.dto.ChatStreamReqDTO;
import com.zmbdp.chat.api.chat.domain.dto.ChatWithImageStreamReqDTO;
import reactor.core.publisher.Flux;

/**
 * AI 对话核心服务
 * <p>
 * 负责流式调用大模型、工具调用编排、对话记忆管理和对话记录持久化。
 * <p>
 * <b>仅负责流式对话</b>（返回 {@code Flux<String>}），不提供同步对话方法；
 * 同步 RAG 检索由 {@code ChatController.retrieveContext()} 直接委托给 {@link IVectorStoreService}。
 * <p>
 * <b>Prompt 拼接</b>由 portal-service 的 {@code IChatPortalService} 完成，
 * chat-service 接收已拼接好的 {@code prompt}。
 *
 * @author 稚名不带撇
 */
public interface IChatService {

    /*=============================================    内部调用    =============================================*/

    /**
     * 流式文本对话
     * <p>
     * 执行流程（8 步）：
     * <ol>
     *     <li>解析参数，确定使用的模型（ModelService.getChatClient）</li>
     *     <li>获取对话历史（ChatMemoryService.getHistory）</li>
     *     <li>构建消息列表（历史消息 + 当前用户消息）</li>
     *     <li>判断是否需要工具调用（ToolRegistryService 获取已启用工具）</li>
     *     <li>调用 ChatClient.stream() 流式生成</li>
     *     <li>流式输出完成后，异步保存对话历史到 Redis</li>
     *     <li>记录对话到 MySQL（sys_ai_conversation 表）</li>
     *     <li>如果使用了工具，记录 tool_usage 信息</li>
     * </ol>
     * <p>
     * <b>SSE 帧类型</b>：内容帧（chunk）、工具调用开始帧（toolCall）、工具调用结果帧（toolResult）、
     * 结束帧（done=true）、错误帧（error）。
     *
     * @param request 流式对话请求（含完整 Prompt、sessionId、模型参数）
     * @return SSE 流（每帧为 JSON 字符串）
     */
    Flux<String> streamChat(ChatStreamReqDTO request);

    /**
     * 流式图文对话
     * <p>
     * 使用视觉模型（默认取 Nacos 配置的默认视觉模型），支持多图输入。
     * SSE 帧格式与 {@link #streamChat} 一致。
     *
     * @param request 图文流式对话请求（含用户提问、图片 Base64 列表、完整 Prompt）
     * @return SSE 流（每帧为 JSON 字符串）
     */
    Flux<String> streamChatWithImage(ChatWithImageStreamReqDTO request);
}
