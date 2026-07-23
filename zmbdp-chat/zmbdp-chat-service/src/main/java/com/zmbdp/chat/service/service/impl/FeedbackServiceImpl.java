package com.zmbdp.chat.service.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.core.type.TypeReference;
import com.zmbdp.chat.api.feedback.domain.dto.FeedbackReqDTO;
import com.zmbdp.chat.api.feedback.domain.vo.FeedbackAdminVO;
import com.zmbdp.chat.api.feedback.domain.vo.FeedbackVO;
import com.zmbdp.chat.service.domain.entity.SysAiFeedback;
import com.zmbdp.chat.service.mapper.SysAiFeedbackMapper;
import com.zmbdp.chat.service.service.IFeedbackService;
import com.zmbdp.common.core.utils.BeanCopyUtil;
import com.zmbdp.common.core.utils.JsonUtil;
import com.zmbdp.common.core.utils.StringUtil;
import com.zmbdp.common.domain.domain.ResultCode;
import com.zmbdp.common.domain.domain.dto.BasePageDTO;
import com.zmbdp.common.domain.domain.vo.BasePageVO;
import com.zmbdp.common.domain.exception.ServiceException;
import com.zmbdp.common.snowflake.service.SnowflakeIdService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
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
     * 默认每页数量（与 AdminServiceImpl.listOperationLogs 一致）
     */
    private static final int DEFAULT_PAGE_SIZE = 20;

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

    /*=============================================    B端调用    =============================================*/

    /**
     * B 端反馈明细分页查询
     * <p>
     * 调用 {@link SysAiFeedbackMapper#selectFeedbackListPage} 执行联表查询，
     * SQL 层已截断 question（LEFT 100 字）和 answer（LEFT 200 字）。
     * Service 层负责将 sourcesJson（JSON 字符串）反序列化为 List&lt;String&gt; 后塞入 sources 字段。
     *
     * @param pageNo        页码（兜底默认 1）
     * @param pageSize      每页数量（兜底默认 20）
     * @param feedbackType  反馈类型过滤（LIKE/DISLIKE，可空）
     * @param dislikeReason 点踩原因过滤（OUTDATED/IRRELEVANT/CODE_ERROR/OTHER，可空）
     * @param userId        用户ID过滤（可空）
     * @param startDate     起始日期（格式：20260712，可空）
     * @param endDate       结束日期（格式：20260712，可空）
     * @return 反馈明细分页结果
     */
    @Override
    public BasePageVO<FeedbackAdminVO> listFeedbacks(Integer pageNo, Integer pageSize,
                                                      String feedbackType, String dislikeReason,
                                                      Long userId, Long startDate, Long endDate) {
        // 参数兜底
        if (pageNo == null || pageNo < 1) {
            pageNo = 1;
        }
        if (pageSize == null || pageSize < 1) {
            pageSize = DEFAULT_PAGE_SIZE;
        }
        // 分页查询（MyBatis-Plus 分页插件自动注入 LIMIT）
        Page<FeedbackAdminVO> page = new Page<>(pageNo, pageSize);
        IPage<FeedbackAdminVO> result = sysAiFeedbackMapper.selectFeedbackListPage(
                page, feedbackType, dislikeReason, userId, startDate, endDate);
        // 反序列化 sourcesJson → sources（List<String>）
        List<FeedbackAdminVO> records = result.getRecords();
        if (records != null && !records.isEmpty()) {
            for (FeedbackAdminVO vo : records) {
                vo.setSources(parseStringListJson(vo.getSourcesJson()));
                vo.setSourcesJson(null);
            }
        }
        // 封装 BasePageVO 返回
        BasePageVO<FeedbackAdminVO> vo = new BasePageVO<>();
        vo.setTotals(result.getTotal() > 0 ? (int) result.getTotal() : 0);
        vo.setTotalPages(BasePageDTO.calculateTotalPages(result.getTotal(), pageSize));
        vo.setList(records != null ? records : new ArrayList<>());
        return vo;
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

    /**
     * 解析 JSON 数组字符串为 List&lt;String&gt;
     * <p>
     * 用于解析 sys_ai_conversation.sources 字段（JSON 数组格式，如 {@code ["文档1","文档2"]}）。
     * 与 HistoryServiceImpl.parseStringListJson 逻辑一致，保持项目内反序列化方式统一。
     *
     * @param jsonArrayStr JSON 数组字符串
     * @return 字符串列表；为空或解析失败时返回 null
     */
    private List<String> parseStringListJson(String jsonArrayStr) {
        if (!StringUtils.hasText(jsonArrayStr)) {
            return null;
        }
        try {
            List<String> list = JsonUtil.jsonToClass(jsonArrayStr, new TypeReference<List<String>>() {});
            return (list != null && !list.isEmpty()) ? list : null;
        } catch (Exception e) {
            log.warn("解析 JSON 数组字符串失败：json = {}", jsonArrayStr, e);
            return null;
        }
    }
}