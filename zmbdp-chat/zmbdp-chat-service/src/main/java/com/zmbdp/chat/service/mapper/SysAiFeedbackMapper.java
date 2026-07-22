package com.zmbdp.chat.service.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.zmbdp.chat.api.feedback.domain.vo.FeedbackAdminVO;
import com.zmbdp.chat.service.domain.entity.SysAiFeedback;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;
import java.util.Map;

/**
 * AI 回答反馈表 sys_ai_feedback 的 mapper
 * <p>
 * 提供回答反馈的基础 CRUD 操作，自定义统计查询方法，
 * 以及 B 端反馈明细分页查询（联表 sys_ai_conversation）。
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

    /**
     * B 端反馈明细分页查询（联表 sys_ai_conversation）
     * <p>
     * 单条记录同时返回反馈信息 + 对话问答摘要：
     * <ul>
     *     <li>反馈字段：id / conversationId / userId / userFrom / feedbackType / dislikeReason / comment / createTime</li>
     *     <li>对话字段：question（LEFT 100 字截断）/ answerSummary（LEFT 200 字截断）/ model / sourcesJson（JSON 字符串）</li>
     * </ul>
     * sourcesJson 由 Service 层反序列化为 List&lt;String&gt; 后塞入 sources 字段。
     * <p>
     * <b>分页实现</b>：第一个参数为 MyBatis-Plus {@link IPage} 对象，
     * 分页插件自动注入 LIMIT 子句。
     *
     * @param page          分页对象（由 Service 层构建）
     * @param feedbackType  反馈类型过滤（LIKE/DISLIKE，可空）
     * @param dislikeReason 点踩原因过滤（OUTDATED/IRRELEVANT/CODE_ERROR/OTHER，可空）
     * @param userId        用户ID过滤（可空）
     * @param startDate     起始日期（格式：20260712，可空）
     * @param endDate       结束日期（格式：20260712，可空）
     * @return 反馈明细分页结果
     */
    IPage<FeedbackAdminVO> selectFeedbackListPage(
            IPage<FeedbackAdminVO> page,
            @Param("feedbackType") String feedbackType,
            @Param("dislikeReason") String dislikeReason,
            @Param("userId") Long userId,
            @Param("startDate") Long startDate,
            @Param("endDate") Long endDate);
}
