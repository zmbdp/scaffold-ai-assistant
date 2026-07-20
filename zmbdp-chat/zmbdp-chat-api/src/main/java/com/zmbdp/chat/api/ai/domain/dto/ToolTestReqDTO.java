package com.zmbdp.chat.api.ai.domain.dto;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.util.Map;

/**
 * 工具测试请求 DTO
 * <p>
 * 用于在 B 端测试工具调用效果，不影响线上对话流。
 * <p>
 * <b>超时控制</b>：工具执行超时时间默认 30 秒（可配置，见 Nacos {@code ai.tool.timeout}），
 * 超时返回 {@code success=false, errorMsg="工具执行超时"}。
 *
 * @author 稚名不带撇
 */
@Data
public class ToolTestReqDTO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 测试参数（因工具类型而异，如 {@code {"filePath": "/path/to/test/file.java"}}）
     */
    private Map<String, Object> params;
}
