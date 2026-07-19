package com.zmbdp.portal.service.history.controller;

import com.zmbdp.chat.api.history.domain.vo.HistoryDetailVO;
import com.zmbdp.chat.api.history.domain.vo.HistoryVO;
import com.zmbdp.common.domain.domain.Result;
import com.zmbdp.common.domain.domain.vo.BasePageVO;
import com.zmbdp.portal.service.history.service.IHistoryPortalService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * C端对话历史入口 Controller
 * <p>
 * 作为 C端对话历史的统一入口，接收前端请求后委托给 {@link IHistoryPortalService}
 * 通过 Feign 调用 chat-service 的 HistoryApi。
 * <p>
 * <b>接口路径</b>：
 * <ul>
 *     <li>{@code GET /history}：获取对话历史列表（分页）</li>
 *     <li>{@code GET /history/{sessionId}}：获取会话详情</li>
 *     <li>{@code DELETE /history/{sessionId}}：删除会话</li>
 * </ul>
 * <p>
 * <b>网关路径映射</b>：前端请求 {@code /portal/history} → gateway StripPrefix=1 → 本 Controller {@code /history}
 * <p>
 * <b>认证</b>：需要 JWT Token，Service 内部从 JWT 提取 userId 传给 chat-service 进行数据过滤。
 *
 * @author 稚名不带撇
 */
@Slf4j
@RestController
@RequestMapping("/history")
public class HistoryPortalController {

    /**
     * C端对话历史业务编排服务
     */
    @Autowired
    private IHistoryPortalService historyPortalService;

    /**
     * 获取对话历史列表（分页）
     * <p>
     * 按 sessionId 聚合，每个会话返回最后一条消息摘要。
     * 内部从 JWT 提取 userId，传给 chat-service 过滤当前用户的历史。
     *
     * @param pageNo   页码，默认 1
     * @param pageSize 每页数量，默认 20
     * @return 历史列表分页结果
     */
    @GetMapping
    public Result<BasePageVO<HistoryVO>> getHistory(
            @RequestParam(value = "pageNo", defaultValue = "1") Integer pageNo,
            @RequestParam(value = "pageSize", defaultValue = "20") Integer pageSize) {
        return Result.success(historyPortalService.getHistoryList(pageNo, pageSize));
    }

    /**
     * 获取会话详情
     * <p>
     * 返回该会话下的所有消息（按时间正序），含用户提问和 AI 回答。
     *
     * @param sessionId 会话ID
     * @return 会话详情 VO
     */
    @GetMapping("/{sessionId}")
    public Result<HistoryDetailVO> getSessionHistory(@PathVariable("sessionId") String sessionId) {
        return Result.success(historyPortalService.getSessionHistory(sessionId));
    }

    /**
     * 删除会话
     * <p>
     * 同时删除 MySQL 记录（软删除）+ Redis 对话记忆缓存。
     *
     * @param sessionId 会话ID
     * @return 操作结果
     */
    @DeleteMapping("/{sessionId}")
    public Result<Void> deleteSession(@PathVariable("sessionId") String sessionId) {
        historyPortalService.deleteSession(sessionId);
        return Result.success();
    }
}