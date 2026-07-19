package com.zmbdp.admin.service.ai.controller;

import com.zmbdp.chat.api.ai.domain.dto.ToolConfigDTO;
import com.zmbdp.chat.api.ai.domain.dto.ToolTestReqDTO;
import com.zmbdp.chat.api.ai.domain.vo.ToolTestResultVO;
import com.zmbdp.chat.api.ai.domain.vo.ToolVO;
import com.zmbdp.common.domain.domain.Result;
import com.zmbdp.common.log.annotation.LogAction;
import com.zmbdp.admin.service.ai.service.IAiAdminService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * B端工具管理 Controller
 * <p>
 * 作为B端 Agent 工具管理的统一入口，通过 Feign 调用 chat-service 的 {@code ToolsApi}。
 * <p>
 * <b>接口路径</b>：
 * <ul>
 *     <li>{@code GET /tools}：获取工具列表</li>
 *     <li>{@code PUT /tools/{name}}：更新工具配置</li>
 *     <li>{@code POST /tools/{name}/test}：测试工具调用</li>
 * </ul>
 * <p>
 * <b>网关路径映射</b>：前端请求 {@code /admin/tools} → gateway StripPrefix=1 → 本 Controller {@code /tools}
 *
 * @author 稚名不带撇
 */
@Slf4j
@RestController
@RequestMapping("/tools")
public class ToolController {

    /**
     * B端 AI 管理业务编排服务
     */
    @Autowired
    private IAiAdminService aiAdminService;

    /**
     * 获取工具列表（不分页，返回所有已注册的工具）
     *
     * @return 工具 VO 列表
     */
    @GetMapping
    public Result<List<ToolVO>> getTools() {
        return Result.success(aiAdminService.listTools());
    }

    /**
     * 更新工具配置
     * <p>
     * 更新后立即生效：若 enabled 从 false 改为 true，工具会被重新注册；反之则被移除。
     *
     * @param name 工具名称（如 ReadFileTool）
     * @param dto  工具配置更新请求
     * @return 操作结果
     */
    @PutMapping("/{name}")
    @LogAction(value = "更新工具配置", module = "tools", recordParams = true)
    public Result<Void> updateToolConfig(@PathVariable("name") String name, @Validated @RequestBody ToolConfigDTO dto) {
        aiAdminService.updateToolConfig(name, dto);
        return Result.success();
    }

    /**
     * 测试工具调用
     * <p>
     * 调用对应工具的 {@code @Tool} 方法，不影响线上对话流。
     * 超时时间默认 30 秒（可配置）。
     *
     * @param name 工具名称
     * @param dto  测试请求（含测试参数）
     * @return 测试结果
     */
    @PostMapping("/{name}/test")
    public Result<ToolTestResultVO> testTool(@PathVariable("name") String name, @Validated @RequestBody ToolTestReqDTO dto) {
        return Result.success(aiAdminService.testTool(name, dto));
    }
}