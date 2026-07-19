package com.zmbdp.chat.service.tool;

import com.zmbdp.common.core.utils.FileUtil;
import com.zmbdp.common.core.utils.JsonUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.context.config.annotation.RefreshScope;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 代码搜索工具
 * <p>
 * 在代码库中搜索类或方法，基于简单文件遍历 + 关键词匹配实现
 * <p>
 * <b>设计决策</b>：不使用 Lucene 倒排索引，改为简单文件遍历 + 关键词匹配。
 * 脚手架代码库规模有限，简单搜索完全满足需求，避免维护索引构建、增量更新等复杂逻辑。
 * <p>
 * <b>匹配优先级</b>：类名匹配 &gt; 方法名匹配 &gt; 全文匹配，按优先级排序后限制返回数量。
 * <p>
 * <b>安全性</b>：搜索范围通过 {@code knowledge.allowed-paths} 配置白名单限制，
 * 文件路径返回相对路径（相对于 {@code knowledge.base-path}），不暴露宿主机绝对路径。
 *
 * @author 稚名不带撇
 */
@Slf4j
@Component
@RefreshScope
public class SearchCodeTool {

    /**
     * 默认返回结果数量
     */
    private static final int DEFAULT_LIMIT = 10;

    /**
     * 最大返回结果数量（防止返回过多结果占用 Token）
     */
    private static final int MAX_LIMIT = 50;

    /**
     * Java 源码文件扩展名
     */
    private static final String JAVA_FILE_SUFFIX = ".java";

    /**
     * 匹配优先级：类名匹配
     */
    private static final int SCORE_CLASS_MATCH = 3;

    /**
     * 匹配优先级：方法名匹配
     */
    private static final int SCORE_METHOD_MATCH = 2;

    /**
     * 匹配优先级：全文匹配
     */
    private static final int SCORE_FULL_TEXT_MATCH = 1;

    /**
     * 路径安全校验器（提供白名单路径列表）
     */
    @Autowired
    private PathSecurityValidator pathSecurityValidator;

    /**
     * 项目根路径（从 Nacos 配置 {@code knowledge.base-path} 读取）
     * <p>
     * 用于将搜索结果的绝对路径转换为相对路径，避免暴露宿主机路径。
     */
    @Value("${knowledge.base-path:}")
    private String basePath;

    /**
     * 在代码库中搜索类或方法
     * <p>
     * 执行流程：
     * <ol>
     *     <li>校验 keyword 非空</li>
     *     <li>遍历 allowed-paths 下的所有 .java 文件（使用 {@link FileUtil#loopFiles}）</li>
     *     <li>逐行读取文件内容（使用 {@link FileUtil#readUtf8Lines}），匹配 keyword</li>
     *     <li>收集匹配结果（filePath、className、matchedLine、lineNumber、context）</li>
     *     <li>按匹配优先级排序（类名 &gt; 方法名 &gt; 全文）</li>
     *     <li>限制返回数量（limit，默认 10）</li>
     *     <li>使用 {@link JsonUtil#classToJson} 序列化为 JSON 字符串返回</li>
     * </ol>
     *
     * @param keyword 搜索关键词（类名、方法名等）
     * @param limit   返回结果数量（可选，默认 10）
     * @return 匹配的类/方法列表 JSON；异常时返回错误 JSON
     */
    @org.springframework.ai.tool.annotation.Tool(
            description = "在代码库中搜索类或方法，参数keyword为搜索关键词（类名、方法名等），limit为返回结果数量。用于查找某个功能在哪里实现。"
    )
    public String searchCode(String keyword, Integer limit) {
        // 1. 校验关键词
        if (!StringUtils.hasText(keyword)) {
            return buildErrorJson("搜索关键词不能为空");
        }
        // 2. 解析 limit 参数
        int resultLimit = (limit == null || limit <= 0) ? DEFAULT_LIMIT : Math.min(limit, MAX_LIMIT);
        // 3. 获取搜索路径
        List<String> allowedPaths = pathSecurityValidator.getAllowedPaths();
        if (CollectionUtils.isEmpty(allowedPaths)) {
            return buildErrorJson("搜索路径不存在，请检查 knowledge.allowed-paths 配置");
        }
        // 4. 遍历 .java 文件，搜索匹配内容
        List<Map<String, Object>> matches = new ArrayList<>();
        for (String allowedPath : allowedPaths) {
            if (!StringUtils.hasText(allowedPath)) {
                continue;
            }
            File pathFile = FileUtil.file(allowedPath);
            if (!FileUtil.exist(pathFile) || !pathFile.isDirectory()) {
                log.debug("搜索路径不存在或不是目录：{}", allowedPath);
                continue;
            }
            List<File> javaFiles = FileUtil.loopFiles(pathFile,
                    file -> file.isFile() && file.getName().endsWith(JAVA_FILE_SUFFIX));
            for (File javaFile : javaFiles) {
                searchInFile(javaFile, keyword, matches);
            }
        }
        // 5. 搜索无结果
        if (matches.isEmpty()) {
            Map<String, Object> emptyResult = new HashMap<>(2);
            emptyResult.put("results", Collections.emptyList());
            emptyResult.put("message", "未找到匹配的代码");
            return JsonUtil.classToJson(emptyResult);
        }
        // 6. 按匹配优先级降序排序
        matches.sort(Comparator.comparingInt(this::extractScore).reversed());
        // 7. 限制返回数量
        List<Map<String, Object>> limitedResults = matches.size() > resultLimit
                ? matches.subList(0, resultLimit) : matches;
        // 8. 移除内部排序用的 _score 字段，构建最终结果
        List<Map<String, Object>> finalResults = new ArrayList<>(limitedResults.size());
        for (Map<String, Object> match : limitedResults) {
            Map<String, Object> cleanMatch = new HashMap<>(match);
            cleanMatch.remove("_score");
            finalResults.add(cleanMatch);
        }
        // 9. 构建返回结果
        Map<String, Object> result = new HashMap<>(2);
        result.put("results", finalResults);
        result.put("total", matches.size());
        log.info("代码搜索完成：keyword = {}, total = {}, returned = {}",
                keyword, matches.size(), finalResults.size());
        return JsonUtil.classToJson(result);
    }

    /**
     * 在单个 Java 文件中搜索关键词
     * <p>
     * 逐行读取文件内容，匹配 keyword，为每个匹配打分并收集到 matches 列表。
     * 文件读取异常时跳过该文件继续搜索（不影响其他文件）。
     *
     * @param javaFile Java 文件
     * @param keyword  搜索关键词
     * @param matches  匹配结果收集列表
     */
    private void searchInFile(File javaFile, String keyword, List<Map<String, Object>> matches) {
        try {
            List<String> lines = FileUtil.readUtf8Lines(javaFile);
            if (CollectionUtils.isEmpty(lines)) {
                return;
            }
            String fileName = javaFile.getName();
            // 从文件名提取类名（去掉 .java 后缀）
            String className = fileName.substring(0, fileName.length() - JAVA_FILE_SUFFIX.length());
            String relativePath = toRelativePath(javaFile.getAbsolutePath());
            for (int i = 0; i < lines.size(); i++) {
                String line = lines.get(i);
                if (line == null || !line.contains(keyword)) {
                    continue;
                }
                int score = computeScore(line, keyword, className);
                Map<String, Object> match = new HashMap<>(8);
                match.put("filePath", relativePath);
                match.put("className", className);
                match.put("matchedLine", line.trim());
                match.put("lineNumber", i + 1);
                match.put("context", line.trim());
                match.put("_score", score);
                matches.add(match);
            }
        } catch (Exception e) {
            log.warn("读取文件失败，跳过该文件：file = {}, error = {}", javaFile.getAbsolutePath(), e.getMessage());
        }
    }

    /**
     * 计算匹配优先级分数
     * <p>
     * 评分规则：
     * <ul>
     *     <li>类名匹配（3 分）：行中包含 "class {keyword}" 或 "interface {keyword}"</li>
     *     <li>方法名匹配（2 分）：行中包含 keyword 且为方法签名格式（含括号）</li>
     *     <li>全文匹配（1 分）：行中包含 keyword</li>
     * </ul>
     *
     * @param line      文件行内容
     * @param keyword   搜索关键词
     * @param className 类名（从文件名提取）
     * @return 匹配分数
     */
    private int computeScore(String line, String keyword, String className) {
        // 类名匹配：行中包含 "class {keyword}" 或 "interface {keyword}"
        if (line.contains("class " + keyword) || line.contains("interface " + keyword)) {
            return SCORE_CLASS_MATCH;
        }
        // 方法名匹配：行中包含 keyword 且为方法签名格式（含括号）
        if (line.contains("(") && line.contains(")")) {
            return SCORE_METHOD_MATCH;
        }
        // 全文匹配
        return SCORE_FULL_TEXT_MATCH;
    }

    /**
     * 从匹配结果中提取排序分数
     *
     * @param match 匹配结果
     * @return 排序分数
     */
    private int extractScore(Map<String, Object> match) {
        Object score = match.get("_score");
        if (score instanceof Integer) {
            return (Integer) score;
        }
        return SCORE_FULL_TEXT_MATCH;
    }

    /**
     * 将绝对路径转换为相对路径（相对于 basePath）
     * <p>
     * 避免在返回结果中暴露宿主机绝对路径。若 basePath 为空或路径不以 basePath 开头，返回原始路径。
     *
     * @param absolutePath 文件绝对路径
     * @return 相对路径
     */
    private String toRelativePath(String absolutePath) {
        if (!StringUtils.hasText(basePath) || !StringUtils.hasText(absolutePath)) {
            return absolutePath;
        }
        String normalizedBase = basePath.replace('\\', '/');
        String normalizedAbs = absolutePath.replace('\\', '/');
        if (normalizedAbs.startsWith(normalizedBase)) {
            String relative = normalizedAbs.substring(normalizedBase.length());
            if (relative.startsWith("/")) {
                relative = relative.substring(1);
            }
            return relative;
        }
        return absolutePath;
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