package com.zmbdp.chat.service.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.zmbdp.chat.service.domain.entity.SysAiDocument;
import org.apache.ibatis.annotations.Mapper;

/**
 * AI 文档表 sys_ai_document 的 mapper
 * <p>
 * 提供文档管理的基础 CRUD 操作，自定义查询方法在 Service 层开发时按需添加。
 *
 * @author 稚名不带撇
 */
@Mapper
public interface SysAiDocumentMapper extends BaseMapper<SysAiDocument> {
}
