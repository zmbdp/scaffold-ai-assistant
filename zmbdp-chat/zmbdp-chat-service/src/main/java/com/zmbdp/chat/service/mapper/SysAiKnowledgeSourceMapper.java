package com.zmbdp.chat.service.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.zmbdp.chat.service.domain.entity.SysAiKnowledgeSource;
import org.apache.ibatis.annotations.Mapper;

/**
 * AI 知识源表 sys_ai_knowledge_source 的 mapper
 * <p>
 * 提供知识源管理的基础 CRUD 操作，自定义查询方法在 Service 层开发时按需添加。
 *
 * @author 稚名不带撇
 */
@Mapper
public interface SysAiKnowledgeSourceMapper extends BaseMapper<SysAiKnowledgeSource> {
}
