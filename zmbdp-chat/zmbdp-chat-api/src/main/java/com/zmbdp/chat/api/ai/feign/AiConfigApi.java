package com.zmbdp.chat.api.ai.feign;

import com.zmbdp.chat.api.ai.domain.dto.AiConfigDTO;
import com.zmbdp.chat.api.ai.domain.vo.AiConfigVO;
import com.zmbdp.chat.api.ai.domain.vo.ModelVO;
import com.zmbdp.common.domain.domain.Result;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;

import java.util.List;

/**
 * AI 配置管理远程调用 Api
 * <p>
 * 提供运行时 AI 配置的查询 / 更新、可用模型列表查询、AI 连接测试等能力。
 * <p>
 * <b>职责边界</b>：
 * <ul>
 *     <li>本接口仅管理 sys_argument 表的 {@code ai.*} 运行时业务参数
 *         （temperature、max_tokens、top_k、enable_rag、enable_tools）</li>
 *     <li>api-key、模型名称等基础设施配置统一在 Nacos 管理，不通过本接口修改</li>
 * </ul>
 *
 * @author 稚名不带撇
 */
@FeignClient(contextId = "aiConfigApi", name = "zmbdp-chat-service", path = "/ai")
public interface AiConfigApi {

    /**
     * 获取 AI 配置
     * <p>
     * 返回运行时业务参数 + 脱敏后的 api-key（仅展示前后 4 位）。
     *
     * @return AI 配置 VO
     */
    @GetMapping("/config")
    Result<AiConfigVO> getConfig();

    /**
     * 更新 AI 配置
     * <p>
     * 仅更新传入的非空字段，api-key 等基础设施配置不通过本接口修改。
     *
     * @param dto AI 配置更新请求
     * @return 操作结果
     */
    @PutMapping("/config")
    Result<Void> updateConfig(@RequestBody AiConfigDTO dto);

    /**
     * 获取可用模型列表
     *
     * @return 模型 VO 列表
     */
    @GetMapping("/models")
    Result<List<ModelVO>> getModels();

    /**
     * 测试 AI 连接
     * <p>
     * 调用 Spring AI ChatClient 发起一次轻量测试调用，验证 AI 服务连通性。
     *
     * @return 操作结果（连接成功返回 success，失败抛业务异常）
     */
    @PostMapping("/test")
    Result<Void> testConnection();
}
