package com.zmbdp.admin.service.ai.controller;

import com.zmbdp.chat.api.ai.domain.dto.AiConfigDTO;
import com.zmbdp.chat.api.ai.domain.vo.AiConfigVO;
import com.zmbdp.chat.api.ai.domain.vo.ModelVO;
import com.zmbdp.common.domain.domain.Result;
import com.zmbdp.common.log.annotation.LogAction;
import com.zmbdp.admin.service.ai.service.IAiAdminService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * B端 AI 配置管理 Controller
 * <p>
 * 作为B端 AI 配置管理的统一入口，通过 Feign 调用 chat-service 的 {@code AiConfigApi}。
 * <p>
 * <b>接口路径</b>：
 * <ul>
 *     <li>{@code GET /ai/config}：获取 AI 配置</li>
 *     <li>{@code PUT /ai/config}：更新 AI 配置</li>
 *     <li>{@code GET /ai/models}：获取可用模型列表</li>
 *     <li>{@code POST /ai/test}：测试 AI 连接</li>
 * </ul>
 * <p>
 * <b>网关路径映射</b>：前端请求 {@code /admin/ai/config} → gateway StripPrefix=1 → 本 Controller {@code /ai/config}
 * <p>
 * <b>职责边界</b>：本接口仅管理运行时业务参数（temperature、max_tokens 等），
 * api-key 等基础设施配置统一在 Nacos 管理。
 *
 * @author 稚名不带撇
 */
@Slf4j
@RestController
@RequestMapping("/ai")
public class AiConfigController {

    /**
     * B端 AI 管理业务编排服务
     */
    @Autowired
    private IAiAdminService aiAdminService;

    /**
     * 获取 AI 配置
     * <p>
     * 返回运行时业务参数 + 脱敏后的 api-key（仅展示前后 4 位）。
     *
     * @return AI 配置 VO
     */
    @GetMapping("/config")
    public Result<AiConfigVO> getConfig() {
        return Result.success(aiAdminService.getAiConfig());
    }

    /**
     * 更新 AI 配置
     * <p>
     * 仅更新传入的非空字段，api-key 等基础设施配置不通过本接口修改。
     *
     * @param dto AI 配置更新请求
     * @return 操作结果
     */
    @PutMapping("/config")
    @LogAction(value = "更新AI配置", module = "ai_config", recordParams = true)
    public Result<Void> updateConfig(@Validated @RequestBody AiConfigDTO dto) {
        aiAdminService.updateAiConfig(dto);
        return Result.success();
    }

    /**
     * 获取可用模型列表
     *
     * @return 模型 VO 列表
     */
    @GetMapping("/models")
    public Result<List<ModelVO>> getModels() {
        return Result.success(aiAdminService.listModels());
    }

    /**
     * 测试 AI 连接
     * <p>
     * 调用 Spring AI ChatClient 发起一次轻量测试调用，验证 AI 服务连通性。
     *
     * @return 操作结果（连接成功返回 success，失败抛业务异常）
     */
    @PostMapping("/test")
    public Result<Void> testConnection() {
        aiAdminService.testAiConnection();
        return Result.success();
    }
}