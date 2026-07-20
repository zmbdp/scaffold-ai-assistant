package com.zmbdp.chat.api.common.enums;

import lombok.Getter;

/**
 * 权限类型枚举（预留枚举，当前未使用）
 * <p>
 * 脚手架 v1.0 未实现细粒度权限控制（sys_ai_permission 表仅建表，校验逻辑为 v2.0 实现），
 * 此枚举为 v2.0 RBAC 扩展预留。
 *
 * @author 稚名不带撇
 */
@Getter
public enum PermissionType {

    /**
     * 菜单权限（预留）
     */
    MENU("MENU", "菜单权限"),

    /**
     * 接口权限（预留）
     */
    API("API", "接口权限");

    /**
     * 权限类型编码
     */
    private final String code;

    /**
     * 权限类型描述
     */
    private final String description;

    /**
     * 构造函数
     *
     * @param code        权限类型编码
     * @param description 权限类型描述
     */
    PermissionType(String code, String description) {
        this.code = code;
        this.description = description;
    }

    /**
     * 根据编码获取枚举
     *
     * @param code 权限类型编码
     * @return 权限类型枚举，未匹配返回 null
     */
    public static PermissionType fromCode(String code) {
        if (code == null) {
            return null;
        }
        for (PermissionType type : values()) {
            if (type.getCode().equals(code)) {
                return type;
            }
        }
        return null;
    }
}