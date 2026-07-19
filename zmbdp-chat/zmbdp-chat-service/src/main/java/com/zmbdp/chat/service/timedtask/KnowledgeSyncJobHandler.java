package com.zmbdp.chat.service.timedtask;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.xxl.job.core.context.XxlJobHelper;
import com.xxl.job.core.handler.annotation.XxlJob;
import com.zmbdp.chat.api.knowledge.domain.vo.SyncResultVO;
import com.zmbdp.chat.service.domain.entity.SysAiConversation;
import com.zmbdp.chat.service.domain.entity.SysAiOperationLog;
import com.zmbdp.chat.service.mapper.SysAiConversationMapper;
import com.zmbdp.chat.service.mapper.SysAiOperationLogMapper;
import com.zmbdp.chat.service.service.IKnowledgeLoaderService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/**
 * 知识同步与数据清理 XXL-JOB Handler
 *
 * <p>包含 3 个 Handler，由 XXL-JOB 调度中心统一管理，支持运行时调整 CRON、手动触发、
 * 失败重试、执行日志可视化查看。
 *
 * <p>Handler 列表：
 * <ul>
 *   <li>{@link #knowledgeSyncJob}：知识同步（默认每日凌晨 2:00 增量同步所有知识源）</li>
 *   <li>{@link #cleanExpiredHistoryJob}：清理过期对话历史（默认每日凌晨 3:00）</li>
 *   <li>{@link #cleanExpiredLogsJob}：清理过期 AI 调用链路日志（默认每日凌晨 4:00）</li>
 * </ul>
 *
 * <p><b>多实例部署说明</b>：
 * <ul>
 *   <li>知识同步任务：{@link IKnowledgeLoaderService#syncKnowledge} 内部已按知识源粒度
 *   加 Redisson 分布式锁，多实例并发触发时也只有一个实例执行同一知识源的同步，
 *   因此本 Handler 不再加额外锁，配合 XXL-JOB 路由策略 FIRST 即可。</li>
 *   <li>清理任务：物理删除操作，多实例并发执行不会产生数据问题（第二次删除返回 0 行），
 *   配合 XXL-JOB 路由策略 FIRST 从框架层面保证只执行一次。</li>
 * </ul>
 *
 * <p><b>参数支持</b>（通过 XXL-JOB 管控台「任务参数」传入，覆盖默认值）：
 * <ul>
 *   <li>知识同步：参数为 {@code true} 表示强制全量同步，{@code false} 或空表示增量同步</li>
 *   <li>清理对话历史：参数为保留天数（整数，默认 30）</li>
 *   <li>清理操作日志：参数为保留天数（整数，默认 90）</li>
 * </ul>
 *
 * <p><b>XXL-JOB 调度中心配置参考</b>：
 * <pre>
 * ┌─────────────────────┬───────────────────────────┬─────────────────┬──────────────┐
 * │ JobHandler          │ schedule_conf             │ executor_route  │ 任务参数     │
 * ├─────────────────────┼───────────────────────────┼─────────────────┼──────────────┤
 * │ knowledgeSyncJob    │ 0 0 2 * * ?               │ FIRST           │ false        │
 * │ cleanExpiredHistoryJob│ 0 0 3 * * ?             │ FIRST           │ 30           │
 * │ cleanExpiredLogsJob │ 0 0 4 * * ?               │ FIRST           │ 90           │
 * └─────────────────────┴───────────────────────────┴─────────────────┴──────────────┘
 * 阻塞处理策略：SERIAL_EXECUTION（串行）
 * 失败重试次数：1
 * </pre>
 *
 * @author 稚名不带撇
 */
@Slf4j
@Component
public class KnowledgeSyncJobHandler {

    /**
     * 对话历史默认保留天数（30 天，与设计文档 7.9.2 节一致）
     */
    private static final int DEFAULT_HISTORY_RETENTION_DAYS = 30;

    /**
     * 操作日志默认保留天数（90 天，与设计文档 7.9.2 节一致）
     */
    private static final int DEFAULT_LOG_RETENTION_DAYS = 90;

    /**
     * 知识加载服务（执行 12 步同步流程，内部含 Redisson 分布式锁）
     */
    @Autowired
    private IKnowledgeLoaderService knowledgeLoaderService;

    /**
     * 对话记录 mapper（清理过期对话历史）
     */
    @Autowired
    private SysAiConversationMapper sysAiConversationMapper;

    /**
     * AI 调用链路日志 mapper（清理过期操作日志）
     */
    @Autowired
    private SysAiOperationLogMapper sysAiOperationLogMapper;

    /**
     * 知识同步 XXL-JOB Handler
     *
     * <p>由调度中心按 CRON 触发，也可在管控台手动触发，支持失败自动重试。
     *
     * <p><b>任务参数</b>：{@code true}=强制全量同步，{@code false} 或空=增量同步（默认）
     *
     * <p><b>分布式锁</b>：{@link IKnowledgeLoaderService#syncKnowledge} 内部已按知识源
     * 粒度加 Redisson 分布式锁，多实例并发触发时也只有一个实例执行同一知识源的同步，
     * 因此本 Handler 不再加额外锁。
     */
    @XxlJob("knowledgeSyncJob")
    public void knowledgeSyncJob() {
        // 解析任务参数：true=强制全量同步，false/空=增量同步
        String param = XxlJobHelper.getJobParam();
        boolean force = "true".equalsIgnoreCase(param);

        XxlJobHelper.log("[XXL-JOB] 知识同步开始：force={}", force);
        log.info("[XXL-JOB] 知识同步开始：force={}", force);

        try {
            SyncResultVO result = knowledgeLoaderService.syncKnowledge(force);
            String successMsg = String.format(
                    "[XXL-JOB] 知识同步完成：total=%d, updated=%d, deleted=%d, skipped=%d, failed=%d, duration=%dms",
                    result.getTotalDocuments(), result.getUpdatedDocuments(),
                    result.getDeletedDocuments(), result.getSkippedDocuments(),
                    result.getFailedDocuments(), result.getDuration());
            XxlJobHelper.log(successMsg);
            log.info(successMsg);
            XxlJobHelper.handleSuccess(successMsg);
        } catch (Exception e) {
            String errMsg = "[XXL-JOB] 知识同步失败";
            XxlJobHelper.log(errMsg + ": " + e.getMessage());
            log.error(errMsg, e);
            XxlJobHelper.handleFail(errMsg);
        }
    }

    /**
     * 清理过期对话历史 XXL-JOB Handler
     *
     * <p>物理删除 {@code create_time} 早于 N 天前的对话记录（含已软删除的记录），
     * 避免历史软删除数据长期占用存储。
     *
     * <p><b>任务参数</b>：保留天数（整数），不传或传非整数时使用默认值 30。
     *
     * <p><b>注意</b>：本任务执行物理删除（{@code DELETE}），删除后数据不可恢复。
     */
    @XxlJob("cleanExpiredHistoryJob")
    public void cleanExpiredHistoryJob() {
        int retentionDays = parseRetentionDays(XxlJobHelper.getJobParam(), DEFAULT_HISTORY_RETENTION_DAYS);
        LocalDateTime cutoff = LocalDateTime.now().minusDays(retentionDays);

        XxlJobHelper.log("[XXL-JOB] 清理过期对话历史开始：retentionDays={}, cutoff={}", retentionDays, cutoff);
        log.info("[XXL-JOB] 清理过期对话历史开始：retentionDays={}, cutoff={}", retentionDays, cutoff);

        try {
            LambdaQueryWrapper<SysAiConversation> wrapper = new LambdaQueryWrapper<>();
            wrapper.lt(SysAiConversation::getCreateTime, cutoff);
            int deleted = sysAiConversationMapper.delete(wrapper);

            String successMsg = String.format("[XXL-JOB] 清理过期对话历史完成：deletedRows=%d", deleted);
            XxlJobHelper.log(successMsg);
            log.info(successMsg);
            XxlJobHelper.handleSuccess(successMsg);
        } catch (Exception e) {
            String errMsg = "[XXL-JOB] 清理过期对话历史失败";
            XxlJobHelper.log(errMsg + ": " + e.getMessage());
            log.error(errMsg, e);
            XxlJobHelper.handleFail(errMsg);
        }
    }

    /**
     * 清理过期 AI 调用链路日志 XXL-JOB Handler
     *
     * <p>物理删除 {@code create_time} 早于 N 天前的操作日志。
     *
     * <p><b>任务参数</b>：保留天数（整数），不传或传非整数时使用默认值 90。
     *
     * <p><b>注意</b>：操作日志仅用于 AI 调用链路追踪和效果评估，保留周期长于对话历史。
     */
    @XxlJob("cleanExpiredLogsJob")
    public void cleanExpiredLogsJob() {
        int retentionDays = parseRetentionDays(XxlJobHelper.getJobParam(), DEFAULT_LOG_RETENTION_DAYS);
        LocalDateTime cutoff = LocalDateTime.now().minusDays(retentionDays);

        XxlJobHelper.log("[XXL-JOB] 清理过期操作日志开始：retentionDays={}, cutoff={}", retentionDays, cutoff);
        log.info("[XXL-JOB] 清理过期操作日志开始：retentionDays={}, cutoff={}", retentionDays, cutoff);

        try {
            LambdaQueryWrapper<SysAiOperationLog> wrapper = new LambdaQueryWrapper<>();
            wrapper.lt(SysAiOperationLog::getCreateTime, cutoff);
            int deleted = sysAiOperationLogMapper.delete(wrapper);

            String successMsg = String.format("[XXL-JOB] 清理过期操作日志完成：deletedRows=%d", deleted);
            XxlJobHelper.log(successMsg);
            log.info(successMsg);
            XxlJobHelper.handleSuccess(successMsg);
        } catch (Exception e) {
            String errMsg = "[XXL-JOB] 清理过期操作日志失败";
            XxlJobHelper.log(errMsg + ": " + e.getMessage());
            log.error(errMsg, e);
            XxlJobHelper.handleFail(errMsg);
        }
    }

    /**
     * 解析保留天数参数
     *
     * @param param        XXL-JOB 任务参数
     * @param defaultDays  默认保留天数（参数无效时使用）
     * @return 解析后的保留天数（最小 1）
     */
    private int parseRetentionDays(String param, int defaultDays) {
        if (param == null || param.trim().isEmpty()) {
            return defaultDays;
        }
        try {
            int days = Integer.parseInt(param.trim());
            return days > 0 ? days : defaultDays;
        } catch (NumberFormatException e) {
            log.warn("[XXL-JOB] 保留天数参数解析失败，使用默认值：param={}, default={}", param, defaultDays);
            return defaultDays;
        }
    }
}
