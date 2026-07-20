package com.zmbdp.chat.service.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.zmbdp.chat.service.domain.entity.SysAiPermission;
import org.apache.ibatis.annotations.Mapper;

/**
 * AI 权限表 sys_ai_permission 的 mapper
 * <p>
 * v1.0 仅建表，权限校验逻辑为 v2.0 实现。当前仅提供基础 CRUD 操作。
 *
 * @author 稚名不带撇
 */
@Mapper
public interface SysAiPermissionMapper extends BaseMapper<SysAiPermission> {
}
