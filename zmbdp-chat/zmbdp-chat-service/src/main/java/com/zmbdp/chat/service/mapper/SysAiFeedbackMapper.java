package com.zmbdp.chat.service.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.zmbdp.chat.service.domain.entity.SysAiFeedback;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;
import java.util.Map;

/**
 * AI 回答反馈表 sys_ai_feedback 的 mapper
 * <p>
 * 提供回答反馈的基础 CRUD 操作，自定义统计查询方法
 *
 * @author 稚名不带撇
 */
@Mapper
public interface SysAiFeedbackMapper extends BaseMapper<SysAiFeedback> {

    /**
     * 按反馈类型统计数量
     *
     * @param feedbackType 反馈类型（LIKE/DISLIKE）
     * @return 数量
     */
    Long countByType(@Param("feedbackType") String feedbackType);

    /**
     * 统计反馈总数
     *
     * @return 反馈总数
     */
    Long countTotalFeedback();

    /**
     * 统计反馈用户数（按 user_id 去重）
     *
     * @return 反馈用户数
     */
    Long countFeedbackUsers();

    /**
     * 按点踩原因分组统计分布
     * <p>
     * 仅统计 feedback_type='DISLIKE' 且 dislike_reason 非空的记录。
     *
     * @return 原因-数量列表，每项含 reason 和 count
     */
    List<Map<String, Object>> countDislikeReasonDistribution();
}
