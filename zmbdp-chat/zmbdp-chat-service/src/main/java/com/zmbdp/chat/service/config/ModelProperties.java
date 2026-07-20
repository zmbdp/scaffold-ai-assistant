package com.zmbdp.chat.service.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.cloud.context.config.annotation.RefreshScope;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Spring AI 多模型配置属性
 * <p>
 * 绑定 Nacos {@code zmbdp-chat-service-${env}.yaml} 中的 {@code spring.ai.models} 配置列表，
 * 供 {@code ModelServiceImpl} 注入使用。
 * <p>
 * <b>配置示例</b>（对应 {@code spring.ai} 前缀）：
 * <pre>{@code
 * spring:
 *   ai:
 *     models:
 *       - type: TEXT_ONLY
 *         name: deepseek-v4-flash
 *         provider: dashscope
 *         enabled: true
 *         default-model: true
 *         capabilities: [TEXT]
 * }</pre>
 * <p>
 * <b>字段映射说明</b>：
 * YAML 中的 {@code default-model}（kebab-case）通过 Spring Boot relaxed binding 自动映射到
 * {@link ModelConfig#getDefaultModel()}（camelCase）。
 * 不直接用 {@code default}，因为它是 Java 关键字，不能作为字段名。
 * <p>
 * <b>热刷新</b>：配合 {@link RefreshScope}，Nacos 配置变更时自动重新绑定；
 * {@code ModelServiceImpl} 同样标注 {@code @RefreshScope}，重建时 {@code @PostConstruct} 重新调用，
 * 自动重建 ChatClient 实例池和默认模型配置。
 *
 * @author 稚名不带撇
 */
@Data
@Component
@RefreshScope
@ConfigurationProperties(prefix = "spring.ai")
public class ModelProperties {

    /**
     * 多模型配置列表（对应 spring.ai.models）
     * <p>
     * 启动时由 {@code ModelServiceImpl} 遍历此列表，为每个 {@code enabled=true} 的模型构建 ChatClient。
     */
    private List<ModelConfig> models = new ArrayList<>();
}
