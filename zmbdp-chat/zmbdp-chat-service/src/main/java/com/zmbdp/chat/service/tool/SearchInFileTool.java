package com.zmbdp.chat.service.tool;

import com.zmbdp.common.core.utils.FileUtil;
import com.zmbdp.common.core.utils.JsonUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cloud.context.config.annotation.RefreshScope;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 文件内搜索工具
 * <p>
 * 在指定文件中搜索关键字，返回匹配行及前后 2 行上下文，
 * 用于用户询问某个方法或变量在文件中的具体位置。
 * <p>
 * <b>安全性</b>：通过 {@link PathSecurityValidator} 校验路径白名单和黑名单，
 * 禁止访问 .git、/etc、/tmp 等敏感目录和隐藏文件。
 * <p>
 * <b>结果限制</b>：最多返回 20 个匹配，避免结果过多占用 Token。
 *
 * @author 稚名不带撇
 */
@Slf4j
@Component
@RefreshScope
public class SearchInFileTool {

    /**
     * 上下文行数（匹配行的前 N 行和后 N 行）
     */
    private static final int CONTEXT_LINES = 2;

    /**
     * 最大返回匹配数量
     */
    private static final int MAX_MATCHES = 20;

    /**
     * 路径安全校验器（复用白名单和黑名单校验逻辑）
     */
    @Autowired
    private PathSecurityValidator pathSecurityValidator;

    /**
     * 在指定文件中搜索关键字
     * <p>
     * 执行流程：
     * <ol>
     *     <li>校验 filePath 是否在白名单范围内（{@link PathSecurityValidator#validatePath}）</li>
     *     <li>校验 keyword 非空</li>
     *     <li>使用 {@link FileUtil#readUtf8Lines(String)} 按行读取文件内容</li>
     *     <li>查找包含 keyword 的行</li>
     *     <li>收集匹配行及前后 2 行上下文</li>
     *     <li>返回匹配结果（最多 20 个匹配）</li>
     * </ol>
     *
     * @param filePath 文件绝对路径
     * @param keyword  搜索关键字
     * @return 匹配结果 JSON；异常时返回错误 JSON
     */
    @Tool(
            description = "在指定文件中搜索关键字，参数filePath为文件绝对路径，keyword为搜索关键字。用于查找某个方法或变量在文件中的具体位置。"
    )
    public String searchInFile(String filePath, String keyword) {
        try {
            // 1. 校验路径是否在白名单范围内
            pathSecurityValidator.validatePath(filePath);
            // 2. 校验关键字
            if (!StringUtils.hasText(keyword)) {
                return buildErrorJson("搜索关键字不能为空");
            }
            // 3. 检查文件是否存在
            File file = FileUtil.file(filePath);
            if (!FileUtil.exist(file)) {
                return buildErrorJson("文件不存在: " + filePath);
            }
            // 4. 按行读取文件内容
            List<String> lines = FileUtil.readUtf8Lines(file);
            if (CollectionUtils.isEmpty(lines)) {
                Map<String, Object> emptyResult = new HashMap<>(2);
                emptyResult.put("matches", Collections.emptyList());
                emptyResult.put("message", "文件内容为空");
                return JsonUtil.classToJson(emptyResult);
            }
            // 5. 查找匹配行并收集上下文
            List<Map<String, Object>> matches = new ArrayList<>();
            for (int i = 0; i < lines.size() && matches.size() < MAX_MATCHES; i++) {
                String line = lines.get(i);
                if (line != null && line.contains(keyword)) {
                    Map<String, Object> match = new HashMap<>(8);
                    match.put("lineNumber", i + 1);
                    match.put("lineContent", line);
                    match.put("contextBefore", getContextLines(lines, i - CONTEXT_LINES, i - 1));
                    match.put("contextAfter", getContextLines(lines, i + 1, i + CONTEXT_LINES));
                    matches.add(match);
                }
            }
            // 6. 无匹配结果
            if (matches.isEmpty()) {
                Map<String, Object> emptyResult = new HashMap<>(2);
                emptyResult.put("matches", Collections.emptyList());
                emptyResult.put("message", "未找到匹配内容");
                return JsonUtil.classToJson(emptyResult);
            }
            // 7. 构建返回结果
            Map<String, Object> result = new HashMap<>(2);
            result.put("matches", matches);
            result.put("total", matches.size());
            log.info("文件内搜索完成：filePath = {}, keyword = {}, matches = {}", filePath, keyword, matches.size());
            return JsonUtil.classToJson(result);
        } catch (SecurityException e) {
            log.warn("路径校验失败：filePath = {}, error = {}", filePath, e.getMessage());
            return buildErrorJson("路径不在允许范围内: " + filePath);
        } catch (Exception e) {
            log.warn("文件读取失败：filePath = {}, error = {}", filePath, e.getMessage());
            return buildErrorJson("文件读取失败: " + e.getMessage());
        }
    }

    /**
     * 获取指定行范围的上下文
     * <p>
     * 将 [startLine, endLine] 范围内的行拼接为一个字符串，行与行之间用换行符分隔。
     * 自动处理越界情况（startLine &lt; 0 或 endLine &gt;= lines.size()）。
     *
     * @param lines     文件所有行
     * @param startLine 起始行索引（含，0-based）
     * @param endLine   结束行索引（含，0-based）
     * @return 上下文字符串；无内容时返回空字符串
     */
    private String getContextLines(List<String> lines, int startLine, int endLine) {
        if (CollectionUtils.isEmpty(lines) || startLine > endLine) {
            return "";
        }
        int start = Math.max(0, startLine);
        int end = Math.min(lines.size() - 1, endLine);
        if (start > end) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = start; i <= end; i++) {
            if (sb.length() > 0) {
                sb.append("\n");
            }
            sb.append(lines.get(i));
        }
        return sb.toString();
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