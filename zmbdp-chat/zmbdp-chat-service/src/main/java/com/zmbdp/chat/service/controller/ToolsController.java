package com.zmbdp.chat.service.controller;

import com.zmbdp.chat.api.ai.domain.dto.ToolConfigDTO;
import com.zmbdp.chat.api.ai.domain.dto.ToolTestReqDTO;
import com.zmbdp.chat.api.ai.domain.vo.ToolTestResultVO;
import com.zmbdp.chat.api.ai.domain.vo.ToolVO;
import com.zmbdp.chat.api.ai.feign.ToolsApi;
import com.zmbdp.chat.service.service.IAdminService;
import com.zmbdp.common.domain.domain.Result;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Agent 工具管理 Controller（chat-service 端）
 * <p>
 * 实现 {@link ToolsApi} Feign 接口，提供工具列表查询、配置更新、工具测试等能力
 *
 * @author 稚名不带撇
 */
@Slf4j
@RestController
@RequestMapping("/tools")
public class ToolsController implements ToolsApi {

    /**
     * B端管理服务（AI 业务相关）
     */
    @Autowired
    private IAdminService adminService;

    /**
     * 获取工具列表（不分页）
     *
     * @return 工具 VO 列表
     */
    @Override
    public Result<List<ToolVO>> getTools() {
        log.info("获取工具列表");
        List<ToolVO> list = adminService.listTools();
        return Result.success(list);
    }

    /**
     * 更新工具配置
     *
     * @param name 工具名称
     * @param dto  工具配置更新请求
     * @return 操作结果
     */
    @Override
    public Result<Void> updateToolConfig(String name, @Validated ToolConfigDTO dto) {
        log.info("更新工具配置：name = {}, enabled = {}", name, dto.getEnabled());
        adminService.updateToolConfig(name, dto);
        return Result.success();
    }

    /**
     * 测试工具调用
     *
     * @param name 工具名称
     * @param dto  测试请求
     * @return 测试结果
     */
    @Override
    public Result<ToolTestResultVO> testTool(String name, @Validated ToolTestReqDTO dto) {
        log.info("测试工具调用：name = {}", name);
        ToolTestResultVO vo = adminService.testTool(name, dto);
        return Result.success(vo);
    }
}