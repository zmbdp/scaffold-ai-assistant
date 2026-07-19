package com.zmbdp.chat.api.chat.feign;

import com.zmbdp.chat.api.chat.domain.dto.RetrieveReqDTO;
import com.zmbdp.chat.api.chat.domain.vo.DocumentVO;
import com.zmbdp.common.domain.domain.Result;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

import java.util.List;

/**
 * AI 对话服务远程调用 Api（仅提供 RAG 检索能力）
 * <p>
 * chat-service 通过本接口暴露同步非对话能力，供 portal-service / admin-service 调用。
 * <p>
 * <b>重要说明</b>：AI 流式对话不通过 Feign（Feign 不支持 Flux 返回），
 * 由 portal-service 通过 WebClient 调用 chat-service 的 SSE 端点
 * {@code POST /chat/completions/stream} 和 {@code POST /chat/image/completions/stream}（见 08-API接口设计.md 8.5 节）。
 *
 * @author 稚名不带撇
 */
@FeignClient(contextId = "chatApi", name = "zmbdp-chat-service", path = "/chat")
public interface ChatApi {

    /**
     * RAG 检索（不调用 LLM，仅做向量检索 + 重排序）
     * <p>
     * 用于 portal-service 在流式对话前获取相关文档上下文，
     * 也用于 admin-service 的知识源召回测试（{@code KnowledgeApi.retrieveTest()} 复用本能力）。
     *
     * @param request 检索请求（含问题、topK、过滤条件）
     * @return 命中的文档分块列表（按相似度降序，已 Reranking）
     */
    @PostMapping("/retrieve")
    Result<List<DocumentVO>> retrieveContext(@RequestBody RetrieveReqDTO request);
}
