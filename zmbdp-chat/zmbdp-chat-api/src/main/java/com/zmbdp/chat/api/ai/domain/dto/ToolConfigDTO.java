package com.zmbdp.chat.api.ai.domain.dto;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.util.Map;

/**
 * 工具配置更新请求 DTO
 * <p>
 * 用于更新单个 Agent 工具的启用状态和配置参数。
 * <p>
 * <b>config 字段</b>：因工具类型而异，如：
 * <ul>
 *     <li>ReadFileTool：{@code {"maxFileSize": 10485760, "pathWhitelist": ["/project/src", "/project/docs"]}}</li>
 *     <li>SearchCodeTool：{@code {"limit": 10}}</li>
 * </ul>
 * 更新后立即生效，由 ToolRegistryService 刷新工具注册。
 *
 * @author 稚名不带撇
 */
@Data
public class ToolConfigDTO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 是否启用工具
     */
    private Boolean enabled;

    /**
     * 工具配置参数（JSON 格式，不同工具配置不同）
     */
    private Map<String, Object> config;
}
