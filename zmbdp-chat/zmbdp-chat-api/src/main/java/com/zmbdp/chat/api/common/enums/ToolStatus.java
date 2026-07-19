package com.zmbdp.chat.api.common.enums;

import lombok.Getter;

/**
 * 工具状态枚举
 * <p>
 * 表示 Agent 工具的启用/禁用状态，对应 sys_argument 表的 ai.tool.*.enabled 配置项。
 *
 * @author 稚名不带撇
 */
@Getter
public enum ToolStatus {

    /**
     * 已启用
     */
    ENABLED("true", "已启用"),

    /**
     * 已禁用
     */
    DISABLED("false", "已禁用");

    /**
     * 状态编码（对应 sys_argument.value 字段的 "true"/"false" 字符串值）
     */
    private final String code;

    /**
     * 状态描述
     */
    private final String description;

    /**
     * 构造函数
     *
     * @param code        状态编码
     * @param description 状态描述
     */
    ToolStatus(String code, String description) {
        this.code = code;
        this.description = description;
    }

    /**
     * 根据编码获取枚举
     *
     * @param code 状态编码
     * @return 工具状态枚举，未匹配返回 null
     */
    public static ToolStatus fromCode(String code) {
        if (code == null) {
            return null;
        }
        for (ToolStatus status : values()) {
            if (status.getCode().equals(code)) {
                return status;
            }
        }
        return null;
    }
}