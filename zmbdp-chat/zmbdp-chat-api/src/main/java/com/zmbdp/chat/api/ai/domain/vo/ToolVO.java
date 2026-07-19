package com.zmbdp.chat.api.ai.domain.vo;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.util.Map;

/**
 * Agent 工具 VO
 * <p>
 * 展示已注册的工具列表，含工具名称、描述、启用状态、配置参数、使用统计等。
 *
 * @author 稚名不带撇
 */
@Data
public class ToolVO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 工具名称（如 ReadFileTool、SearchCodeTool）
     */
    private String name;

    /**
     * 工具描述
     */
    private String description;

    /**
     * 是否启用
     */
    private Boolean enabled;

    /**
     * 工具配置参数（因工具类型而异）
     */
    private Map<String, Object> config;

    /**
     * 最后使用时间（时间戳，毫秒）
     */
    private Long lastUsedTime;

    /**
     * 累计使用次数
     */
    private Integer usedCount;
}
