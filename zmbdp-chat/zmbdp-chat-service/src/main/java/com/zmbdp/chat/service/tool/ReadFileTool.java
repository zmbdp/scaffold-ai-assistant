package com.zmbdp.chat.service.tool;

import com.zmbdp.common.core.utils.FileUtil;
import com.zmbdp.common.core.utils.JsonUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.context.config.annotation.RefreshScope;
import org.springframework.stereotype.Component;

import java.io.File;
import java.util.HashMap;
import java.util.Map;

/**
 * 文件读取工具
 * <p>
 * 提供 Agent 读取指定文件内容的能力，用于用户询问某个类的实现、查看配置文件内容等场景
 * <p>
 * <b>安全性</b>：通过 {@link PathSecurityValidator} 校验路径白名单和黑名单，
 * 禁止访问 .git、/etc、/tmp 等敏感目录和隐藏文件。
 * <p>
 * <b>文件大小限制</b>：超过 {@code knowledge.max-file-size}（默认 10MB）的文件会截断返回，
 * 避免大文件占用过多 Token。
 *
 * @author 稚名不带撇
 */
@Slf4j
@Component
@RefreshScope
public class ReadFileTool {

    /**
     * 文件过大时的截断提示后缀
     */
    private static final String FILE_TOO_LARGE_SUFFIX = "\n\n...[文件过大，已截断，仅显示前 "
            + "%d 字节内容，完整内容请使用 SearchInFileTool 搜索关键字]";

    /**
     * 路径安全校验器（复用白名单和黑名单校验逻辑）
     */
    @Autowired
    private PathSecurityValidator pathSecurityValidator;

    /**
     * 最大文件大小（字节，从 Nacos 配置 {@code knowledge.max-file-size} 读取，默认 10MB）
     * <p>
     * 超过此大小的文件会截断返回前 maxFileSize 字节内容。
     */
    @Value("${knowledge.max-file-size:10485760}")
    private Long maxFileSize;

    /**
     * 读取指定文件的内容
     * <p>
     * 执行流程：
     * <ol>
     *     <li>校验 filePath 是否在白名单范围内（{@link PathSecurityValidator#validatePath}）</li>
     *     <li>使用 {@link FileUtil#readUtf8String(String)} 读取文件内容</li>
     *     <li>如果文件过大（&gt; maxFileSize），返回前 maxFileSize 内容并提示文件过大</li>
     *     <li>返回文件内容</li>
     * </ol>
     *
     * @param filePath 文件绝对路径
     * @return 文件内容字符串；异常时返回 JSON 格式的错误信息
     */
    @org.springframework.ai.tool.annotation.Tool(
            description = "读取指定文件的内容，参数filePath为文件绝对路径。用于查看某个类的完整源码实现。"
    )
    public String readFile(String filePath) {
        try {
            // 1. 校验路径是否在白名单范围内
            pathSecurityValidator.validatePath(filePath);
            // 2. 检查文件是否存在
            File file = FileUtil.file(filePath);
            if (!FileUtil.exist(file)) {
                return buildErrorJson("文件不存在: " + filePath);
            }
            // 3. 检查文件大小，过大则截断返回
            long fileSize = FileUtil.size(file);
            if (maxFileSize != null && fileSize > maxFileSize) {
                // 截断读取前 maxFileSize 字节
                String truncatedContent = readTruncatedContent(filePath, maxFileSize);
                String suffix = String.format(FILE_TOO_LARGE_SUFFIX, maxFileSize);
                log.info("文件过大已截断：filePath = {}, fileSize = {}, maxFileSize = {}",
                        filePath, fileSize, maxFileSize);
                return truncatedContent + suffix;
            }
            // 4. 读取完整文件内容
            String content = FileUtil.readUtf8String(filePath);
            log.info("读取文件成功：filePath = {}, size = {}", filePath, fileSize);
            return content;
        } catch (SecurityException e) {
            log.warn("路径校验失败：filePath = {}, error = {}", filePath, e.getMessage());
            return buildErrorJson("路径不在允许范围内: " + filePath);
        } catch (Exception e) {
            log.warn("读取文件失败：filePath = {}, error = {}", filePath, e.getMessage());
            return buildErrorJson("文件读取失败: " + e.getMessage());
        }
    }

    /**
     * 读取文件前指定字节的内容（截断读取）
     * <p>
     * 使用 {@link FileUtil#readUtf8String(File)} 读取完整内容后截取前 maxBytes 字节，
     * 避免读取超大文件时内存溢出（实际文件大小已在调用前校验，此处为二次保护）。
     *
     * @param filePath 文件路径
     * @param maxBytes 最大字节数
     * @return 截断后的文件内容
     */
    private String readTruncatedContent(String filePath, Long maxBytes) {
        String content = FileUtil.readUtf8String(filePath);
        if (content == null) {
            return "";
        }
        int endIndex = (int) Math.min(content.length(), maxBytes);
        return content.substring(0, endIndex);
    }

    /**
     * 构建错误返回 JSON
     * <p>
     * 格式：{@code {"error": "错误信息"}}
     *
     * @param errorMsg 错误信息
     * @return JSON 字符串
     */
    private String buildErrorJson(String errorMsg) {
        Map<String, String> errorMap = new HashMap<>(2);
        errorMap.put("error", errorMsg);
        return JsonUtil.classToJson(errorMap);
    }
}