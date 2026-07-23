package com.zmbdp.chat.api.knowledge.constant;

/**
 * 知识同步 MQ 常量
 * <p>
 * 定义知识同步异步任务使用的 Direct 交换机、队列、路由键名称。
 * <p>
 * <b>设计说明</b>：
 * <ul>
 *     <li>使用 Direct 交换机（非 Fanout），因为知识同步任务只需一个 chat-service 实例执行，
 *         命名队列 + 竞争消费模式确保消息只被一个实例处理</li>
 *     <li>命名队列（非匿名队列）支持消息持久化，服务重启不丢任务</li>
 *     <li>常量定义在 chat-api 模块，admin-service（发送端）和 chat-service（消费端）共同引用</li>
 * </ul>
 *
 * @author 稚名不带撇
 */
public class KnowledgeSyncMQConstants {

    /**
     * 知识同步 Direct 交换机
     */
    public static final String EXCHANGE = "knowledge.sync.exchange";

    /**
     * 知识同步队列
     */
    public static final String QUEUE = "knowledge.sync.queue";

    /**
     * 知识同步路由键
     */
    public static final String ROUTING_KEY = "knowledge.sync";

    private KnowledgeSyncMQConstants() {
    }
}
