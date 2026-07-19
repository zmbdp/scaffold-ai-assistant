package com.zmbdp.chat.api.ai.domain.vo;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * 工具测试结果 VO
 * <p>
 * 返回工具测试调用的执行结果，含成功状态、结果内容、耗时、错误信息等。
 *
 * @author 稚名不带撇
 */
@Data
public class ToolTestResultVO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 是否执行成功
     */
    private Boolean success;

    /**
     * 工具执行结果（String 或 JSON 字符串）
     */
    private String result;

    /**
     * 执行耗时（毫秒）
     */
    private Long duration;

    /**
     * 失败时的错误信息（如 "工具执行超时"）
     */
    private String errorMsg;
}
