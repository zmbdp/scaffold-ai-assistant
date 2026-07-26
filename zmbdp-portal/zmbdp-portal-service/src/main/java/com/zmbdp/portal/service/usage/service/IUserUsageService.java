package com.zmbdp.portal.service.usage.service;

import com.zmbdp.chat.api.statistics.domain.vo.UsageItemVO;
import com.zmbdp.chat.api.statistics.domain.vo.UsageSummaryVO;
import com.zmbdp.common.domain.domain.vo.BasePageVO;

/**
 * C端用户用量业务编排服务
 * <p>
 * portal-service 通过 Feign 调用 chat-service 的 {@code StatisticsApi}，
 * 为 C端用户提供 AI 调用用量汇总和明细查询能力。
 * <p>
 * <b>userId 来源</b>：由 portal-service 从 JWT 解析后传递给 chat-service，
 * C端用户只能查看自己的用量数据（chat-service 根据 userId 过滤）。
 * <p>
 * <b>越权防护</b>：禁止前端直接传 userId，一律从 JWT 提取，否则用户改请求参数就能查别人数据。
 *
 * @author 稚名不带撇
 */
public interface IUserUsageService {

    /**
     * 获取当前登录用户的用量汇总
     * <p>
     * 返回累计/今日 Token 消耗、对话数、活跃天数、首次使用时间、近 7 天 Token 趋势。
     * 内部从 JWT 提取 userId，传给 chat-service 进行聚合查询。
     *
     * @return 用户用量汇总 VO
     */
    UsageSummaryVO getUsageSummary();

    /**
     * 分页获取当前登录用户的 AI 调用明细
     * <p>
     * 按 create_time 倒序分页返回该用户的 AI 调用记录。
     * 内部从 JWT 提取 userId，传给 chat-service 进行分页查询。
     *
     * @param pageNo   页码，默认 1
     * @param pageSize 每页数量，默认 10
     * @return 用量明细分页结果
     */
    BasePageVO<UsageItemVO> getUsageList(Integer pageNo, Integer pageSize);
}