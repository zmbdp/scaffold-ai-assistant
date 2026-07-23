package com.zmbdp.chat.service.tool;

import com.zmbdp.common.core.utils.JsonUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 工具调用记录装饰器
 * <p>
 * 包装原始 {@link ToolCallback}，在每次工具调用时记录：
 * <ul>
 *     <li>工具名（{@link ToolDefinition#name()}）</li>
 *     <li>调用参数（JSON 字符串）</li>
 *     <li>调用结果（截断到 500 字符，避免日志过长）</li>
 *     <li>耗时（毫秒）</li>
 *     <li>是否成功（true/false）</li>
 *     <li>失败原因（异常 message，成功时为 null）</li>
 * </ul>
 * <p>
 * <b>使用方式</b>：通过 {@link #wrap(ToolCallback)} 包装原始 callback，
 * 调用 {@link #drainRecords()} 获取并清空本次收集的调用记录（JSON 字符串列表）。
 * <p>
 * <b>线程安全</b>：每个装饰器实例内部用 {@code synchronizedList} 保证收集列表的线程安全，
 * 但工具调用本身由 Spring AI 在流式对话的 reactor 线程中触发，外层通过 ThreadLocal 绑定到单次对话。
 *
 * @author 稚名不带撇
 */
@Slf4j
public class ToolCallRecorder implements ToolCallback {

    /**
     * 被包装的原始 ToolCallback
     */
    private final ToolCallback delegate;

    /**
     * 本次对话收集的工具调用记录（JSON 字符串列表，每项为一条调用记录）
     * <p>
     * 使用 synchronizedList 保证 reactor 线程并发写入安全。
     */
    private final List<String> records = Collections.synchronizedList(new ArrayList<>());

    /**
     * 工具调用结果最大保留长度（超出截断，避免日志字段过长）
     */
    private static final int MAX_RESULT_LENGTH = 500;

    /**
     * 工具调用失败时错误信息最大保留长度
     */
    private static final int MAX_ERROR_LENGTH = 500;

    private ToolCallRecorder(ToolCallback delegate) {
        this.delegate = delegate;
    }

    /**
     * 包装原始 ToolCallback
     *
     * @param delegate 原始 ToolCallback
     * @return 带记录功能的装饰器实例
     */
    public static ToolCallRecorder wrap(ToolCallback delegate) {
        return new ToolCallRecorder(delegate);
    }

    @Override
    public ToolDefinition getToolDefinition() {
        return delegate.getToolDefinition();
    }

    /**
     * 工具调用入口（无 ToolContext）
     * <p>
     * 记录调用参数、结果、耗时、成功/失败，将记录序列化为 JSON 存入 {@link #records}。
     *
     * @param toolInput 工具输入参数（JSON 字符串）
     * @return 工具调用结果
     */
    @Override
    public String call(String toolInput) {
        return call(toolInput, null);
    }

    /**
     * 工具调用入口（带 ToolContext）
     * <p>
     * 记录调用参数、结果、耗时、成功/失败，将记录序列化为 JSON 存入 {@link #records}。
     *
     * @param toolInput   工具输入参数（JSON 字符串）
     * @param toolContext 工具上下文（可为 null）
     * @return 工具调用结果
     */
    @Override
    public String call(String toolInput, ToolContext toolContext) {
        String toolName = delegate.getToolDefinition().name();
        long startTime = System.currentTimeMillis();
        String result = null;
        String errorMsg = null;
        boolean success = false;
        try {
            result = delegate.call(toolInput, toolContext);
            success = true;
        } catch (Exception e) {
            errorMsg = e.getMessage();
            log.error("工具调用失败：toolName = {}, toolInput = {}, error = {}", toolName, toolInput, errorMsg, e);
            throw e;
        } finally {
            long duration = System.currentTimeMillis() - startTime;
            recordCall(toolName, toolInput, result, duration, success, errorMsg);
        }
        return result;
    }

    /**
     * 记录一次工具调用
     * <p>
     * 将调用信息序列化为 JSON 字符串，格式与 sys_ai_operation_log.tool_calls 字段约定一致：
     * <pre>{@code
     * {"name":"readFile","arguments":"{\"filePath\":\"/xxx\"}","success":true,"duration":45,"summary":"..."}
     * }</pre>
     *
     * @param toolName  工具名
     * @param arguments 调用参数（JSON 字符串）
     * @param result    调用结果（失败时为 null）
     * @param duration  耗时（毫秒）
     * @param success   是否成功
     * @param errorMsg  失败原因（成功时为 null）
     */
    private void recordCall(String toolName, String arguments, String result,
                            long duration, boolean success, String errorMsg) {
        try {
            java.util.Map<String, Object> record = new java.util.HashMap<>();
            record.put("name", toolName);
            record.put("arguments", arguments);
            record.put("success", success);
            record.put("duration", duration);
            // 结果摘要（截断，避免 tool_calls 字段过大）
            record.put("summary", truncate(result, MAX_RESULT_LENGTH));
            if (StringUtils.hasText(errorMsg)) {
                record.put("errorMsg", truncate(errorMsg, MAX_ERROR_LENGTH));
            }
            records.add(JsonUtil.classToJson(record));
        } catch (Exception e) {
            log.warn("记录工具调用失败：toolName = {}, error = {}", toolName, e.getMessage());
        }
    }

    /**
     * 截断字符串到指定长度，超出部分用 "..." 省略
     */
    private String truncate(String str, int maxLength) {
        if (str == null) {
            return null;
        }
        if (str.length() <= maxLength) {
            return str;
        }
        return str.substring(0, maxLength) + "...";
    }

    /**
     * 获取并清空本次收集的工具调用记录
     * <p>
     * 返回 JSON 数组字符串，格式为 {@code [{"name":"readFile",...}, ...]}，
     * 直接写入 sys_ai_operation_log.tool_calls 字段。调用后清空内部 records 列表。
     * <p>
     * <b>调用时机</b>：在流式对话的 doOnComplete 回调中调用，此时所有工具调用已完成。
     *
     * @return 工具调用记录 JSON 数组字符串；无调用记录时返回 null
     */
    public String drainRecords() {
        if (records.isEmpty()) {
            return null;
        }
        // 取出快照后清空，避免重复 drain
        List<String> snapshot;
        synchronized (records) {
            snapshot = new ArrayList<>(records);
            records.clear();
        }
        if (snapshot.isEmpty()) {
            return null;
        }
        // 将每条 JSON 字符串包装成 JSON 数组
        return "[" + String.join(",", snapshot) + "]";
    }
}
