package com.zmbdp.portal.service.history.service.impl;

import com.zmbdp.chat.api.history.domain.vo.HistoryDetailVO;
import com.zmbdp.chat.api.history.domain.vo.HistoryVO;
import com.zmbdp.chat.api.history.feign.HistoryApi;
import com.zmbdp.common.domain.domain.Result;
import com.zmbdp.common.domain.domain.ResultCode;
import com.zmbdp.common.domain.domain.vo.BasePageVO;
import com.zmbdp.common.domain.exception.ServiceException;
import com.zmbdp.common.security.domain.dto.LoginUserDTO;
import com.zmbdp.common.security.service.TokenService;
import com.zmbdp.portal.service.history.service.IHistoryPortalService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * C端对话历史业务编排服务实现
 * <p>
 * 通过 Feign 调用 chat-service 的 {@code HistoryApi}，为 C端用户提供对话历史管理能力。
 * <p>
 * <b>userId 提取</b>：每次请求通过 {@code tokenService.getLoginUser(secret)} 从当前 HTTP 请求
 * 的 JWT 中解析 userId，传给 chat-service 进行数据过滤，确保 C端用户只能查看/删除自己的对话历史。
 *
 * @author 稚名不带撇
 */
@Slf4j
@Service
public class HistoryPortalServiceImpl implements IHistoryPortalService {

    /**
     * 默认页码
     */
    private static final int DEFAULT_PAGE_NO = 1;

    /**
     * 默认每页数量
     */
    private static final int DEFAULT_PAGE_SIZE = 20;

    /**
     * 对话历史 Feign 接口
     */
    @Autowired
    private HistoryApi historyApi;

    /**
     * Token 服务（用于解析 JWT 提取 userId）
     */
    @Autowired
    private TokenService tokenService;

    /**
     * JWT 密钥（从 share-token-${env}.yaml 共享配置读取）
     */
    @Value("${jwt.token.secret}")
    private String secret;

    /**
     * 获取对话历史列表（分页）
     *
     * @param pageNo   页码，默认 1
     * @param pageSize 每页数量，默认 20
     * @return 历史列表分页结果
     */
    @Override
    public BasePageVO<HistoryVO> getHistoryList(Integer pageNo, Integer pageSize) {
        Long userId = getCurrentUserId();
        int finalPageNo = pageNo != null && pageNo > 0 ? pageNo : DEFAULT_PAGE_NO;
        int finalPageSize = pageSize != null && pageSize > 0 ? pageSize : DEFAULT_PAGE_SIZE;
        log.info("查询对话历史列表：userId = {}, pageNo = {}, pageSize = {}", userId, finalPageNo, finalPageSize);
        Result<BasePageVO<HistoryVO>> result = historyApi.getHistoryList(userId, finalPageNo, finalPageSize);
        if (result == null || result.getCode() != ResultCode.SUCCESS.getCode()) {
            log.warn("查询对话历史列表失败：userId = {}, code = {}, msg = {}",
                    userId, result == null ? null : result.getCode(), result == null ? null : result.getErrMsg());
            throw new ServiceException("查询对话历史列表失败");
        }
        return result.getData();
    }

    /**
     * 获取会话详情
     *
     * @param sessionId 会话ID
     * @return 会话详情 VO
     */
    @Override
    public HistoryDetailVO getSessionHistory(String sessionId) {
        Long userId = getCurrentUserId();
        log.info("查询会话详情：sessionId = {}, userId = {}", sessionId, userId);
        Result<HistoryDetailVO> result = historyApi.getSessionHistory(sessionId, userId);
        if (result == null || result.getCode() != ResultCode.SUCCESS.getCode()) {
            log.warn("查询会话详情失败：sessionId = {}, userId = {}, code = {}, msg = {}",
                    sessionId, userId, result == null ? null : result.getCode(), result == null ? null : result.getErrMsg());
            // 归属校验失败返回特定错误码，其他失败返回通用错误
            if (result != null && ResultCode.SESSION_NOT_BELONG_TO_USER.getCode() == result.getCode()) {
                throw new ServiceException("会话不属于当前用户", ResultCode.SESSION_NOT_BELONG_TO_USER.getCode());
            }
            throw new ServiceException("查询会话详情失败");
        }
        return result.getData();
    }

    /**
     * 删除会话
     *
     * @param sessionId 会话ID
     */
    @Override
    public void deleteSession(String sessionId) {
        Long userId = getCurrentUserId();
        log.info("删除会话：sessionId = {}, userId = {}", sessionId, userId);
        Result<Void> result = historyApi.deleteSession(sessionId, userId);
        if (result == null || result.getCode() != ResultCode.SUCCESS.getCode()) {
            log.warn("删除会话失败：sessionId = {}, userId = {}, code = {}, msg = {}",
                    sessionId, userId, result == null ? null : result.getCode(), result == null ? null : result.getErrMsg());
            if (result != null && ResultCode.SESSION_NOT_BELONG_TO_USER.getCode() == result.getCode()) {
                throw new ServiceException("会话不属于当前用户", ResultCode.SESSION_NOT_BELONG_TO_USER.getCode());
            }
            throw new ServiceException("删除会话失败");
        }
    }

    /**
     * 从当前 HTTP 请求的 JWT 中提取 userId
     *
     * @return 当前登录用户ID
     * @throws ServiceException 如果 Token 无效或已过期
     */
    private Long getCurrentUserId() {
        LoginUserDTO loginUser = tokenService.getLoginUser(secret);
        if (loginUser == null || loginUser.getUserId() == null) {
            log.warn("用户令牌有误，无法获取 userId");
            throw new ServiceException("用户令牌有误", ResultCode.INVALID_PARA.getCode());
        }
        return loginUser.getUserId();
    }
}