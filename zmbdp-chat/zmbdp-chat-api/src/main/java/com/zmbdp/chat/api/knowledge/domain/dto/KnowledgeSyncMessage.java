package com.zmbdp.chat.api.knowledge.domain.dto;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * 知识同步 MQ 消息体
 * <p>
 * admin-service 发送 MQ 消息时使用，chat-service 消费端接收后解析 sourceType 和 force 参数，
 * 调用 {@code IKnowledgeLoaderService.syncKnowledge(sourceType, force)} 执行异步同步。
 *
 * @author 稚名不带撇
 */
@Data
public class KnowledgeSyncMessage implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 知识源类型过滤（doc/javadoc/config/code，null 或 "all" 表示全部）
     */
    private String sourceType;

    /**
     * 是否强制全量同步（true=全量跳过哈希检查，false=增量）
     */
    private Boolean force;
}
