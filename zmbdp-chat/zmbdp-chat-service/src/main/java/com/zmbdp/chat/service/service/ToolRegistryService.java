package com.zmbdp.chat.service.service;

import com.zmbdp.admin.api.config.domain.dto.ArgumentDTO;
import com.zmbdp.admin.api.config.feign.ArgumentServiceApi;
import com.zmbdp.chat.service.tool.CompareConfigTool;
import com.zmbdp.chat.service.tool.ListDirTool;
import com.zmbdp.chat.service.tool.NacosConfigTool;
import com.zmbdp.chat.service.tool.PreDeployCheckTool;
import com.zmbdp.chat.service.tool.ReadFileTool;
import com.zmbdp.chat.service.tool.SearchCodeTool;
import com.zmbdp.chat.service.tool.SearchInFileTool;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Agent 工具注册服务
 * <p>
 * 通过 Feign 调用 {@code ArgumentServiceApi} 查询 {@code sys_argument} 表的 {@code ai.tool.*.enabled} 配置项，
 * 动态过滤已启用的工具，供 {@link IChatService} 在流式对话中配置给 ChatClient。
 * <p>
 * <b>设计决策</b>：
 * 原设计为 {@code config/ToolConfig.java}（@Configuration），经审视不合理，
 * 改为 {@code service/ToolRegistryService.java}（@Service），因为工具注册/发现是业务逻辑而非静态 Bean 定义。
 * <p>
 * <b>热加载</b>：管理员通过 B 端更新 {@code ai.tool.{toolName}.enabled} 配置项后，
 * {@code IAdminService.updateToolConfig()} 触发本服务 {@link #refreshTool} 刷新工具列表。
 * <p>
 * <b>工具名说明</b>：Spring AI 默认使用 {@code @Tool} 注解方法的<strong>方法名</strong>作为工具调用名称
 * （如 {@code readFile}、{@code searchCode}），不带类名前缀。本服务维护方法名到启用状态、工具 Bean 实例的映射关系。
 * <p>
 * <b>工具 Bean 注入</b>：通过 {@code @Autowired} 注入所有 7 个工具 Bean，在 {@link #initToolBeanMap} 中
 * 构建 toolName → toolBean 的映射。{@link #getEnabledToolBeans} 返回已启用的工具 Bean 实例列表，
 * 供 {@code ChatClient.prompt().tools(Object...)} 调用（Spring AI 自动扫描 {@code @Tool} 注解方法）。
 *
 * @author 稚名不带撇
 */
@Slf4j
@Service
public class ToolRegistryService {

    /**
     * 工具配置项前缀（sys_argument 表 configKey 前缀）
     */
    private static final String TOOL_CONFIG_KEY_PREFIX = "ai.tool.";

    /**
     * 工具配置项后缀（sys_argument 表 configKey 后缀）
     */
    private static final String TOOL_CONFIG_KEY_SUFFIX = ".enabled";

    /**
     * 启用状态值（sys_argument 表 value 字段）
     */
    private static final String ENABLED_VALUE = "true";

    /**
     * 全量已注册的 Agent 工具名列表（与 @Tool 注解方法名、db.sql 中 sys_argument 的 configKey 一致）。
     * <p>
     * 顺序：文件操作工具（4 个）+ Nacos 配置工具（3 个），共 7 个。
     * <p>
     * <b>命名规则</b>：与 {@code @Tool} 注解方法名一致（小驼峰），对应的 configKey 为
     * {@code ai.tool.{toolName}.enabled}。
     */
    private static final List<String> ALL_TOOL_NAMES = List.of(
            "readFile",
            "searchCode",
            "listDir",
            "searchInFile",
            "nacosConfig",
            "compareConfig",
            "preDeployCheck"
    );

    /**
     * 工具启用状态缓存（toolName → enabled）
     * <p>
     * 使用 ConcurrentHashMap 保证并发读写的线程安全；
     * 由 {@link #refreshEnabledTools} 全量刷新，{@link #refreshTool} 单条刷新。
     */
    private final Map<String, Boolean> toolEnabledMap = new ConcurrentHashMap<>();

    /**
     * 工具名 → 工具 Bean 实例的映射（{@link #initToolBeanMap} 初始化）
     * <p>
     * 用于在 {@code ChatClient.prompt().tools(Object...)} 调用时传入工具 Bean 实例，
     * Spring AI 会通过 MethodToolCallbackProvider 自动扫描 @Tool 注解方法。
     */
    private final Map<String, Object> toolBeanMap = new HashMap<>();

    /**
     * 参数服务远程调用 Api（Feign，查 sys_argument 表）
     */
    @Autowired
    private ArgumentServiceApi argumentServiceApi;

    /*=============================================    工具 Bean 注入    =============================================*/

    /**
     * 文件读取工具（@Tool 方法名：readFile）
     */
    @Autowired
    private ReadFileTool readFileTool;

    /**
     * 代码搜索工具（@Tool 方法名：searchCode）
     */
    @Autowired
    private SearchCodeTool searchCodeTool;

    /**
     * 目录列表工具（@Tool 方法名：listDir）
     */
    @Autowired
    private ListDirTool listDirTool;

    /**
     * 文件内搜索工具（@Tool 方法名：searchInFile）
     */
    @Autowired
    private SearchInFileTool searchInFileTool;

    /**
     * Nacos 配置查询工具（@Tool 方法名：nacosConfig）
     */
    @Autowired
    private NacosConfigTool nacosConfigTool;

    /**
     * 配置差异对比工具（@Tool 方法名：compareConfig）
     */
    @Autowired
    private CompareConfigTool compareConfigTool;

    /**
     * 部署前检查工具（@Tool 方法名：preDeployCheck）
     */
    @Autowired
    private PreDeployCheckTool preDeployCheckTool;

    /**
     * 初始化工具 Bean 映射
     * <p>
     * 在 Spring 容器完成依赖注入后执行，构建 toolName → toolBean 的映射关系，
     * 供 {@link #getEnabledToolBeans} 使用。
     */
    @PostConstruct
    public void initToolBeanMap() {
        toolBeanMap.put("readFile", readFileTool);
        toolBeanMap.put("searchCode", searchCodeTool);
        toolBeanMap.put("listDir", listDirTool);
        toolBeanMap.put("searchInFile", searchInFileTool);
        toolBeanMap.put("nacosConfig", nacosConfigTool);
        toolBeanMap.put("compareConfig", compareConfigTool);
        toolBeanMap.put("preDeployCheck", preDeployCheckTool);
        log.info("工具 Bean 映射初始化完成：共 {} 个工具", toolBeanMap.size());
    }

    /*=============================================    内部调用    =============================================*/

    /**
     * 获取已启用的 Agent 工具名列表
     * <p>
     * 从本地缓存读取已启用的工具名列表，供 {@link IAdminService#listTools} 构建 ToolVO 列表使用。
     * 若缓存为空则先触发一次全量刷新。
     *
     * @return 已启用的工具名列表（按字母序排序）
     */
    public List<String> getEnabledTools() {
        if (toolEnabledMap.isEmpty()) {
            refreshEnabledTools();
        }
        List<String> enabledList = new ArrayList<>();
        for (Map.Entry<String, Boolean> entry : toolEnabledMap.entrySet()) {
            if (Boolean.TRUE.equals(entry.getValue())) {
                enabledList.add(entry.getKey());
            }
        }
        Collections.sort(enabledList);
        return enabledList;
    }

    /**
     * 获取全量已注册的工具名列表（含禁用的工具）
     * <p>
     * 供 {@link IAdminService#listTools} 展示所有工具（含禁用状态）使用。
     *
     * @return 全量工具名列表（不可变）
     */
    public List<String> getAllToolNames() {
        return ALL_TOOL_NAMES;
    }

    /**
     * 获取已启用的工具 Bean 实例列表
     * <p>
     * 供 {@code ChatClient.prompt().tools(Object...)} 调用，
     * Spring AI 通过 MethodToolCallbackProvider 自动扫描这些 Bean 的 @Tool 注解方法。
     * <p>
     * <b>调用时机</b>：{@link com.zmbdp.chat.service.service.impl.ChatServiceImpl#streamChat} 的 Step 4
     * 判断是否需要工具调用时使用。
     *
     * @return 已启用的工具 Bean 实例列表；无启用工具时返回空列表
     */
    public List<Object> getEnabledToolBeans() {
        List<String> enabledToolNames = getEnabledTools();
        if (CollectionUtils.isEmpty(enabledToolNames)) {
            return Collections.emptyList();
        }
        List<Object> beans = new ArrayList<>(enabledToolNames.size());
        for (String toolName : enabledToolNames) {
            Object bean = toolBeanMap.get(toolName);
            if (bean != null) {
                beans.add(bean);
            } else {
                log.warn("工具 Bean 未找到：toolName = {}（可能未正确注入或类未标注 @Component）", toolName);
            }
        }
        return beans;
    }

    /**
     * 根据工具名获取工具 Bean 实例
     * <p>
     * 供 {@link IAdminService#testTool} 测试工具调用时使用。
     *
     * @param toolName 工具名（@Tool 注解方法名）
     * @return 工具 Bean 实例；不存在返回 null
     */
    public Object getToolBean(String toolName) {
        if (!StringUtils.hasText(toolName)) {
            return null;
        }
        return toolBeanMap.get(toolName);
    }

    /**
     * 判断指定工具是否启用
     *
     * @param toolName 工具名（@Tool 注解方法的名称，如 readFile）
     * @return true=启用，false=禁用
     */
    public boolean isToolEnabled(String toolName) {
        if (!StringUtils.hasText(toolName)) {
            return false;
        }
        Boolean enabled = toolEnabledMap.get(toolName);
        if (enabled == null) {
            // 缓存未命中，触发单条刷新
            refreshTool(toolName, null);
            enabled = toolEnabledMap.get(toolName);
        }
        return Boolean.TRUE.equals(enabled);
    }

    /**
     * 全量刷新工具启用状态
     * <p>
     * 执行流程：
     * <ol>
     *     <li>构建 7 个工具的 configKey 列表（{@code ai.tool.{toolName}.enabled}）</li>
     *     <li>通过 Feign 调用 {@link ArgumentServiceApi#getByConfigKeys} 批量查询 sys_argument 表</li>
     *     <li>根据查询结果填充本地缓存（toolName → enabled）</li>
     *     <li>对未在 sys_argument 表中找到的工具，默认启用（与 db.sql 预置数据一致）</li>
     * </ol>
     * <p>
     * <b>默认启用策略</b>：Feign 调用失败或配置项不存在时，工具默认启用，
     * 与 db.sql 中 sys_argument 表预置的 7 条 {@code ai.tool.*.enabled=true} 数据保持一致。
     */
    public void refreshEnabledTools() {
        // 1. 构建 configKey → toolName 的映射
        Map<String, String> configKeyToToolName = new HashMap<>(ALL_TOOL_NAMES.size());
        List<String> configKeys = new ArrayList<>(ALL_TOOL_NAMES.size());
        for (String toolName : ALL_TOOL_NAMES) {
            String configKey = TOOL_CONFIG_KEY_PREFIX + toolName + TOOL_CONFIG_KEY_SUFFIX;
            configKeyToToolName.put(configKey, toolName);
            configKeys.add(configKey);
        }
        // 2. 批量查询 sys_argument 表
        Map<String, Boolean> newMap = new HashMap<>(ALL_TOOL_NAMES.size());
        try {
            List<ArgumentDTO> arguments = argumentServiceApi.getByConfigKeys(configKeys);
            if (!CollectionUtils.isEmpty(arguments)) {
                for (ArgumentDTO arg : arguments) {
                    if (arg == null || !StringUtils.hasText(arg.getConfigKey())) {
                        continue;
                    }
                    String toolName = configKeyToToolName.get(arg.getConfigKey());
                    if (toolName == null) {
                        continue;
                    }
                    boolean enabled = ENABLED_VALUE.equalsIgnoreCase(arg.getValue());
                    newMap.put(toolName, enabled);
                }
            }
        } catch (Exception e) {
            log.warn("批量查询工具启用状态失败，所有工具按默认启用处理：{}", e.getMessage());
        }
        // 3. 对未查到的工具，默认启用（与 db.sql 预置数据一致）
        for (String toolName : ALL_TOOL_NAMES) {
            if (!newMap.containsKey(toolName)) {
                newMap.put(toolName, Boolean.TRUE);
            }
        }
        // 4. 更新本地缓存
        toolEnabledMap.clear();
        toolEnabledMap.putAll(newMap);
        log.info("工具启用状态已刷新：{}", toolEnabledMap);
    }

    /**
     * 刷新工具注册（管理员更新工具启用状态后触发）
     * <p>
     * 当 enabled 参数为 null 时，从 sys_argument 表重新查询该工具的启用状态；
     * 当 enabled 参数非 null 时，直接更新本地缓存（由 IAdminService.updateToolConfig 调用）。
     *
     * @param toolName 工具名（@Tool 注解方法的名称）
     * @param enabled  是否启用（null 表示从远程重新查询）
     */
    public void refreshTool(String toolName, Boolean enabled) {
        if (!StringUtils.hasText(toolName)) {
            return;
        }
        if (enabled == null) {
            // 从远程查询
            String configKey = TOOL_CONFIG_KEY_PREFIX + toolName + TOOL_CONFIG_KEY_SUFFIX;
            try {
                ArgumentDTO argument = argumentServiceApi.getByConfigKey(configKey);
                if (argument != null && ENABLED_VALUE.equalsIgnoreCase(argument.getValue())) {
                    toolEnabledMap.put(toolName, Boolean.TRUE);
                } else {
                    toolEnabledMap.put(toolName, Boolean.FALSE);
                }
            } catch (Exception e) {
                log.warn("查询工具启用状态失败：toolName = {}, configKey = {}, error = {}",
                        toolName, configKey, e.getMessage());
                toolEnabledMap.put(toolName, Boolean.FALSE);
            }
        } else {
            // 直接更新缓存
            toolEnabledMap.put(toolName, enabled);
        }
        log.info("工具启用状态已刷新：toolName = {}, enabled = {}", toolName, toolEnabledMap.get(toolName));
    }
}