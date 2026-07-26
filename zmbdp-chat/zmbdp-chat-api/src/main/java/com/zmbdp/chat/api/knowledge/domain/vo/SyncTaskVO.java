package com.zmbdp.chat.api.knowledge.domain.vo;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * 知识同步任务提交结果 VO
 * <p>
 * 触发同步接口（{@code POST /knowledge/sync}）的返回值。
 * 接口仅做"提交"动作（生成 taskId + 投递 MQ），同步流程在 chat-service 后台异步执行，
 * 前端使用 {@link #taskId} 调用 {@code GET /knowledge/sync/progress/{taskId}} 轮询进度。
 * <p>
 * <b>status 取值</b>：
 * <ul>
 *     <li>{@code RUNNING}：新任务已提交，同步流程即将开始（或已在执行）</li>
 *     <li>{@code SKIPPED}：因已有同步任务在执行，本次提交被跳过；此时 {@link #taskId} 为当前正在执行的任务ID，
 *         前端应使用该 taskId 轮询进度，而不是重新触发同步</li>
 * </ul>
 *
 * @author 稚名不带撇
 */
@Data
public class SyncTaskVO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 同步任务ID（UUID）
     * <p>
     * status=RUNNING 时为本次生成的新 taskId；status=SKIPPED 时为当前正在执行的任务ID。
     * 前端使用此值调用 {@code GET /knowledge/sync/progress/{taskId}} 轮询进度。
     */
    private String taskId;

    /**
     * 提交状态：RUNNING（已提交）/ SKIPPED（被跳过，已有任务在执行）
     */
    private String status;

    /**
     * 提示信息（用于前端直接展示）
     */
    private String message;
}
