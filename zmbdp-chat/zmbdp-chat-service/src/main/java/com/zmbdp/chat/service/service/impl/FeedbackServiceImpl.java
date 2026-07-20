package com.zmbdp.chat.service.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.zmbdp.chat.api.feedback.domain.dto.FeedbackReqDTO;
import com.zmbdp.chat.api.feedback.domain.vo.FeedbackVO;
import com.zmbdp.chat.service.domain.entity.SysAiFeedback;
import com.zmbdp.chat.service.mapper.SysAiFeedbackMapper;
import com.zmbdp.chat.service.service.IFeedbackService;
import com.zmbdp.common.core.utils.BeanCopyUtil;
import com.zmbdp.common.core.utils.StringUtil;
import com.zmbdp.common.domain.domain.ResultCode;
import com.zmbdp.common.domain.exception.ServiceException;
import com.zmbdp.common.snowflake.service.SnowflakeIdService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.CollectionUtils;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * 回答反馈服务实现类
 * <p>
 * 提供反馈提交、查询、删除以及满意度统计。
 * <p>
 * <b>覆盖语义</b>：submitFeedback 采用"先删后插"实现覆盖，
 * 由 {@link #submitFeedback} 上的 {@code @Transactional} 保证原子性，sys_ai_feedback 表的 uk_conversation_user 唯一索引作为并发兜底。
 * Controller 层的 @Idempotent 用于防重复点击（同一 Token 短时间内只允许一次），
 * 与覆盖语义不冲突（切换反馈类型时前端传不同 Token）。
 *
 * @author 稚名不带撇
 */
@Slf4j
@Service
public class FeedbackServiceImpl implements IFeedbackService {

    /**
     * 反馈类型：点赞
     */
    private static final String TYPE_LIKE = "LIKE";

    /**
     * 反馈类型：点踩
     */
    private static final String TYPE_DISLIKE = "DISLIKE";

    /**
     * 日期格式化器（YYYYMMDD，与脚手架统一）
     */
    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyyMMdd");

    /**
     * 反馈 mapper
     */
    @Autowired
    private SysAiFeedbackMapper sysAiFeedbackMapper;

    /**
     * 雪花 ID 生成服务
     */
    @Autowired
    private SnowflakeIdService snowflakeIdService;

    /*=============================================    前端调用    =============================================*/

    /**
     * 提交回答反馈（点赞 / 点踩，覆盖语义）
     * <p>
     * <b>覆盖规则</b>：
     * <ul>
     *     <li>同一用户对同一对话已存在反馈时，先物理删除旧记录，再插入新记录</li>
     *     <li>典型场景：用户从点赞切换为点踩（或反之），或修改点踩原因/评论内容</li>
     *     <li>反馈时间（createTime/createDate）随覆盖刷新为当前时间，与字段语义"反馈时间"一致</li>
     * </ul>
     * <p>
     * <b>原子性</b>：通过 {@code @Transactional} 保证"先删后插"的原子性，避免出现删除成功但插入失败导致数据丢失。
     * <p>
     * <b>并发兜底</b>：sys_ai_feedback 表的 uk_conversation_user 唯一索引防止并发场景下出现重复记录
     * （@Idempotent 在 Controller 层已拦截绝大多数重复请求，此处为最终兜底）。
     * <p>
     * feedbackType=DISLIKE 时，dislikeReason 必填（缺失返回 500031，DTO 上无法用 Jakarta Validation 表达条件必填）。
     *
     * @param dto      反馈请求
     * @param userId   用户ID（从 Header 获取）
     * @param userFrom 用户来源（从 Header 获取，sys/app）
     * @return 反馈 VO
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public FeedbackVO submitFeedback(FeedbackReqDTO dto, Long userId, String userFrom) {
        // 业务规则校验：DISLIKE 时 dislikeReason 必填（DTO 上无法用 Jakarta Validation 表达条件必填）
        if (TYPE_DISLIKE.equals(dto.getFeedbackType()) && StringUtil.isEmpty(dto.getDislikeReason())) {
            throw new ServiceException("点踩原因不能为空", ResultCode.FEEDBACK_DISLIKE_REASON_REQUIRED.getCode());
        }
        // 覆盖语义：先按 conversationId + userId 物理删除旧反馈（若存在）
        // 复用 LambdaQueryWrapper，与 deleteFeedback 方法逻辑一致
        LambdaQueryWrapper<SysAiFeedback> deleteWrapper = new LambdaQueryWrapper<>();
        deleteWrapper.eq(SysAiFeedback::getConversationId, dto.getConversationId())
                .eq(SysAiFeedback::getUserId, userId);
        int deletedRows = sysAiFeedbackMapper.delete(deleteWrapper);
        if (deletedRows > 0) {
            log.info("覆盖旧反馈：conversationId = {}, userId = {}, 删除旧记录数 = {}",
                    dto.getConversationId(), userId, deletedRows);
        }
        // 构建新反馈实体
        SysAiFeedback feedback = new SysAiFeedback();
        BeanCopyUtil.copyProperties(dto, feedback);
        feedback.setId(snowflakeIdService.nextId());
        feedback.setUserId(userId);
        feedback.setUserFrom(userFrom);
        long today = Long.parseLong(LocalDate.now().format(DATE_FORMATTER));
        feedback.setCreateDate(today);
        feedback.setCreateTime(LocalDateTime.now());
        // LIKE 时清空 dislikeReason（避免脏数据）
        if (TYPE_LIKE.equals(dto.getFeedbackType())) {
            feedback.setDislikeReason(null);
        }
        // 插入新反馈（uk_conversation_user 唯一索引兜底防并发重复）
        sysAiFeedbackMapper.insert(feedback);
        log.info("提交反馈成功：conversationId = {}, userId = {}, feedbackType = {}, 覆盖旧记录 = {}",
                dto.getConversationId(), userId, dto.getFeedbackType(), deletedRows);
        // 转换为 VO 返回
        return entityToVO(feedback);
    }

    /**
     * 查询用户对某对话已提交的反馈
     * <p>
     * 用户未反馈时返回 null（非错误）。
     *
     * @param conversationId 对话记录ID
     * @param userId         用户ID
     * @return 反馈 VO（未反馈时为 null）
     */
    @Override
    public FeedbackVO getFeedback(Long conversationId, Long userId) {
        LambdaQueryWrapper<SysAiFeedback> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(SysAiFeedback::getConversationId, conversationId)
                .eq(SysAiFeedback::getUserId, userId)
                .last("LIMIT 1");
        List<SysAiFeedback> list = sysAiFeedbackMapper.selectList(queryWrapper);
        if (CollectionUtils.isEmpty(list)) {
            return null;
        }
        return entityToVO(list.get(0));
    }

    /**
     * 撤销反馈（物理删除记录）
     *
     * @param conversationId 对话记录ID
     * @param userId         用户ID
     */
    @Override
    public void deleteFeedback(Long conversationId, Long userId) {
        LambdaQueryWrapper<SysAiFeedback> deleteWrapper = new LambdaQueryWrapper<>();
        deleteWrapper.eq(SysAiFeedback::getConversationId, conversationId)
                .eq(SysAiFeedback::getUserId, userId);
        int rows = sysAiFeedbackMapper.delete(deleteWrapper);
        log.info("撤销反馈：conversationId = {}, userId = {}, 删除行数 = {}", conversationId, userId, rows);
    }

    /*=============================================    私有方法    =============================================*/

    /**
     * 反馈实体转 VO
     *
     * @param feedback 反馈实体
     * @return 反馈 VO
     */
    private FeedbackVO entityToVO(SysAiFeedback feedback) {
        FeedbackVO vo = new FeedbackVO();
        BeanCopyUtil.copyProperties(feedback, vo);
        return vo;
    }
}