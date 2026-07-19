package com.zmbdp.chat.service.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/**
 * AI 权限表 sys_ai_permission
 * <p>
 * v1.0 仅建表，权限校验逻辑（PermissionType 枚举、4 条校验规则）为 v2.0 实现。
 * v1.0 AI 模块的权限控制通过 Gateway AuthFilter 的 userFrom 来源校验实现。
 *
 * @author 稚名不带撇
 */
@Data
@TableName("sys_ai_permission")
public class SysAiPermission {

    /**
     * 权限ID（雪花算法）
     */
    @TableId(value = "id", type = IdType.ASSIGN_ID)
    private Long id;

    /**
     * 用户ID（关联 sys_user 或 app_user 表）
     */
    private Long userId;

    /**
     * 用户来源（sys/app）
     */
    private String userFrom;

    /**
     * 模型编码（如 qwen-plus、gpt-4o）
     */
    private String modelCode;

    /**
     * 是否启用（1=启用，0=禁用）
     */
    private Integer enabled;

    /**
     * 调用配额（-1 表示无限制）
     */
    private Integer quota;

    /**
     * 已使用配额
     */
    private Integer usedQuota;

    /**
     * 过期日期（格式：20260712，为空表示永久）
     */
    private Long expireDate;

    /**
     * 创建日期（格式：20260712）
     */
    private Long createDate;

    /**
     * 更新日期（格式：20260712）
     */
    private Long updateDate;
}
