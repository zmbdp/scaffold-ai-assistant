package com.zmbdp.portal.service.feedback.service.impl;

import com.zmbdp.chat.api.feedback.domain.dto.FeedbackReqDTO;
import com.zmbdp.chat.api.feedback.domain.vo.FeedbackVO;
import com.zmbdp.chat.api.feedback.feign.FeedbackApi;
import com.zmbdp.common.domain.domain.Result;
import com.zmbdp.common.domain.domain.ResultCode;
import com.zmbdp.common.domain.exception.ServiceException;
import com.zmbdp.common.security.domain.dto.LoginUserDTO;
import com.zmbdp.common.security.service.TokenService;
import com.zmbdp.portal.service.feedback.service.IFeedbackPortalService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * C端回答反馈业务编排服务实现
 * <p>
 * 通过 Feign 调用 chat-service 的 {@code FeedbackApi}，为 C端用户提供回答反馈能力。
 * <p>
 * <b>userId / userFrom 提取</b>：每次请求通过 {@code tokenService.getLoginUser(secret)}
 * 从当前 HTTP 请求的 JWT 中解析，传给 chat-service 进行数据隔离。
 *
 * @author 稚名不带撇
 */
@Slf4j
@Service
public class FeedbackPortalServiceImpl implements IFeedbackPortalService {

    /**
     * 回答反馈 Feign 接口
     */
    @Autowired
    private FeedbackApi feedbackApi;

    /**
     * Token 服务（用于解析 JWT 提取 userId / userFrom）
     */
    @Autowired
    private TokenService tokenService;

    /**
     * JWT 密钥（从 share-token-${env}.yaml 共享配置读取）
     */
    @Value("${jwt.token.secret}")
    private String secret;

    /**
     * 提交回答反馈（覆盖语义）
     *
     * @param request 反馈请求
     * @return 反馈 VO
     */
    @Override
    public FeedbackVO submitFeedback(FeedbackReqDTO request) {
        LoginUserDTO loginUser = getCurrentLoginUser();
        Long userId = loginUser.getUserId();
        String userFrom = loginUser.getUserFrom();
        log.info("提交回答反馈：userId = {}, userFrom = {}, conversationId = {}, feedbackType = {}",
                userId, userFrom, request.getConversationId(), request.getFeedbackType());
        Result<FeedbackVO> result = feedbackApi.submitFeedback(request, userId, userFrom);
        if (result == null || result.getCode() != ResultCode.SUCCESS.getCode()) {
            log.warn("提交回答反馈失败：userId = {}, conversationId = {}, code = {}, msg = {}",
                    userId, request.getConversationId(),
                    result == null ? null : result.getCode(), result == null ? null : result.getErrMsg());
            // 透传 chat-service 返回的错误信息（如"点踩原因不能为空"）
            String errMsg = result != null && result.getErrMsg() != null ? result.getErrMsg() : "提交回答反馈失败";
            throw new ServiceException(errMsg);
        }
        return result.getData();
    }

    /**
     * 查询用户对某对话已提交的反馈
     *
     * @param conversationId 对话记录ID
     * @return 反馈 VO（未反馈时为 null）
     */
    @Override
    public FeedbackVO getFeedback(Long conversationId) {
        Long userId = getCurrentLoginUser().getUserId();
        log.info("查询回答反馈：userId = {}, conversationId = {}", userId, conversationId);
        Result<FeedbackVO> result = feedbackApi.getFeedback(conversationId, userId);
        if (result == null || result.getCode() != ResultCode.SUCCESS.getCode()) {
            log.warn("查询回答反馈失败：userId = {}, conversationId = {}, code = {}, msg = {}",
                    userId, conversationId,
                    result == null ? null : result.getCode(), result == null ? null : result.getErrMsg());
            throw new ServiceException("查询回答反馈失败");
        }
        // 用户未反馈时 chat-service 返回 data: null（非错误），直接透传
        return result.getData();
    }

    /**
     * 撤销反馈
     *
     * @param conversationId 对话记录ID
     */
    @Override
    public void deleteFeedback(Long conversationId) {
        Long userId = getCurrentLoginUser().getUserId();
        log.info("撤销回答反馈：userId = {}, conversationId = {}", userId, conversationId);
        Result<Void> result = feedbackApi.deleteFeedback(conversationId, userId);
        if (result == null || result.getCode() != ResultCode.SUCCESS.getCode()) {
            log.warn("撤销回答反馈失败：userId = {}, conversationId = {}, code = {}, msg = {}",
                    userId, conversationId,
                    result == null ? null : result.getCode(), result == null ? null : result.getErrMsg());
            throw new ServiceException("撤销回答反馈失败");
        }
    }

    /**
     * 从当前 HTTP 请求的 JWT 中提取登录用户信息
     *
     * @return 当前登录用户信息
     * @throws ServiceException 如果 Token 无效或已过期
     */
    private LoginUserDTO getCurrentLoginUser() {
        LoginUserDTO loginUser = tokenService.getLoginUser(secret);
        if (loginUser == null || loginUser.getUserId() == null) {
            log.warn("用户令牌有误，无法获取登录用户信息");
            throw new ServiceException("用户令牌有误", ResultCode.INVALID_PARA.getCode());
        }
        return loginUser;
    }
}