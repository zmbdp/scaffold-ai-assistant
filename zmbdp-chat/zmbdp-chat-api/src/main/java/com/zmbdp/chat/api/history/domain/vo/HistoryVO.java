package com.zmbdp.chat.api.history.domain.vo;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * 对话历史列表项 VO
 * <p>
 * 用于 C 端历史列表展示，每个 sessionId 聚合为一条记录。
 *
 * @author 稚名不带撇
 */
@Data
public class HistoryVO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 会话ID
     */
    private String sessionId;

    /**
     * 最后一条消息（用户提问摘要）
     */
    private String lastMessage;

    /**
     * 最后消息时间（时间戳，毫秒）
     */
    private Long timestamp;

    /**
     * 使用的模型名称
     */
    private String model;

    /**
     * 该会话的消息数量
     */
    private Integer messageCount;
}
