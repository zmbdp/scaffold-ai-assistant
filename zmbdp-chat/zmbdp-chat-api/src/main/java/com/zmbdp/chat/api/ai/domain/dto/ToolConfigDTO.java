package com.zmbdp.chat.api.ai.domain.dto;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * 工具配置更新请求 DTO
 * <p>
 * 用于更新单个 Agent 工具的启用状态。
 * <p>
 * <b>工具参数说明</b>：工具的运行时参数（如 maxFileSize、basePath 等）统一通过 Nacos 配置 +
 * {@code @RefreshScope} 管理，不通过此接口修改。如需调整工具参数，请在 Nacos 的
 * {@code share-knowledge-*.yaml} 中修改对应的 {@code knowledge.*} 配置项。
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
}
