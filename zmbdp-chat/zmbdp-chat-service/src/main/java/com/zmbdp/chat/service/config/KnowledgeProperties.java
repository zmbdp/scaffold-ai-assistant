package com.zmbdp.chat.service.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.cloud.context.config.annotation.RefreshScope;
import org.springframework.stereotype.Component;

import java.io.Serial;
import java.io.Serializable;
import java.util.List;

/**
 * 知识库路径安全配置
 * <p>
 * 使用 {@code @ConfigurationProperties} 绑定 Nacos 中 {@code knowledge.allowed-paths} 和
 * {@code knowledge.path-blacklist} 两个 YAML 列表配置项。
 * <p>
 * <b>为什么不用 @Value</b>：{@code @Value} 不支持 YAML 列表格式（{@code - item}）绑定到
 * {@code List<String>}，YAML 列表在 Spring Environment 中被存为索引属性
 * （如 {@code knowledge.allowed-paths[0]}），{@code @Value} 只能匹配标量值。
 * {@code @ConfigurationProperties} 通过 relaxed binding 支持索引属性到 List 的绑定。
 *
 * @author 稚名不带撇
 */
@Data
@Component
@RefreshScope
@ConfigurationProperties(prefix = "knowledge")
public class KnowledgeProperties implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 路径白名单（允许 Agent 工具访问的目录列表）
     * <p>
     * 对应 Nacos 配置 {@code knowledge.allowed-paths}，YAML 列表格式：
     * <pre>{@code
     * knowledge:
     *   allowed-paths:
     *     - d:/GitHub/frameworkjava
     * }</pre>
     */
    private List<String> allowedPaths;

    /**
     * 路径黑名单（禁止 Agent 工具访问的目录/文件名列表）
     * <p>
     * 对应 Nacos 配置 {@code knowledge.path-blacklist}，YAML 列表格式：
     * <pre>{@code
     * knowledge:
     *   path-blacklist:
     *     - .git
     *     - /etc
     * }</pre>
     */
    private List<String> pathBlacklist;
}
