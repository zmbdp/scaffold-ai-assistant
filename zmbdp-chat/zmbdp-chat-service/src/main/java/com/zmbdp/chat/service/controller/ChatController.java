package com.zmbdp.chat.service.controller;

import com.zmbdp.chat.api.chat.domain.dto.RetrieveReqDTO;
import com.zmbdp.chat.api.chat.domain.vo.DocumentVO;
import com.zmbdp.chat.api.chat.feign.ChatApi;
import com.zmbdp.chat.service.service.IVectorStoreService;
import com.zmbdp.common.domain.domain.Result;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.util.StringUtils;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * AI 对话 Controller（chat-service 端）
 * <p>
 * 实现 {@link ChatApi} Feign 接口，提供 RAG 检索能力
 * <p>
 * <b>注意</b>：流式对话端点不在此 Controller 中，由独立的
 * {@link ChatStreamController}（文本流式）和 {@link ChatWithImageStreamController}（图文流式）提供，
 * 因为 Feign 不支持 Flux 返回，SSE 端点不走 Feign 契约。
 * <p>
 * <b>RAG 检索</b>：{@link #retrieveContext} 委托给 {@link IVectorStoreService}
 * （Embedding → Milvus 检索 → Reranking），不调用 LLM，仅返回检索结果。
 *
 * @author 稚名不带撇
 */
@Slf4j
@RestController
@RequestMapping("/chat")
public class ChatController implements ChatApi {

    /**
     * 默认 topK（从 Nacos 配置 {@code scaffold.rag.top-k} 读取，范围 1-20）
     */
    @Value("${scaffold.rag.top-k:5}")
    private int defaultTopK;

    /**
     * 向量存储服务（提供 RAG 检索能力）
     */
    @Autowired
    private IVectorStoreService vectorStoreService;

    /**
     * RAG 检索（不调用 LLM，仅做向量检索 + 重排序）
     * <p>
     * 供 portal-service 在流式对话前获取相关文档上下文，
     * 也供 admin-service 的知识源召回测试复用（{@code KnowledgeApi.retrieveTest()}）。
     * <p>
     * 当 sourceType 或 module 非空时，使用带元数据过滤的检索；
     * 否则使用默认检索。topK 为空时使用 Nacos 配置的默认值。
     *
     * @param request 检索请求（含问题、topK、过滤条件）
     * @return 命中的文档分块列表（按相似度降序，已 Reranking）
     */
    @Override
    public Result<List<DocumentVO>> retrieveContext(@Validated RetrieveReqDTO request) {
        log.info("RAG 检索：question = {}, topK = {}, sourceType = {}, module = {}",
                request.getQuestion(), request.getTopK(), request.getSourceType(), request.getModule());
        List<DocumentVO> list = doRetrieve(request);
        return Result.success(list);
    }

    /*=============================================    私有方法    =============================================*/

    /**
     * 执行 RAG 检索
     * <p>
     * 当 sourceType 或 module 非空时，使用 {@link IVectorStoreService#searchWithFilter} 带过滤条件检索；
     * 否则使用 {@link IVectorStoreService#search} 默认检索。topK 为空时使用 Nacos 配置的默认值。
     *
     * @param request 检索请求
     * @return 命中的文档分块列表
     */
    private List<DocumentVO> doRetrieve(RetrieveReqDTO request) {
        int topK = request.getTopK() != null ? request.getTopK() : defaultTopK;
        boolean hasSourceType = StringUtils.hasText(request.getSourceType());
        boolean hasModule = StringUtils.hasText(request.getModule());
        if (hasSourceType || hasModule) {
            Map<String, String> filters = new HashMap<>(4);
            if (hasSourceType) {
                filters.put("source_type", request.getSourceType());
            }
            if (hasModule) {
                filters.put("module", request.getModule());
            }
            return vectorStoreService.searchWithFilter(request.getQuestion(), topK, filters);
        }
        List<DocumentVO> result = vectorStoreService.search(request.getQuestion(), topK);
        return result != null ? result : new ArrayList<>();
    }
}