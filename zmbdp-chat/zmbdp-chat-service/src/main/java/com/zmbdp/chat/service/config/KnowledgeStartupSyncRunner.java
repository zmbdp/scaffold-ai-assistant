package com.zmbdp.chat.service.config;

import com.zmbdp.chat.api.knowledge.domain.vo.SyncResultVO;
import com.zmbdp.chat.service.service.IKnowledgeLoaderService;
import com.zmbdp.chat.service.service.impl.VectorStoreServiceImpl;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.concurrent.CompletableFuture;

/**
 * 知识库启动同步 Runner
 * <p>
 * 在 chat-service 启动完成后自动触发一次知识同步，避免向量库为空导致 AI 对话时检索不到任何上下文。
 * <p>
 * <b>触发时机</b>：Spring 容器初始化完成（所有 Bean 注入完毕，Milvus 集合已创建）后由
 * {@link ApplicationRunner} 回调触发。{@link Order} 设置为较靠后的值，确保在
 * {@link VectorStoreServiceImpl} 的 {@code @PostConstruct}（Milvus 集合初始化）之后执行。
 * <p>
 * <b>异步执行</b>：同步过程可能耗时较长（首次同步需要 Embedding 向量化大量文件），
 * 因此使用 {@link CompletableFuture#runAsync} 异步执行，不阻塞服务启动。
 * <p>
 * <b>多实例安全</b>：{@link IKnowledgeLoaderService#syncKnowledge} 内部已按知识源粒度
 * 加 Redisson 分布式锁（key: {@code knowledge:sync:{knowledgeSourceId}}），多实例同时启动时
 * 也只有一个实例会执行同一知识源的同步，其他实例直接跳过，无需在此处额外加锁。
 * <p>
 * <b>同步模式</b>：默认增量同步（force=false），仅处理新增/修改/删除的文件；
 * 如需全量重新向量化，可通过 Nacos 配置 {@code knowledge.startup-sync.force=true} 开启。
 * <p>
 * <b>异常隔离</b>：同步失败不影响服务启动，仅记录错误日志。后续可通过 XXL-JOB
 * {@code knowledgeSyncJob} 或 B 端"知识同步"接口手动重试。
 *
 * @author 稚名不带撇
 */
@Slf4j
@Component
@Order(100)
public class KnowledgeStartupSyncRunner implements ApplicationRunner {

    /**
     * 知识加载服务（执行 12 步同步流程，内部含 Redisson 分布式锁）
     */
    @Autowired
    private IKnowledgeLoaderService knowledgeLoaderService;

    /**
     * 是否启用启动时知识同步（从 Nacos {@code knowledge.startup-sync.enabled} 读取，默认 true）
     * <p>
     * 关闭场景：调试启动速度、临时排查问题、或向量库已由其他实例同步完成时。
     */
    @Value("${knowledge.startup-sync.enabled:true}")
    private boolean startupSyncEnabled;

    /**
     * 启动同步是否强制全量（从 Nacos {@code knowledge.startup-sync.force} 读取，默认 false）
     * <p>
     * <b>false（推荐）</b>：增量同步，通过 SHA-256 哈希对比仅处理变更文件，启动快。
     * <b>true</b>：全量同步，跳过哈希检查重新向量化所有文档，适用于 Embedding 模型切换后重建向量库。
     */
    @Value("${knowledge.startup-sync.force:false}")
    private boolean startupSyncForce;

    /**
     * 启动同步前的等待时间（毫秒，从 Nacos {@code knowledge.startup-sync.delay-ms} 读取，默认 3000）
     * <p>
     * 等待 MilvusServiceClient 完成 loadCollection、Embedding 模型懒加载等异步初始化动作，
     * 避免首次调用 Embedding 接口时因 SDK 未就绪而失败。设为 0 表示不等待。
     */
    @Value("${knowledge.startup-sync.delay-ms:3000}")
    private long startupSyncDelayMs;

    /**
     * 启动完成后触发知识同步
     * <p>
     * 异步执行，主线程立即返回不阻塞启动。同步过程通过 {@link IKnowledgeLoaderService#syncKnowledge}
     * 内部的 Redisson 分布式锁保证多实例安全。
     *
     * @param args 启动参数（未使用）
     */
    @Override
    public void run(ApplicationArguments args) {
        if (!startupSyncEnabled) {
            log.info("启动时知识同步已禁用（knowledge.startup-sync.enabled=false），跳过");
            return;
        }
        log.info("启动时知识同步已启用：force = {}, delayMs = {}", startupSyncForce, startupSyncDelayMs);
        CompletableFuture.runAsync(() -> {
            try {
                if (startupSyncDelayMs > 0) {
                    Thread.sleep(startupSyncDelayMs);
                }
                log.info("启动时知识同步开始：force = {}", startupSyncForce);
                SyncResultVO result = knowledgeLoaderService.syncKnowledge(startupSyncForce);
                log.info("启动时知识同步完成：total = {}, updated = {}, deleted = {}, skipped = {}, failed = {}, duration = {}ms",
                        result.getTotalDocuments(), result.getUpdatedDocuments(),
                        result.getDeletedDocuments(), result.getSkippedDocuments(),
                        result.getFailedDocuments(), result.getDuration());
                if (result.getFailedDocuments() > 0) {
                    log.warn("启动时知识同步存在失败文件，建议通过 XXL-JOB knowledgeSyncJob 手动重试或查看 chat-service 日志定位失败原因");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.warn("启动时知识同步被中断");
            } catch (Exception e) {
                log.error("启动时知识同步失败，可通过 XXL-JOB knowledgeSyncJob 手动重试", e);
            }
        });
    }
}
