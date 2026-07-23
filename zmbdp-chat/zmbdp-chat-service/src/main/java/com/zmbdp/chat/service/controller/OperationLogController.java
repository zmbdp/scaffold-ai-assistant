package com.zmbdp.chat.service.controller;

import com.zmbdp.chat.api.operationlog.domain.vo.OperationLogVO;
import com.zmbdp.chat.api.operationlog.feign.OperationLogApi;
import com.zmbdp.chat.service.domain.entity.SysAiOperationLog;
import com.zmbdp.chat.service.service.IAdminService;
import com.zmbdp.common.core.utils.BeanCopyUtil;
import com.zmbdp.common.domain.domain.Result;
import com.zmbdp.common.domain.domain.vo.BasePageVO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;

/**
 * AI 调用链路日志 Controller（chat-service 端）
 * <p>
 * 实现 {@link OperationLogApi} Feign 接口，查询 sys_ai_operation_log 表的 AI 调用链路日志
 * <p>
 * <b>职责边界</b>：本接口仅查询 AI 调用链路日志（sys_ai_operation_log 表），
 * B 端管理操作审计由脚手架 @LogAction 注解自动记录到 operation_log 表，不在本接口查询范围。
 * <p>
 * <b>列表页</b>：仅返回摘要信息（不含完整 prompt/response/toolCalls，避免响应过大）；
 * <b>详情页</b>：通过 StatisticsApi.getOperationDetail() 查看单次调用完整链路。
 *
 * @author 稚名不带撇
 */
@Slf4j
@RestController
@RequestMapping("/operation-log")
public class OperationLogController implements OperationLogApi {

    /**
     * B端管理服务（AI 业务相关）
     */
    @Autowired
    private IAdminService adminService;

    /**
     * 获取 AI 调用链路日志列表（分页）
     * <p>
     * 列表页仅返回摘要信息（不含完整 prompt/response/toolCalls，避免响应过大）。
     * 支持按操作类型、模型、状态、日期范围过滤（均为可选条件，为空时不加过滤）。
     *
     * @param pageNo        页码
     * @param pageSize      每页数量
     * @param operationType AI 操作类型过滤（可选）
     * @param model         模型名称过滤（可选）
     * @param status        调用状态过滤（可选）
     * @param startDate     起始日期（YYYYMMDD 格式 Long 值，可选）
     * @param endDate       结束日期（YYYYMMDD 格式 Long 值，可选）
     * @return 操作日志分页结果
     */
    @Override
    public Result<BasePageVO<OperationLogVO>> getLogs(Integer pageNo, Integer pageSize, String operationType,
                                                       String model, String status, Long startDate, Long endDate) {
        log.info("获取 AI 调用链路日志列表：pageNo = {}, pageSize = {}, operationType = {}, model = {}, status = {}, startDate = {}, endDate = {}",
                pageNo, pageSize, operationType, model, status, startDate, endDate);
        // 委托给 IAdminService（支持操作类型/模型/状态/日期范围过滤）
        BasePageVO<SysAiOperationLog> page = adminService.listOperationLogs(pageNo, pageSize, operationType,
                model, status, startDate, endDate);
        // 转换为 OperationLogVO 列表（列表页仅返回摘要，不含 prompt/response/toolCalls）
        BasePageVO<OperationLogVO> result = convertToOperationLogVOPage(page);
        return Result.success(result);
    }

    /*=============================================    私有方法    =============================================*/

    /**
     * 将 SysAiOperationLog 分页结果转换为 OperationLogVO 分页结果
     * <p>
     * 列表页仅返回摘要字段（不含 prompt/response/toolCalls，避免响应过大），
     * 完整链路详情通过 StatisticsApi.getOperationDetail() 查看。
     *
     * @param page SysAiOperationLog 分页结果
     * @return OperationLogVO 分页结果
     */
    private BasePageVO<OperationLogVO> convertToOperationLogVOPage(BasePageVO<SysAiOperationLog> page) {
        BasePageVO<OperationLogVO> result = new BasePageVO<>();
        if (page == null) {
            result.setTotals(0);
            result.setTotalPages(0);
            result.setList(new ArrayList<>());
            return result;
        }
        result.setTotals(page.getTotals());
        result.setTotalPages(page.getTotalPages());
        List<SysAiOperationLog> records = page.getList();
        if (records == null || records.isEmpty()) {
            result.setList(new ArrayList<>());
            return result;
        }
        List<OperationLogVO> voList = new ArrayList<>(records.size());
        for (SysAiOperationLog record : records) {
            OperationLogVO vo = new OperationLogVO();
            BeanCopyUtil.copyProperties(record, vo);
            // 列表页清空大字段（prompt/response/toolCalls），避免响应过大
            vo.setPrompt(null);
            vo.setResponse(null);
            vo.setToolCalls(null);
            voList.add(vo);
        }
        result.setList(voList);
        return result;
    }
}