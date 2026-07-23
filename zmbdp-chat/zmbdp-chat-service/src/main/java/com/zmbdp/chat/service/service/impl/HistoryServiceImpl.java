package com.zmbdp.chat.service.service.impl;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.zmbdp.chat.api.history.domain.vo.HistoryDetailVO;
import com.zmbdp.chat.api.history.domain.vo.HistoryVO;
import com.zmbdp.chat.service.domain.entity.SysAiConversation;
import com.zmbdp.chat.service.mapper.SysAiConversationMapper;
import com.zmbdp.chat.service.service.IChatMemoryService;
import com.zmbdp.chat.service.service.IHistoryService;
import com.zmbdp.common.core.utils.JsonUtil;
import com.zmbdp.common.domain.domain.ResultCode;
import com.zmbdp.common.domain.domain.dto.BasePageDTO;
import com.zmbdp.common.domain.domain.vo.BasePageVO;
import com.zmbdp.common.domain.exception.ServiceException;
import com.zmbdp.common.snowflake.service.SnowflakeIdService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * 对话历史服务实现类
 * <p>
 * 对话历史管理，提供历史列表查询、会话详情查询、会话删除；
 * 持久化对话记录到 MySQL（sys_ai_conversation 表。
 * <p>
 * <b>二级存储</b>：
 * <ul>
 *     <li>Redis List：实时对话记忆（{@link IChatMemoryService} 维护）</li>
 *     <li>MySQL sys_ai_conversation 表：对话记录持久化（is_deleted 软删除）</li>
 * </ul>
 *
 * @author 稚名不带撇
 */
@Slf4j
@Service
public class HistoryServiceImpl implements IHistoryService {

    /**
     * 对话记录 mapper
     */
    @Autowired
    private SysAiConversationMapper sysAiConversationMapper;

    /**
     * 对话记忆服务（Redis List + L1 Caffeine + DB 降级）
     */
    @Autowired
    private IChatMemoryService chatMemoryService;

    /**
     * 雪花 ID 生成服务
     */
    @Autowired
    private SnowflakeIdService snowflakeIdService;

    /**
     * 日期格式化器（YYYYMMDD，与脚手架统一）
     */
    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyyMMdd");

    /**
     * 对话状态：成功
     */
    private static final String STATUS_SUCCESS = "SUCCESS";

    /**
     * 软删除标记：已删除
     */
    private static final int IS_DELETED_TRUE = 1;

    /**
     * 软删除标记：未删除
     */
    private static final int IS_DELETED_FALSE = 0;

    /*=============================================    前端调用    =============================================*/

    /**
     * 按 user_id 分页查询会话列表
     * <p>
     * 从 sys_ai_conversation 表按 session_id 聚合查询，每个会话返回最后一条消息摘要。
     *
     * @param userId   用户ID
     * @param pageNo   页码
     * @param pageSize 每页数量
     * @return 历史会话列表分页结果
     */
    @Override
    public BasePageVO<HistoryVO> getHistoryList(Long userId, Integer pageNo, Integer pageSize) {
        BasePageVO<HistoryVO> result = new BasePageVO<>();
        // 参数兜底
        if (pageNo == null || pageNo < 1) {
            pageNo = 1;
        }
        if (pageSize == null || pageSize < 1) {
            pageSize = 20;
        }
        // 查询总数
        Long totals = sysAiConversationMapper.selectSessionCountByUserId(userId);
        if (totals == null || totals == 0) {
            result.setTotals(0);
            result.setTotalPages(0);
            result.setList(new ArrayList<>());
            return result;
        }
        // 分页查询
        int offset = (pageNo - 1) * pageSize;
        List<HistoryVO> list = sysAiConversationMapper.selectSessionListByUserId(userId, offset, pageSize);
        result.setTotals(totals.intValue());
        result.setTotalPages(BasePageDTO.calculateTotalPages(totals, pageSize));
        // 分页查询结果为空（可能是超页）
        if (CollectionUtils.isEmpty(list)) {
            result.setList(new ArrayList<>());
            return result;
        }
        result.setList(list);
        return result;
    }

    /**
     * 获取会话详情
     * <p>
     * 先校验 sessionId 归属当前用户，再从 sys_ai_conversation 表查询该会话的全部问答记录，
     * 每条记录拆分为 user + assistant 两条 Message，填充全部业务字段
     * （timestamp / images / sources / model）。
     * <p>
     * <b>数据源选择</b>：使用 sys_ai_conversation 表而非 Redis，理由：
     * <ol>
     *     <li>表字段齐全（question / answer / images / model / sources / create_time）</li>
     *     <li>Redis 中的 Spring AI Message 对象只有 getText()，不携带业务字段</li>
     *     <li>表数据持久可靠，Redis 重启会清空</li>
     * </ol>
     *
     * @param sessionId 会话ID
     * @param userId    用户ID（用于归属校验）
     * @return 会话详情
     * @throws ServiceException sessionId 归属校验失败
     */
    @Override
    public HistoryDetailVO getSessionHistory(String sessionId, Long userId) {
        // 归属校验：sessionId 属于其他用户时拒绝访问
        if (!checkSessionOwnership(userId, sessionId)) {
            log.warn("获取会话详情归属校验失败：sessionId = {}, userId = {}", sessionId, userId);
            throw new ServiceException("会话不属于当前用户", ResultCode.SESSION_NOT_BELONG_TO_USER.getCode());
        }
        HistoryDetailVO vo = new HistoryDetailVO();
        vo.setSessionId(sessionId);
        // 从 sys_ai_conversation 表查询全部对话记录（按 create_time 正序）
        List<SysAiConversation> records = sysAiConversationMapper.selectListBySessionId(sessionId);
        if (CollectionUtils.isEmpty(records)) {
            vo.setMessages(new ArrayList<>());
            return vo;
        }
        // 每条记录拆分为 user + assistant 两条 Message
        List<HistoryDetailVO.Message> messageList = new ArrayList<>(records.size() * 2);
        for (SysAiConversation record : records) {
            // user 消息（用户的提问）
            HistoryDetailVO.Message userMsg = new HistoryDetailVO.Message();
            userMsg.setRole("user");
            userMsg.setContent(record.getQuestion());
            userMsg.setTimestamp(toEpochMillis(record.getCreateTime()));
            userMsg.setImages(parseStringListJson(record.getImages()));
            messageList.add(userMsg);
            // assistant 消息（AI 的回答）：answer 为空时跳过（FAILED 且无响应的情况）
            if (StringUtils.hasText(record.getAnswer())) {
                HistoryDetailVO.Message assistantMsg = new HistoryDetailVO.Message();
                assistantMsg.setRole("assistant");
                assistantMsg.setContent(record.getAnswer());
                assistantMsg.setTimestamp(toEpochMillis(record.getCreateTime()));
                assistantMsg.setModel(record.getModel());
                assistantMsg.setSources(parseStringListJson(record.getSources()));
                messageList.add(assistantMsg);
            }
        }
        vo.setMessages(messageList);
        return vo;
    }

    /**
     * LocalDateTime 转换为毫秒时间戳
     *
     * @param dateTime LocalDateTime 对象
     * @return 毫秒时间戳；dateTime 为 null 时返回 null
     */
    private Long toEpochMillis(LocalDateTime dateTime) {
        if (dateTime == null) {
            return null;
        }
        return dateTime.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
    }

    /**
     * 解析 JSON 数组字符串为 List<String>
     * <p>
     * 通用方法，用于解析 sys_ai_conversation 表中以 JSON 数组格式存储的字符串字段：
     * <ul>
     *     <li>{@code images}：图片 URL 列表（如 {@code ["https://.../img1.png","https://.../img2.jpg"]}）</li>
     *     <li>{@code sources}：RAG 引用来源列表（如 {@code ["文档1","文档2"]}）</li>
     * </ul>
     * 由 chat-service 序列化存入，本接口反序列化为 List 返回前端。
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

    /**
     * 删除会话
     * <p>
     * 先校验 sessionId 归属当前用户，再删除 Redis 对话记忆缓存 + MySQL 软删除（is_deleted=1）。
     *
     * @param sessionId 会话ID
     * @param userId    用户ID（用于归属校验）
     * @throws ServiceException sessionId 归属校验失败
     */
    @Override
    public void deleteSession(String sessionId, Long userId) {
        // 归属校验：sessionId 属于其他用户时拒绝删除
        if (!checkSessionOwnership(userId, sessionId)) {
            log.warn("删除会话归属校验失败：sessionId = {}, userId = {}", sessionId, userId);
            throw new ServiceException("会话不属于当前用户", ResultCode.SESSION_NOT_BELONG_TO_USER.getCode());
        }
        // 1. 从 Redis 删除会话历史（内部会通过 MQ 广播失效 L1 Caffeine）
        chatMemoryService.clearHistory(sessionId);
        // 2. MySQL 软删除（与 sys_ai_document 软删除机制一致，使用独立字段 is_deleted）
        LambdaUpdateWrapper<SysAiConversation> updateWrapper = new LambdaUpdateWrapper<>();
        updateWrapper.eq(SysAiConversation::getSessionId, sessionId)
                .set(SysAiConversation::getIsDeleted, IS_DELETED_TRUE);
        sysAiConversationMapper.update(null, updateWrapper);
        log.info("删除会话完成：sessionId = {}, userId = {}", sessionId, userId);
    }

    /**
     * 校验会话归属
     * <p>
     * <b>判定规则</b>：
     * <ul>
     *     <li>sessionId 在数据库中不存在（新会话）→ 返回 true（允许使用）</li>
     *     <li>sessionId 存在且 user_id 匹配 → 返回 true</li>
     *     <li>sessionId 存在但 user_id 不匹配 → 返回 false（拒绝）</li>
     * </ul>
     *
     * @param userId    用户ID
     * @param sessionId 会话ID
     * @return true 表示属于当前用户或为新会话；false 表示属于其他用户
     */
    @Override
    public boolean checkSessionOwnership(Long userId, String sessionId) {
        if (userId == null || sessionId == null || sessionId.isEmpty()) {
            return false;
        }
        Long ownerUserId = sysAiConversationMapper.selectOwnerUserIdBySessionId(sessionId);
        // sessionId 不存在（新会话）→ 允许使用
        if (ownerUserId == null) {
            return true;
        }
        // sessionId 存在，校验 user_id 匹配
        return userId.equals(ownerUserId);
    }

    /*=============================================    内部调用    =============================================*/

    /**
     * 持久化对话记录到 MySQL
     * <p>
     * 将对话记录写入 sys_ai_conversation 表，ID 由雪花算法生成，
     * 自动补充 createDate（YYYYMMDD）和 createTime（LocalDateTime）字段。
     *
     * @param conversation 对话记录实体
     */
    @Override
    public void saveConversation(SysAiConversation conversation) {
        if (conversation == null) {
            return;
        }
        // 补充主键
        if (conversation.getId() == null) {
            conversation.setId(snowflakeIdService.nextId());
        }
        // 补充日期字段（YYYYMMDD 格式，与脚手架统一）
        long today = Long.parseLong(LocalDate.now().format(DATE_FORMATTER));
        if (conversation.getCreateDate() == null) {
            conversation.setCreateDate(today);
        }
        if (conversation.getCreateTime() == null) {
            conversation.setCreateTime(LocalDateTime.now());
        }
        // 默认未删除
        if (conversation.getIsDeleted() == null) {
            conversation.setIsDeleted(IS_DELETED_FALSE);
        }
        sysAiConversationMapper.insert(conversation);
    }
}