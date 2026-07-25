package com.zmbdp.chat.service.mq;

import com.zmbdp.chat.api.knowledge.constant.KnowledgeSyncMQConstants;
import com.zmbdp.chat.api.knowledge.domain.dto.KnowledgeSyncMessage;
import com.zmbdp.chat.api.knowledge.domain.vo.SyncResultVO;
import com.zmbdp.chat.service.service.IKnowledgeLoaderService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.ExchangeTypes;
import org.springframework.amqp.rabbit.annotation.Exchange;
import org.springframework.amqp.rabbit.annotation.Queue;
import org.springframework.amqp.rabbit.annotation.QueueBinding;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * 知识同步 MQ 消费者
 * <p>
 * 消费 admin-service 发送的知识同步消息，异步执行知识同步流程。
 * <p>
 * <b>设计说明</b>：
 * <ul>
 *     <li>使用 Direct 交换机 + 命名队列（持久化），多实例竞争消费，消息只被一个 chat-service 处理</li>
 *     <li>同步流程内部已有 Redisson 分布式锁（按知识源粒度），即使消息被重复消费也不会导致数据不一致</li>
 *     <li>消费失败时 MQ 会自动重试（默认 3 次），重试仍失败则消息进入死信队列（如有配置）</li>
 * </ul>
 *
 * @author 稚名不带撇
 */
@Slf4j
@Component
public class KnowledgeSyncConsumer {

    /**
     * 知识加载服务（执行 12 步同步流程，内部含 Redisson 分布式锁）
     */
    @Autowired
    private IKnowledgeLoaderService knowledgeLoaderService;

    /**
     * 消费知识同步消息
     * <p>
     * 解析消息中的 taskId、sourceType 和 force 参数，调用 {@link IKnowledgeLoaderService#syncKnowledge}
     * 执行异步同步。同步过程中向 Redis 写入进度数据（key = sync:progress:{taskId}），
     * 供前端通过 GET /knowledge/sync/progress/{taskId} 轮询查询。
     * <p>
     * <b>单任务约束</b>：syncKnowledge 内部通过全局锁保证同时只有一个同步任务执行，
     * 获取锁失败时返回 null 并写 SKIPPED 状态到 Redis。
     *
     * @param message 知识同步消息（含 taskId、sourceType、force 参数）
     */
    @RabbitListener(bindings = @QueueBinding(
            exchange = @Exchange(value = KnowledgeSyncMQConstants.EXCHANGE, type = ExchangeTypes.DIRECT),
            value = @Queue(value = KnowledgeSyncMQConstants.QUEUE, durable = "true"),
            key = KnowledgeSyncMQConstants.ROUTING_KEY
    ))
    public void handleKnowledgeSync(KnowledgeSyncMessage message) {
        String taskId = message != null ? message.getTaskId() : null;
        String sourceType = message != null ? message.getSourceType() : null;
        boolean force = message != null && Boolean.TRUE.equals(message.getForce());
        log.info("[MQ] 收到知识同步消息：taskId = {}, sourceType = {}, force = {}", taskId, sourceType, force);
        try {
            SyncResultVO result = knowledgeLoaderService.syncKnowledge(sourceType, force, taskId);
            if (result == null) {
                log.warn("[MQ] 知识同步被跳过（已有任务在执行）：taskId = {}", taskId);
                return;
            }
            log.info("[MQ] 知识同步完成：taskId = {}, total = {}, updated = {}, deleted = {}, skipped = {}, failed = {}, duration = {}ms",
                    taskId, result.getTotalDocuments(), result.getUpdatedDocuments(),
                    result.getDeletedDocuments(), result.getSkippedDocuments(),
                    result.getFailedDocuments(), result.getDuration());
            if (result.getFailedDocuments() > 0) {
                log.warn("[MQ] 知识同步存在失败文件，建议查看 chat-service 日志定位失败原因");
            }
        } catch (Exception e) {
            log.error("[MQ] 知识同步失败：taskId = {}, sourceType = {}, force = {}", taskId, sourceType, force, e);
            // 抛出异常让 MQ 重试
            throw new RuntimeException("知识同步失败", e);
        }
    }
}
