package com.zmbdp.chat.service.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.zmbdp.chat.api.history.domain.vo.HistoryVO;
import com.zmbdp.chat.api.statistics.domain.vo.HotQuestionVO;
import com.zmbdp.chat.api.statistics.domain.vo.UserStatisticsVO;
import com.zmbdp.chat.service.domain.entity.SysAiConversation;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;
import java.util.Map;

/**
 * AI 对话记录表 sys_ai_conversation 的 mapper
 * <p>
 * 提供对话记录的基础 CRUD 操作，自定义分页/统计查询方法用于历史会话列表聚合查询
 *
 * @author 稚名不带撇
 */
@Mapper
public interface SysAiConversationMapper extends BaseMapper<SysAiConversation> {

    /**
     * 按 user_id 分页查询会话列表（按 session_id 聚合）
     * <p>
     * 每个 sessionId 聚合为一条记录，返回最后一条消息摘要、消息数量等。
     * 过滤条件：is_deleted=0 且 status='SUCCESS'。
     *
     * @param userId   用户ID
     * @param offset   分页偏移量（pageNo-1 * pageSize）
     * @param pageSize 每页数量
     * @return 历史会话列表（按最后消息时间倒序）
     */
    List<HistoryVO> selectSessionListByUserId(@Param("userId") Long userId,
                                              @Param("offset") int offset,
                                              @Param("pageSize") int pageSize);

    /**
     * 按 user_id 查询会话总数（按 session_id 去重）
     * <p>
     * 与 {@link #selectSessionListByUserId} 使用相同过滤条件。
     *
     * @param userId 用户ID
     * @return 会话总数
     */
    Long selectSessionCountByUserId(@Param("userId") Long userId);

    /**
     * 统计对话总数（status='SUCCESS' 且 is_deleted=0）
     *
     * @return 对话总数
     */
    Long countTotalConversations();

    /**
     * 统计今日对话数（status='SUCCESS' 且 is_deleted=0 且 create_date=今日）
     *
     * @param today 今日日期（YYYYMMDD 格式 Long 值）
     * @return 今日对话数
     */
    Long countTodayConversations(@Param("today") Long today);

    /**
     * 统计平均响应时间（毫秒，status='SUCCESS'）
     *
     * @return 平均响应时间
     */
    Long avgResponseTime();

    /**
     * 统计近 N 天活跃用户数（按 user_id 去重）
     * <p>
     * 日期阈值由 SQL 通过 DATE_SUB(NOW(), INTERVAL days-1 DAY) 计算后转为 YYYYMMDD，
     * 与 create_date（BIGINT YYYYMMDD）比较，days 含当天（如 7 表示近 7 天含今天）。
     *
     * @param days 统计天数（含当天）
     * @return 活跃用户数
     */
    Long countActiveUsers(@Param("days") int days);

    /**
     * 按日期分组统计近 N 天每日对话数
     *
     * @param startDate 起始日期（YYYYMMDD 格式 Long 值）
     * @return 日期-对话数列表，每项含 date（String YYYY-MM-DD）和 count
     */
    List<Map<String, Object>> countDailyTrend(@Param("startDate") Long startDate);

    /**
     * 按问题内容分组统计热门问题 TOP N
     * <p>
     * 仅统计 status='SUCCESS' 且 is_deleted=0 的对话。
     *
     * @param limit 返回数量
     * @return 热门问题列表
     */
    List<HotQuestionVO> selectTopQuestions(@Param("limit") int limit);

    /**
     * 统计总用户数（按 user_id 去重）
     *
     * @return 总用户数
     */
    Long countTotalUsers();

    /**
     * 查询 Top 活跃用户列表（按对话次数降序）
     *
     * @param limit 返回数量
     * @return 活跃用户列表
     */
    List<UserStatisticsVO.TopUser> selectTopUsers(@Param("limit") int limit);

    /**
     * 统计对话总数（用于反馈率计算）
     *
     * @return 对话总数
     */
    Long countTotalConversationsForFeedback();

    /**
     * 按 session_id 查询会话所有者 userId（用于归属校验）
     * <p>
     * 返回该 sessionId 下任意一条记录的 user_id（同一 sessionId 的所有记录属于同一用户）。
     * 加 {@code LIMIT 1} 提升查询效率，避免扫描全部分片记录。
     *
     * @param sessionId 会话ID
     * @return 所有者 userId；sessionId 不存在或已全部软删除时返回 null
     */
    Long selectOwnerUserIdBySessionId(@Param("sessionId") String sessionId);

    /**
     * 按 session_id 查询全部对话记录（按 create_time 正序）
     * <p>
     * 用于历史详情接口 {@code getSessionHistory}，返回该会话的所有问答记录，
     * 每条记录含 question / answer / images / model / sources / create_time 等字段，
     * 由 Service 层拆分为 user + assistant 两条 Message。
     * <p>
     * 过滤条件：is_deleted=0，不按 status 过滤（FAILED 记录也展示，便于用户查看失败原因）。
     *
     * @param sessionId 会话ID
     * @return 对话记录列表（按 create_time 正序，最早对话在最前）
     */
    List<SysAiConversation> selectListBySessionId(@Param("sessionId") String sessionId);
}