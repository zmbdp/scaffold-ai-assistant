package com.zmbdp.chat.service.controller;

import com.zmbdp.chat.api.ai.domain.dto.AiConfigDTO;
import com.zmbdp.chat.api.ai.domain.vo.AiConfigVO;
import com.zmbdp.chat.api.ai.domain.vo.ModelVO;
import com.zmbdp.chat.api.ai.feign.AiConfigApi;
import com.zmbdp.chat.service.service.IAdminService;
import com.zmbdp.common.domain.domain.Result;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * AI 配置管理 Controller（chat-service 端）
 * <p>
 * 实现 {@link AiConfigApi} Feign 接口，提供运行时 AI 配置的查询 / 更新、模型列表、连接测试等能力
 * <p>
 * <b>职责边界</b>：本接口仅管理 sys_argument 表的 {@code ai.*} 运行时业务参数，
 * api-key、模型名称等基础设施配置统一在 Nacos 管理。
 *
 * @author 稚名不带撇
 */
@Slf4j
@RestController
@RequestMapping("/ai")
public class AiConfigController implements AiConfigApi {

    /**
     * B端管理服务（AI 业务相关）
     */
    @Autowired
    private IAdminService adminService;

    /**
     * 获取 AI 配置
     * <p>
     * 返回运行时业务参数 + 脱敏后的 api-key（仅展示前后 4 位）。
     *
     * @return AI 配置 VO
     */
    @Override
    public Result<AiConfigVO> getConfig() {
        log.info("获取 AI 配置");
        AiConfigVO vo = adminService.getAiConfig();
        return Result.success(vo);
    }

    /**
     * 更新 AI 配置
     * <p>
     * 仅更新传入的非空字段，api-key 等基础设施配置不通过本接口修改。
     *
     * @param dto AI 配置更新请求
     * @return 操作结果
     */
    @Override
    public Result<Void> updateConfig(@Validated AiConfigDTO dto) {
        log.info("更新 AI 配置：temperature = {}, maxTokens = {}, topK = {}, enableRag = {}, enableTools = {}",
                dto.getTemperature(), dto.getMaxTokens(), dto.getTopK(), dto.getEnableRag(), dto.getEnableTools());
        adminService.updateAiConfig(dto);
        return Result.success();
    }

    /**
     * 获取可用模型列表
     *
     * @return 模型 VO 列表
     */
    @Override
    public Result<List<ModelVO>> getModels() {
        log.info("获取可用模型列表");
        List<ModelVO> list = adminService.listModels();
        return Result.success(list);
    }

    /**
     * 测试 AI 连接
     * <p>
     * 调用 Spring AI ChatClient 发起一次轻量测试调用，验证 AI 服务连通性。
     * 使用默认模型和默认测试消息（"你好，请回复'连接正常'"）。
     *
     * @return 操作结果（连接成功返回 success，失败抛业务异常）
     */
    @Override
    public Result<Void> testConnection() {
        log.info("测试 AI 连接");
        // Controller 无参数，使用默认模型和默认测试消息（AdminServiceImpl 内部已处理 null 情况）
        boolean success = adminService.testAiConnection(null, null);
        if (!success) {
            return Result.fail("AI 连接测试失败");
        }
        return Result.success();
    }
}