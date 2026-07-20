package com.zmbdp.admin.service.ai.controller;

import com.zmbdp.chat.api.operationlog.domain.vo.OperationLogVO;
import com.zmbdp.common.domain.domain.Result;
import com.zmbdp.common.domain.domain.vo.BasePageVO;
import com.zmbdp.admin.service.ai.service.IAiAdminService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * B端 AI 操作日志 Controller
 * <p>
 * 作为B端 AI 调用链路日志查询的统一入口，通过 Feign 调用 chat-service 的 {@code OperationLogApi}。
 * <p>
 * <b>接口路径</b>：
 * <ul>
 *     <li>{@code GET /knowledge/logs/list}：获取 AI 调用链路日志列表（分页）</li>
 * </ul>
 * <p>
 * <b>网关路径映射</b>：前端请求 {@code /admin/knowledge/logs/list} → gateway StripPrefix=1 → 本 Controller {@code /knowledge/logs/list}
 * <p>
 * <b>职责边界</b>：
 * <ul>
 *     <li>本接口查询 AI 调用链路日志（sys_ai_operation_log 表，AI 编排层手动埋点）</li>
 *     <li>B端管理操作审计（知识源 CRUD、配置修改等）由 {@code @LogAction} 注解自动记录到 operation_log 表，
 *         不在本表查询范围</li>
 * </ul>
 * <p>
 * <b>路径说明</b>：本 Controller 路径为 {@code /knowledge/logs}（而非 {@code /operation-log}），
 * 是因为B端管理界面将 AI 操作日志查询入口放在知识库管理模块下。
 * 底层仍通过 Feign 调用 chat-service 的 {@code /operation-log/list} 接口。
 *
 * @author 稚名不带撇
 */
@Slf4j
@RestController
@RequestMapping("/knowledge/logs")
public class LogController {

    /**
     * B端 AI 管理业务编排服务
     */
    @Autowired
    private IAiAdminService aiAdminService;

    /**
     * 获取 AI 调用链路日志列表（分页）
     * <p>
     * 列表页仅返回摘要信息（不含完整 prompt/response，避免响应过大）。
     * 完整链路详情通过 {@code StatisticsController.getOperationDetail()} 查看。
     *
     * @param pageNo        页码，默认 1
     * @param pageSize      每页数量，默认 20
     * @param operationType AI 操作类型过滤（CHAT/RETRIEVE/EMBEDDING/RERANK，可选）
     * @param model         模型名称过滤（如 deepseek-v4-flash，可选）
     * @param status        调用状态过滤（SUCCESS/FAILED/TIMEOUT，可选）
     * @param startDate     开始日期（格式：20260712，可选）
     * @param endDate       结束日期（格式：20260712，可选）
     * @return 操作日志分页结果
     */
    @GetMapping("/list")
    public Result<BasePageVO<OperationLogVO>> listLogs(
            @RequestParam(value = "pageNo", defaultValue = "1") Integer pageNo,
            @RequestParam(value = "pageSize", defaultValue = "20") Integer pageSize,
            @RequestParam(value = "operationType", required = false) String operationType,
            @RequestParam(value = "model", required = false) String model,
            @RequestParam(value = "status", required = false) String status,
            @RequestParam(value = "startDate", required = false) Long startDate,
            @RequestParam(value = "endDate", required = false) Long endDate) {
        return Result.success(aiAdminService.listOperationLogs(pageNo, pageSize, operationType,
                model, status, startDate, endDate));
    }
}