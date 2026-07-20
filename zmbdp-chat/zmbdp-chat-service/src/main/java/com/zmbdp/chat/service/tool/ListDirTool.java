package com.zmbdp.chat.service.tool;

import com.zmbdp.common.core.utils.FileUtil;
import com.zmbdp.common.core.utils.JsonUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cloud.context.config.annotation.RefreshScope;
import org.springframework.stereotype.Component;

import java.io.File;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

/**
 * 目录列表工具
 * <p>
 * 列出指定目录的结构，生成 ASCII 目录树，用于用户询问项目或模块的目录组织
 * <p>
 * <b>安全性</b>：通过 {@link PathSecurityValidator} 校验路径白名单和黑名单，
 * 遍历子目录/文件时同样校验路径合法性，跳过 .git、/etc、/tmp 等敏感目录和隐藏文件。
 * <p>
 * <b>深度限制</b>：最多显示 3 层深度，超出部分显示 {@code ...}，避免目录树过大占用过多 Token。
 *
 * @author 稚名不带撇
 */
@Slf4j
@Component
@RefreshScope
public class ListDirTool {

    /**
     * 最大显示深度（根目录为第 1 层）
     */
    private static final int MAX_DEPTH = 3;

    /**
     * 每层缩进的空格数
     */
    private static final String INDENT_UNIT = "  ";

    /**
     * 目录前缀标识
     */
    private static final String DIR_PREFIX = "[D] ";

    /**
     * 文件前缀标识
     */
    private static final String FILE_PREFIX = "[F] ";

    /**
     * 超出深度限制时的提示
     */
    private static final String DEPTH_EXCEEDED_HINT = "...";

    /**
     * 路径安全校验器（复用白名单和黑名单校验逻辑）
     */
    @Autowired
    private PathSecurityValidator pathSecurityValidator;

    /**
     * 列出指定目录的结构
     * <p>
     * 执行流程：
     * <ol>
     *     <li>校验 dirPath 是否在白名单范围内（{@link PathSecurityValidator#validatePath}）</li>
     *     <li>使用 {@link FileUtil} 递归遍历目录结构</li>
     *     <li>生成 ASCII 目录树（最多显示 3 层深度）</li>
     *     <li>返回目录树字符串</li>
     * </ol>
     *
     * @param dirPath 目录绝对路径
     * @return ASCII 目录树字符串；异常时返回 JSON 格式的错误信息
     */
    @Tool(
            description = "列出指定目录的结构，参数dirPath为目录绝对路径。用于查看项目或模块的目录组织。"
    )
    public String listDir(String dirPath) {
        try {
            // 1. 校验路径是否在白名单范围内
            pathSecurityValidator.validatePath(dirPath);
            // 2. 检查目录是否存在
            File dir = FileUtil.file(dirPath);
            if (!FileUtil.exist(dir)) {
                return buildErrorJson("目录不存在: " + dirPath);
            }
            if (!dir.isDirectory()) {
                return buildErrorJson("路径不是目录: " + dirPath);
            }
            // 3. 生成 ASCII 目录树
            StringBuilder sb = new StringBuilder();
            sb.append(DIR_PREFIX).append(dir.getName()).append("/\n");
            buildDirectoryTree(dir, 1, sb, INDENT_UNIT);
            log.info("列出目录成功：dirPath = {}", dirPath);
            return sb.toString();
        } catch (SecurityException e) {
            log.warn("路径校验失败：dirPath = {}, error = {}", dirPath, e.getMessage());
            return buildErrorJson("路径不在允许范围内: " + dirPath);
        } catch (Exception e) {
            log.warn("遍历目录失败：dirPath = {}, error = {}", dirPath, e.getMessage());
            return buildErrorJson("目录遍历失败: " + e.getMessage());
        }
    }

    /**
     * 递归构建 ASCII 目录树
     * <p>
     * 遍历目录的子项，按"目录在前、文件在后、同类按名称排序"的顺序输出。
     * 子项路径通过 {@link PathSecurityValidator#isPathAllowed} 校验，跳过黑名单和隐藏文件。
     * 超过 {@link #MAX_DEPTH} 层的子目录显示 {@code ...}。
     *
     * @param dir         当前目录
     * @param currentDepth 当前深度（根目录为 0，其子项为 1，依此类推）
     * @param sb          字符串构建器
     * @param indent      当前缩进
     */
    private void buildDirectoryTree(File dir, int currentDepth, StringBuilder sb, String indent) {
        File[] children = dir.listFiles();
        if (children == null || children.length == 0) {
            return;
        }
        // 排序：目录在前，文件在后，同类按名称排序
        Arrays.sort(children, (a, b) -> {
            if (a.isDirectory() && !b.isDirectory()) {
                return -1;
            }
            if (!a.isDirectory() && b.isDirectory()) {
                return 1;
            }
            return a.getName().compareTo(b.getName());
        });
        for (File child : children) {
            // 校验子项路径是否允许访问（跳过黑名单目录和隐藏文件）
            if (!pathSecurityValidator.isPathAllowed(child.getAbsolutePath())) {
                continue;
            }
            String prefix = child.isDirectory() ? DIR_PREFIX : FILE_PREFIX;
            String suffix = child.isDirectory() ? "/" : "";
            sb.append(indent).append(prefix).append(child.getName()).append(suffix).append("\n");
            // 递归遍历子目录
            if (child.isDirectory()) {
                if (currentDepth < MAX_DEPTH) {
                    buildDirectoryTree(child, currentDepth + 1, sb, indent + INDENT_UNIT);
                } else {
                    // 超过最大深度，显示 ...
                    sb.append(indent).append(INDENT_UNIT).append(DEPTH_EXCEEDED_HINT).append("\n");
                }
            }
        }
    }

    /**
     * 构建错误返回 JSON
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