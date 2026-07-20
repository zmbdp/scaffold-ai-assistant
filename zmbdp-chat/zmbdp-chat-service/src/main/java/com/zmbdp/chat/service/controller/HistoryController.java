package com.zmbdp.chat.service.controller;

import com.zmbdp.chat.api.history.domain.vo.HistoryDetailVO;
import com.zmbdp.chat.api.history.domain.vo.HistoryVO;
import com.zmbdp.chat.api.history.feign.HistoryApi;
import com.zmbdp.chat.service.service.IHistoryService;
import com.zmbdp.common.domain.domain.Result;
import com.zmbdp.common.domain.domain.vo.BasePageVO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 对话历史管理 Controller（chat-service 端）
 * <p>
 * 实现 {@link HistoryApi} Feign 接口，提供 C 端用户的对话历史列表、会话详情、删除会话等能力
 *
 * @author 稚名不带撇
 */
@Slf4j
@RestController
@RequestMapping("/history")
public class HistoryController implements HistoryApi {

    /**
     * 对话历史服务
     */
    @Autowired
    private IHistoryService historyService;

    /**
     * 获取对话历史列表（分页）
     * <p>
     * 按 user_id 聚合查询会话列表，每个会话返回最后一条消息摘要。
     *
     * @param userId   用户ID
     * @param pageNo   页码
     * @param pageSize 每页数量
     * @return 历史列表分页结果
     */
    @Override
    public Result<BasePageVO<HistoryVO>> getHistoryList(Long userId, Integer pageNo, Integer pageSize) {
        log.info("获取对话历史列表：userId = {}, pageNo = {}, pageSize = {}", userId, pageNo, pageSize);
        BasePageVO<HistoryVO> result = historyService.getHistoryList(userId, pageNo, pageSize);
        return Result.success(result);
    }

    /**
     * 获取会话详情
     * <p>
     * 委托给 {@link IHistoryService#getSessionHistory} 获取会话完整对话记录，
     * 内部已含 L1 Caffeine → Redis List → DB 降级流程，并校验 sessionId 归属 userId。
     *
     * @param sessionId 会话ID
     * @param userId    用户ID（用于归属校验）
     * @return 会话详情 VO
     */
    @Override
    public Result<HistoryDetailVO> getSessionHistory(String sessionId, Long userId) {
        log.info("获取会话详情：sessionId = {}, userId = {}", sessionId, userId);
        HistoryDetailVO detail = historyService.getSessionHistory(sessionId, userId);
        return Result.success(detail);
    }

    /**
     * 删除会话
     * <p>
     * 同时删除 MySQL 记录（软删除）+ Redis 对话记忆缓存（MQ 广播失效 L1 Caffeine），
     * 并校验 sessionId 归属 userId。
     *
     * @param sessionId 会话ID
     * @param userId    用户ID（用于归属校验）
     * @return 操作结果
     */
    @Override
    public Result<Void> deleteSession(String sessionId, Long userId) {
        log.info("删除会话：sessionId = {}, userId = {}", sessionId, userId);
        historyService.deleteSession(sessionId, userId);
        return Result.success();
    }

    /**
     * 校验会话归属
     * <p>
     * 判断 sessionId 是否属于指定用户。新会话（数据库中不存在）视为允许使用。
     *
     * @param userId    用户ID
     * @param sessionId 会话ID
     * @return true 表示属于当前用户或为新会话；false 表示属于其他用户
     */
    @Override
    public Result<Boolean> checkSessionOwnership(Long userId, String sessionId) {
        log.info("校验会话归属：sessionId = {}, userId = {}", sessionId, userId);
        boolean owned = historyService.checkSessionOwnership(userId, sessionId);
        return Result.success(owned);
    }
}