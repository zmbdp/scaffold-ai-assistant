package com.zmbdp.portal.service.usage.service.impl;

import com.zmbdp.chat.api.statistics.domain.vo.UsageItemVO;
import com.zmbdp.chat.api.statistics.domain.vo.UsageSummaryVO;
import com.zmbdp.chat.api.statistics.feign.StatisticsApi;
import com.zmbdp.common.domain.domain.Result;
import com.zmbdp.common.domain.domain.ResultCode;
import com.zmbdp.common.domain.exception.ServiceException;
import com.zmbdp.common.domain.domain.vo.BasePageVO;
import com.zmbdp.common.security.domain.dto.LoginUserDTO;
import com.zmbdp.common.security.service.TokenService;
import com.zmbdp.portal.service.usage.service.IUserUsageService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * C端用户用量业务编排服务实现
 * <p>
 * 通过 Feign 调用 chat-service 的 {@code StatisticsApi}，为 C端用户提供 AI 调用用量汇总和明细查询能力。
 * <p>
 * <b>userId 提取</b>：每次请求通过 {@code tokenService.getLoginUser(secret)} 从当前 HTTP 请求
 * 的 JWT 中解析 userId，传给 chat-service 进行数据过滤，确保 C端用户只能查看自己的用量数据。
 *
 * @author 稚名不带撇
 */
@Slf4j
@Service
public class UserUsageServiceImpl implements IUserUsageService {

    /**
     * 默认页码
     */
    private static final int DEFAULT_PAGE_NO = 1;

    /**
     * 默认每页数量
     */
    private static final int DEFAULT_PAGE_SIZE = 10;

    /**
     * 统计服务 Feign 接口
     */
    @Autowired
    private StatisticsApi statisticsApi;

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
     * 获取当前登录用户的用量汇总
     *
     * @return 用户用量汇总 VO
     */
    @Override
    public UsageSummaryVO getUsageSummary() {
        Long userId = getCurrentUserId();
        log.info("查询用户用量汇总：userId = {}", userId);
        Result<UsageSummaryVO> result = statisticsApi.getUserUsageSummary(userId);
        if (result == null || result.getCode() != ResultCode.SUCCESS.getCode()) {
            log.warn("查询用户用量汇总失败：userId = {}, code = {}, msg = {}",
                    userId, result == null ? null : result.getCode(), result == null ? null : result.getErrMsg());
            throw new ServiceException("查询用户用量汇总失败");
        }
        return result.getData();
    }

    /**
     * 分页获取当前登录用户的 AI 调用明细
     *
     * @param pageNo   页码，默认 1
     * @param pageSize 每页数量，默认 10
     * @return 用量明细分页结果
     */
    @Override
    public BasePageVO<UsageItemVO> getUsageList(Integer pageNo, Integer pageSize) {
        Long userId = getCurrentUserId();
        int finalPageNo = pageNo != null && pageNo > 0 ? pageNo : DEFAULT_PAGE_NO;
        int finalPageSize = pageSize != null && pageSize > 0 ? pageSize : DEFAULT_PAGE_SIZE;
        log.info("查询用户用量明细：userId = {}, pageNo = {}, pageSize = {}", userId, finalPageNo, finalPageSize);
        Result<BasePageVO<UsageItemVO>> result = statisticsApi.getUserUsageList(userId, finalPageNo, finalPageSize);
        if (result == null || result.getCode() != ResultCode.SUCCESS.getCode()) {
            log.warn("查询用户用量明细失败：userId = {}, code = {}, msg = {}",
                    userId, result == null ? null : result.getCode(), result == null ? null : result.getErrMsg());
            throw new ServiceException("查询用户用量明细失败");
        }
        return result.getData();
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