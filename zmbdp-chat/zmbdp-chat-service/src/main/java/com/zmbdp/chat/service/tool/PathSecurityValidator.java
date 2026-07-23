package com.zmbdp.chat.service.tool;

import com.zmbdp.chat.service.config.KnowledgeProperties;
import com.zmbdp.common.core.utils.FileUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

import java.util.List;

/**
 * 路径安全校验器
 * <p>
 * 集中管理 Agent 文件操作工具（ReadFileTool/SearchCodeTool/ListDirTool/SearchInFileTool）的
 * 路径白名单和黑名单配置，提供统一的路径安全校验方法。
 * <p>
 * <b>设计决策</b>：
 * 设计文档 5.2.1-5.2.4 要求每个工具类通过 {@code @Value} 注入 allowedPaths/pathBlacklist，
 * 但 5.2.3/5.2.4 明确要求"复用 ReadFileTool 的白名单校验逻辑"。
 * 为同时满足"配置统一管理"和"校验逻辑复用"，将公共的路径安全配置和校验逻辑抽取到本类，
 * 避免四个工具类重复实现相同的校验代码（DRY 原则）。
 * <p>
 * <b>配置绑定</b>：通过 {@link KnowledgeProperties}（{@code @ConfigurationProperties}）读取
 * {@code knowledge.allowed-paths} 和 {@code knowledge.path-blacklist} 两个 YAML 列表配置项。
 * 不使用 {@code @Value} 是因为它不支持 YAML 列表格式绑定到 {@code List<String>}。
 *
 * @author 稚名不带撇
 */
@Slf4j
@Component
public class PathSecurityValidator {

    /**
     * 知识库路径安全配置（通过 {@code @ConfigurationProperties} 绑定 YAML 列表，支持 Nacos 热刷新）
     */
    @Autowired
    private KnowledgeProperties knowledgeProperties;

    /**
     * 校验路径是否允许访问
     * <p>
     * 校验规则：
     * <ol>
     *     <li>路径必须非空</li>
     *     <li>路径必须以白名单前缀开头（任一前缀匹配即可）</li>
     *     <li>路径不能包含黑名单目录（如 .git、/etc、/tmp，任一黑名单匹配则拒绝）</li>
     *     <li>路径不能是隐藏文件（文件名以 . 开头，但 . 和 .. 除外）</li>
     * </ol>
     *
     * @param filePath 文件路径
     * @return true=允许访问，false=拒绝访问
     */
    public boolean isPathAllowed(String filePath) {
        if (!StringUtils.hasText(filePath)) {
            return false;
        }
        String normalizedPath = normalizePath(filePath);
        List<String> allowedPaths = knowledgeProperties.getAllowedPaths();
        // 1. 检查白名单（路径必须以任一白名单前缀开头）
        if (CollectionUtils.isEmpty(allowedPaths)) {
            log.warn("路径白名单为空，拒绝所有文件访问：{}", filePath);
            return false;
        }
        boolean inWhitelist = false;
        for (String allowedPath : allowedPaths) {
            if (StringUtils.hasText(allowedPath)
                    && normalizedPath.startsWith(normalizePath(allowedPath))) {
                inWhitelist = true;
                break;
            }
        }
        if (!inWhitelist) {
            return false;
        }
        // 2. 检查黑名单（路径不能包含任一黑名单目录）
        List<String> pathBlacklist = knowledgeProperties.getPathBlacklist();
        if (!CollectionUtils.isEmpty(pathBlacklist)) {
            for (String blacklist : pathBlacklist) {
                if (StringUtils.hasText(blacklist) && normalizedPath.contains(blacklist)) {
                    return false;
                }
            }
        }
        // 3. 检查隐藏文件（文件名以 . 开头，但 . 和 .. 除外）
        String fileName = extractFileName(normalizedPath);
        if (fileName.startsWith(".") && !fileName.equals(".") && !fileName.equals("..")) {
            return false;
        }
        return true;
    }

    /**
     * 校验路径，不允许则抛 SecurityException
     * <p>
     * 供 ReadFileTool/SearchInFileTool/ListDirTool 在方法入口处调用，校验失败时抛出异常，
     * 由工具方法内部 catch 后转换为错误 JSON 返回给大模型。
     *
     * @param filePath 文件路径
     * @throws SecurityException 路径不在允许范围内
     */
    public void validatePath(String filePath) {
        if (!isPathAllowed(filePath)) {
            throw new SecurityException("路径不在允许范围内: " + filePath);
        }
    }

    /**
     * 获取路径白名单（供 SearchCodeTool 遍历搜索范围使用）
     *
     * @return 路径白名单列表
     */
    public List<String> getAllowedPaths() {
        return knowledgeProperties.getAllowedPaths();
    }

    /**
     * 标准化路径
     * <p>
     * 统一路径分隔符为 /（兼容 Windows 和 Linux），并去除 ./.. 等相对路径组件。
     *
     * @param path 原始路径
     * @return 标准化后的路径
     */
    private String normalizePath(String path) {
        if (path == null) {
            return "";
        }
        // 统一路径分隔符为 /
        String normalized = path.replace('\\', '/');
        // 使用 Hutool FileUtil 标准化（去除 ./.. 等）
        return FileUtil.normalize(normalized);
    }

    /**
     * 提取文件名
     *
     * @param path 标准化后的路径
     * @return 文件名
     */
    private String extractFileName(String path) {
        if (path == null || path.isEmpty()) {
            return "";
        }
        int lastSlash = path.lastIndexOf('/');
        if (lastSlash >= 0 && lastSlash < path.length() - 1) {
            return path.substring(lastSlash + 1);
        }
        return path;
    }
}