package com.zmbdp.chat.api.operationlog.feign;

import com.zmbdp.chat.api.operationlog.domain.vo.OperationLogVO;
import com.zmbdp.common.domain.domain.Result;
import com.zmbdp.common.domain.domain.vo.BasePageVO;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * AI 调用链路日志远程调用 Api
 * <p>
 * 查询 sys_ai_operation_log 表的 AI 调用链路日志（列表页仅返回摘要信息，不含完整 prompt/response）。
 * <p>
 * <b>职责边界</b>：
 * <ul>
 *     <li>本接口查询 AI 调用链路日志（sys_ai_operation_log 表，AI 编排层手动埋点）</li>
 *     <li>B 端管理操作审计（知识源 CRUD、配置修改等）由脚手架 @LogAction 注解自动记录到 operation_log 表，
 *         不在本表查询范围</li>
 * </ul>
 * 完整链路详情（含 prompt/response/toolCalls）通过 StatisticsApi.getOperationDetail() 查看。
 *
 * @author 稚名不带撇
 */
@FeignClient(contextId = "operationLogApi", name = "zmbdp-chat-service", path = "/operation-log")
public interface OperationLogApi {

    /**
     * 获取 AI 调用链路日志列表（分页）
     * <p>
     * 列表页仅返回摘要信息（不含完整 prompt/response，避免响应过大）。
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
    Result<BasePageVO<OperationLogVO>> getLogs(@RequestParam(value = "pageNo", defaultValue = "1") Integer pageNo,
                                                @RequestParam(value = "pageSize", defaultValue = "20") Integer pageSize,
                                                @RequestParam(value = "operationType", required = false) String operationType,
                                                @RequestParam(value = "model", required = false) String model,
                                                @RequestParam(value = "status", required = false) String status,
                                                @RequestParam(value = "startDate", required = false) Long startDate,
                                                @RequestParam(value = "endDate", required = false) Long endDate);
}
