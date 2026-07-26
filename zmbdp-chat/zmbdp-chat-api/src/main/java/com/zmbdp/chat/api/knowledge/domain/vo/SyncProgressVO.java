package com.zmbdp.chat.api.knowledge.domain.vo;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * 知识同步进度 VO
 * <p>
 * 存储在 Redis（key = {@code sync:progress:{taskId}}，TTL 24h），供前端轮询查询。
 * <p>
 * <b>进度阶段</b>：
 * <ol>
 *     <li>RUNNING：同步进行中，processedFiles 逐步递增</li>
 *     <li>COMPLETE：同步完成，finalResult 填充最终统计</li>
 *     <li>FAILED：同步异常终止，lastError 填充错误信息</li>
 *     <li>SKIPPED：因已有同步任务在执行，本次被跳过</li>
 * </ol>
 *
 * @author 稚名不带撇
 */
@Data
public class SyncProgressVO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 同步任务ID
     */
    private String taskId;

    /**
     * 任务状态：RUNNING / COMPLETE / FAILED / SKIPPED
     */
    private String status;

    /**
     * 同步开始时间（时间戳，毫秒）
     */
    private Long startTime;

    /**
     * 同步耗时（毫秒，完成或失败时填充）
     */
    private Long duration;

    /* ==================== 知识源级进度 ==================== */

    /**
     * 知识源总数
     */
    private Integer totalSources;

    /**
     * 当前正在同步的知识源序号（从 1 开始）
     */
    private Integer currentSourceIndex;

    /**
     * 当前正在同步的知识源名称
     */
    private String currentSourceName;

    /* ==================== 文件级进度 ==================== */

    /**
     * 待同步文件总数（预扫描阶段统计）
     */
    private Integer totalFiles;

    /**
     * 已处理文件数（成功 + 失败 + 跳过）
     */
    private Integer processedFiles;

    /**
     * 成功处理的文件数（新增 + 更新）
     */
    private Integer successFiles;

    /**
     * 处理失败的文件数
     */
    private Integer failedFiles;

    /**
     * 跳过的文件数（哈希未变，仅增量模式）
     */
    private Integer skippedFiles;

    /* ==================== 当前文件信息 ==================== */

    /**
     * 最近处理的文件名
     */
    private String lastFileName;

    /**
     * 最近的错误信息（失败时填充）
     */
    private String lastError;

    /* ==================== 最终结果 ==================== */

    /**
     * 同步完成后的最终统计（status=COMPLETE 时填充）
     */
    private SyncResultVO finalResult;
}
