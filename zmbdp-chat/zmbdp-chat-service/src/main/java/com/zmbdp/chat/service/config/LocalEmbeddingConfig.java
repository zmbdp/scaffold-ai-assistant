package com.zmbdp.chat.service.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.ai.transformers.TransformersEmbeddingModel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;
import java.util.Map;

/**
 * 本地 Embedding 模型配置（基于 ONNX Runtime，免费、零 API 调用）
 * <p>
 * 当 Nacos 配置 {@code scaffold.embedding.provider=local} 时激活，创建
 * {@link TransformersEmbeddingModel} Bean 替代 DashScope 远程 Embedding。
 * <p>
 * <b>开关说明</b>：
 * <ul>
 *     <li>{@code scaffold.embedding.provider=local}：启用本地 ONNX 模型（免费）</li>
 *     <li>{@code scaffold.embedding.provider=dashscope}：使用通义千问远程 Embedding（收费，默认）</li>
 * </ul>
 * <p>
 * <b>Bean 冲突处理</b>：
 * Spring AI Alibaba 的 {@code DashScopeEmbeddingAutoConfiguration} 自动装配的
 * {@code dashscopeEmbeddingModel} Bean 默认带 {@code @Primary}，若本地 Bean 也加
 * {@code @Primary} 会触发 {@code NoUniqueBeanDefinitionException}（more than one 'primary'）。
 * 因此启用本地 Embedding 时，通过 {@link BeanFactoryPostProcessor} 在 Bean 实例化前
 * 动态移除 DashScope 的 EmbeddingModel BeanDefinition，保证容器中只剩一个 EmbeddingModel。
 * <p>
 * <b>维度对齐</b>：本地模型输出维度必须与 {@code spring.ai.milvus.embedding-dimension} 一致，
 * 否则启动时 {@code auto-rebuild-on-dimension-mismatch} 会自动重建 Milvus 集合。
 * bge-large-zh-v1.5 输出 1024 维，与 DashScope qwen3.7-text-embedding 维度一致，切换无需改维度。
 * <p>
 * <b>模型文件获取</b>：执行 {@code deploy/models/download-embedding-model.ps1}（Windows）
 * 或 {@code deploy/models/download-embedding-model.sh}（Linux）下载 Xenova/bge-large-zh-v1.5 的 ONNX 文件，
 * 放到 {@code deploy/models/bge-large-zh-v1.5/} 目录下。
 *
 * @author 稚名不带撇
 */
@Slf4j
@Configuration
@ConditionalOnProperty(name = "scaffold.embedding.provider", havingValue = "local")
public class LocalEmbeddingConfig {

    /**
     * ONNX 模型文件路径（支持 classpath: / file: / https: 协议）
     */
    @Value("${scaffold.embedding.local.onnx-model-uri}")
    private String onnxModelUri;

    /**
     * 分词器文件路径（支持 classpath: / file: / https: 协议）
     */
    @Value("${scaffold.embedding.local.tokenizer-uri}")
    private String tokenizerUri;

    /**
     * ONNX 模型输出层名称
     * <p>
     * bge-large-zh-v1.5 固定为 {@code last_hidden_state}（原始隐藏状态）；
     * bge-small / bge-base 为 {@code token_embeddings}（pooler 层处理过）。
     * 输出层名称由模型本身决定，与下载方式无关，只要模型不变此值就不变。
     */
    @Value("${scaffold.embedding.local.model-output-name:last_hidden_state}")
    private String modelOutputName;

    /**
     * 动态移除 DashScope 自动配置的 EmbeddingModel Bean
     * <p>
     * 必须声明为 {@code static}，避免触发本 {@code @Configuration} 类的提前实例化
     * （{@code BeanFactoryPostProcessor} 需在所有 Bean 实例化前执行，若依赖宿主类的
     * {@code @Value} 字段会导致提前实例化失败）。
     * <p>
     * 执行时机：所有 BeanDefinition 注册完成后、Bean 实例化前。此时遍历容器中所有
     * {@link EmbeddingModel} 类型的 BeanDefinition，移除除 {@code localEmbeddingModel}
     * 之外的所有 Bean（即 DashScope 自动装配的 {@code dashscopeEmbeddingModel}）。
     *
     * @return BeanFactoryPostProcessor 实例
     */
    @Bean
    public static BeanFactoryPostProcessor dashscopeEmbeddingRemover() {
        return beanFactory -> {
            if (beanFactory instanceof BeanDefinitionRegistry registry) {
                String[] beanNames = beanFactory.getBeanNamesForType(EmbeddingModel.class);
                for (String beanName : beanNames) {
                    if (!"localEmbeddingModel".equals(beanName)) {
                        registry.removeBeanDefinition(beanName);
                        log.info("移除 DashScope EmbeddingModel Bean：{}（已切换为本地 Embedding）", beanName);
                    }
                }
            }
        };
    }

    /**
     * 创建本地 Transformers Embedding 模型 Bean
     * <p>
     * 由于 {@link #dashscopeEmbeddingRemover} 已移除 DashScope 的 EmbeddingModel Bean，
     * 容器中仅剩此 Bean，无需 {@code @Primary} 即可被 {@code @Autowired} 正确注入。
     * <p>
     * <b>初始化说明</b>：{@link TransformersEmbeddingModel#afterPropertiesSet()} 会在
     * Bean 创建后自动调用，加载 ONNX 模型文件到内存（约 1.3GB），首次加载耗时 3-10 秒。
     * <p>
     * <b>内存要求</b>：bge-large-zh-v1.5 模型加载需要较大内存，JVM 堆内存建议至少 2g，
     * 否则 ONNX Runtime 会抛出 {@code bad allocation}（native 层内存分配失败）。
     * <p>
     * <b>线程安全</b>：ONNX Runtime 的 {@code OrtSession} 不是线程安全的，
     * 知识同步（MQ 消费者线程）和对话检索（HTTP 线程）并发调用同一个
     * {@code TransformersEmbeddingModel} 实例会抛 native 异常。
     * 因此返回 {@link ThreadSafeEmbeddingModel} 包装器，通过 synchronized
     * 串行化所有 embed 调用，保证线程安全（排队执行，不增加内存）。
     *
     * @return 本地 Embedding 模型实例（线程安全包装）
     * @throws Exception 模型文件加载失败时抛出
     */
    @Bean
    public EmbeddingModel localEmbeddingModel() throws Exception {
        TransformersEmbeddingModel embeddingModel = new TransformersEmbeddingModel();
        embeddingModel.setModelResource(onnxModelUri);
        embeddingModel.setTokenizerResource(tokenizerUri);
        embeddingModel.setModelOutputName(modelOutputName);
        // 开启 tokenizer padding，避免批量 embed 时出现 "Supplied array is ragged" 错误
        embeddingModel.setTokenizerOptions(Map.of("padding", "true"));
        embeddingModel.afterPropertiesSet();
        log.info("本地 Embedding 模型加载完成，已启用线程安全包装（synchronized 串行化 embed 调用）");
        return new ThreadSafeEmbeddingModel(embeddingModel);
    }

    /**
     * 线程安全的 EmbeddingModel 包装器
     * <p>
     * ONNX Runtime 的 {@code OrtSession} 不是线程安全的，并发调用 embed 会抛 native 异常。
     * 此包装器通过 synchronized 串行化所有 embed 相关调用，保证线程安全。
     * <p>
     * <b>影响</b>：知识同步（MQ 消费者线程）和对话检索（HTTP 线程）会排队执行 embed，
     * 同步期间对话请求的向量检索会等待几秒（本地模型单次 embed 约 500ms-1s），但不会报错。
     * DashScope 远程 API 天然支持并发，切换回 DashScope 时无此限制。
     */
    private static class ThreadSafeEmbeddingModel implements EmbeddingModel {
        private final EmbeddingModel delegate;

        ThreadSafeEmbeddingModel(EmbeddingModel delegate) {
            this.delegate = delegate;
        }

        @Override
        public synchronized EmbeddingResponse call(EmbeddingRequest request) {
            return delegate.call(request);
        }

        @Override
        public synchronized float[] embed(String text) {
            return delegate.embed(text);
        }

        @Override
        public synchronized float[] embed(Document document) {
            return delegate.embed(document);
        }

        @Override
        public synchronized List<float[]> embed(List<String> texts) {
            return delegate.embed(texts);
        }
    }
}
