package com.zmbdp.chat.api.history.domain.vo;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.util.List;

/**
 * 会话详情 VO
 * <p>
 * 用于 C 端会话详情展示，含该会话下的所有消息（按时间正序）。
 *
 * @author 稚名不带撇
 */
@Data
public class HistoryDetailVO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 会话ID
     */
    private String sessionId;

    /**
     * 消息列表（按时间正序）
     */
    private List<Message> messages;

    /**
     * 单条消息
     */
    @Data
    public static class Message implements Serializable {

        @Serial
        private static final long serialVersionUID = 1L;

        /**
         * 角色（user/assistant）
         */
        private String role;

        /**
         * 消息内容
         */
        private String content;

        /**
         * 时间戳（毫秒）
         */
        private Long timestamp;

        /**
         * 是否含图片
         */
        private Boolean hasImage;

        /**
         * 引用来源（仅 assistant 消息，RAG 检索命中的文档列表）
         */
        private List<String> sources;

        /**
         * 模型名称（仅 assistant 消息）
         */
        private String model;
    }
}