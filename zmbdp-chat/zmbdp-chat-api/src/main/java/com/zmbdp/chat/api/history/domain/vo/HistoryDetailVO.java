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
         * 图片 URL 列表（仅 user 消息有效，图文对话时由 portal-service 上传 OSS 后透传）
         * <p>
         * 数据库 sys_ai_conversation.images 字段以 JSON 数组格式存储，此处反序列化为 List。
         * 纯文本对话或 assistant 消息该字段为 null。
         */
        private List<String> images;

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