package com.zmbdp.chat.api.history.feign;

import com.zmbdp.chat.api.history.domain.vo.HistoryDetailVO;
import com.zmbdp.chat.api.history.domain.vo.HistoryVO;
import com.zmbdp.common.domain.domain.Result;
import com.zmbdp.common.domain.domain.vo.BasePageVO;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * 对话历史管理远程调用 Api
 * <p>
 * 提供 C 端用户的对话历史列表、会话详情、删除会话等能力。
 * <p>
 * <b>userId 来源</b>：由 portal-service 通过 {@code tokenService.getLoginUser(token, secret)}
 * 从 JWT Token 中解析后传递给本接口（注：TokenService 无 parseToken 方法，实际方法为 getLoginUser，
 * 需传入 jwt.token.secret 密钥），chat-service 根据该 userId 过滤对话历史。
 *
 * @author 稚名不带撇
 */
@FeignClient(contextId = "historyApi", name = "zmbdp-chat-service", path = "/history")
public interface HistoryApi {

    /**
     * 获取对话历史列表（分页）
     * <p>
     * 按 sessionId 聚合，每个会话返回最后一条消息摘要。
     *
     * @param userId   用户ID（由 portal-service 从 JWT 解析）
     * @param pageNo   页码，默认 1
     * @param pageSize 每页数量，默认 20
     * @return 历史列表分页结果
     */
    @GetMapping
    Result<BasePageVO<HistoryVO>> getHistoryList(@RequestParam("userId") Long userId,
                                                  @RequestParam(value = "pageNo", defaultValue = "1") Integer pageNo,
                                                  @RequestParam(value = "pageSize", defaultValue = "20") Integer pageSize);

    /**
     * 获取会话详情
     * <p>
     * 返回该会话下的所有消息（按时间正序），含用户提问和 AI 回答。
     * 注：本方法的轮数由 chat-service 内部根据
     * {@code scaffold.rag.memory-rounds} 截取。
     * <p>
     * <b>归属校验</b>：chat-service 内部会校验 sessionId 归属 userId，校验失败返回
     * {@code ResultCode.SESSION_NOT_BELONG_TO_USER}（500032）。
     *
     * @param sessionId 会话ID
     * @param userId    用户ID（由 portal-service 从 JWT 解析，用于归属校验）
     * @return 会话详情 VO
     */
    @GetMapping("/{sessionId}")
    Result<HistoryDetailVO> getSessionHistory(@PathVariable("sessionId") String sessionId,
                                                @RequestParam("userId") Long userId);

    /**
     * 删除会话
     * <p>
     * 同时删除 MySQL 记录（软删除，置 is_deleted=1）+ Redis 对话记忆缓存
     * （通过 MQ 广播失效 L1 Caffeine）。
     * <p>
     * <b>归属校验</b>：chat-service 内部会校验 sessionId 归属 userId，校验失败返回
     * {@code ResultCode.SESSION_NOT_BELONG_TO_USER}（500032）。
     *
     * @param sessionId 会话ID
     * @param userId    用户ID（由 portal-service 从 JWT 解析，用于归属校验）
     * @return 操作结果
     */
    @DeleteMapping("/{sessionId}")
    Result<Void> deleteSession(@PathVariable("sessionId") String sessionId,
                                 @RequestParam("userId") Long userId);

    /**
     * 校验会话归属
     * <p>
     * 判断 sessionId 是否属于指定用户。
     * <p>
     * <b>判定规则</b>：
     * <ul>
     *     <li>sessionId 在数据库中不存在（新会话）→ 返回 true（允许使用）</li>
     *     <li>sessionId 存在且 user_id 匹配 → 返回 true</li>
     *     <li>sessionId 存在但 user_id 不匹配 → 返回 false（拒绝）</li>
     * </ul>
     * <p>
     * <b>使用场景</b>：portal-service 在复用 sessionId 前调用本方法校验归属
     *
     * @param userId    用户ID
     * @param sessionId 会话ID
     * @return true 表示属于当前用户或为新会话；false 表示属于其他用户
     */
    @GetMapping("/ownership/check")
    Result<Boolean> checkSessionOwnership(@RequestParam("userId") Long userId,
                                            @RequestParam("sessionId") String sessionId);
}
