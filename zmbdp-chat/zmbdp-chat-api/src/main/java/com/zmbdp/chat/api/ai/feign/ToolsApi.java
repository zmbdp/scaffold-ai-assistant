package com.zmbdp.chat.api.ai.feign;

import com.zmbdp.chat.api.ai.domain.dto.ToolConfigDTO;
import com.zmbdp.chat.api.ai.domain.dto.ToolTestReqDTO;
import com.zmbdp.chat.api.ai.domain.vo.ToolTestResultVO;
import com.zmbdp.chat.api.ai.domain.vo.ToolVO;
import com.zmbdp.common.domain.domain.Result;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;

import java.util.List;

/**
 * Agent 工具管理远程调用 Api
 * <p>
 * 提供工具列表查询、工具配置更新、工具测试等能力。
 * 工具配置存储在 sys_argument 表的 {@code ai.tool.{toolName}.enabled} 配置项中，
 * 由 ToolRegistryService 负责工具的动态注册 / 移除。
 *
 * @author 稚名不带撇
 */
@FeignClient(contextId = "toolsApi", name = "zmbdp-chat-service", path = "/tools")
public interface ToolsApi {

    /**
     * 获取工具列表（不分页，返回所有已注册的工具）
     *
     * @return 工具 VO 列表
     */
    @GetMapping
    Result<List<ToolVO>> getTools();

    /**
     * 更新工具配置
     * <p>
     * 更新后立即生效：若 enabled 从 false 改为 true，工具会被 ToolRegistryService 重新注册；
     * 反之则被移除。
     *
     * @param name 工具名称（如 ReadFileTool）
     * @param dto  工具配置更新请求
     * @return 操作结果
     */
    @PutMapping("/{name}")
    Result<Void> updateToolConfig(@PathVariable("name") String name, @RequestBody ToolConfigDTO dto);

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
    Result<ToolTestResultVO> testTool(@PathVariable("name") String name, @RequestBody ToolTestReqDTO dto);
}
