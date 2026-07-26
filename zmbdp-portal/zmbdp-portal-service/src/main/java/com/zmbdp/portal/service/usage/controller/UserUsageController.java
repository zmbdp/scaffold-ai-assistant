package com.zmbdp.portal.service.usage.controller;

import com.zmbdp.chat.api.statistics.domain.vo.UsageItemVO;
import com.zmbdp.chat.api.statistics.domain.vo.UsageSummaryVO;
import com.zmbdp.common.domain.domain.Result;
import com.zmbdp.common.domain.domain.vo.BasePageVO;
import com.zmbdp.portal.service.usage.service.IUserUsageService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * C端用户用量入口 Controller
 * <p>
 * 作为 C端用户用量查询的统一入口，接收前端请求后委托给 {@link IUserUsageService}
 * 通过 Feign 调用 chat-service 的 StatisticsApi。
 * <p>
 * <b>接口路径</b>：
 * <ul>
 *     <li>{@code GET /user/usage/summary}：用量汇总（仪表盘 + 用量管理页顶部卡片）</li>
 *     <li>{@code GET /user/usage/list}：用量明细分页（用量管理页表格）</li>
 * </ul>
 * <p>
 * <b>网关路径映射</b>：前端请求 {@code /portal/user/usage/*} → gateway StripPrefix=1 → 本 Controller {@code /user/usage/*}
 * <p>
 * <b>认证</b>：需要 C端 JWT（{@code userFrom=app}），Service 内部从 JWT 提取 userId 传给 chat-service。
 * <p>
 * <b>越权防护</b>：禁止前端直接传 userId，一律从 JWT 提取，否则用户改请求参数就能查别人数据。
 *
 * @author 稚名不带撇
 */
@Slf4j
@RestController
@RequestMapping("/user/usage")
public class UserUsageController {

    /**
     * C端用户用量业务编排服务
     */
    @Autowired
    private IUserUsageService userUsageService;

    /**
     * 获取当前登录用户的用量汇总
     * <p>
     * 返回累计/今日 Token 消耗、对话数、活跃天数、首次使用时间、近 7 天 Token 趋势。
     * 用于 C端仪表盘 + 用量管理页顶部卡片展示。
     * <p>
     * <b>认证</b>：需要 C端 JWT，userId 从 JWT 解析，前端无需传任何参数。
     *
     * @return 用户用量汇总 VO
     */
    @GetMapping("/summary")
    public Result<UsageSummaryVO> getUsageSummary() {
        return Result.success(userUsageService.getUsageSummary());
    }

    /**
     * 分页获取当前登录用户的 AI 调用明细
     * <p>
     * 按 create_time 倒序分页返回该用户的 AI 调用记录，用于 C端用量管理页表格展示。
     * <p>
     * <b>认证</b>：需要 C端 JWT，userId 从 JWT 解析，前端无需传 userId。
     *
     * @param pageNo   页码，默认 1
     * @param pageSize 每页数量，默认 10
     * @return 用量明细分页结果
     */
    @GetMapping("/list")
    public Result<BasePageVO<UsageItemVO>> getUsageList(
            @RequestParam(value = "pageNo", defaultValue = "1") Integer pageNo,
            @RequestParam(value = "pageSize", defaultValue = "10") Integer pageSize) {
        return Result.success(userUsageService.getUsageList(pageNo, pageSize));
    }
}