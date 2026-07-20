package com.zmbdp.chat.service.tool;

import com.zmbdp.chat.service.service.INacosConfigService;
import com.zmbdp.common.core.utils.JsonUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.yaml.snakeyaml.Yaml;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/**
 * 配置差异对比工具
 * <p>
 * 对比不同环境的 Nacos 配置差异，用于用户询问环境差异或排查配置不一致问题。
 * <p>
 * <b>实现方式</b>：分别获取两个环境的配置内容，解析 YAML 结构为扁平化的 key-value Map，
 * 对比所有 key 的值差异，返回差异列表。
 * <p>
 * <b>dataId 约定</b>：与 {@link NacosConfigTool} 一致，大模型传入的 dataId 为不带环境后缀的 base dataId，
 * 工具内部自动拼接 {@code -{env}.yaml}。
 *
 * @author 稚名不带撇
 */
@Slf4j
@Component
public class CompareConfigTool {

    /**
     * 默认分组
     */
    private static final String DEFAULT_GROUP = "DEFAULT_GROUP";

    /**
     * 命名空间前缀（与 NacosConfigTool 一致）
     */
    private static final String NAMESPACE_PREFIX = "scaffold-ai-assistant-";

    /**
     * 配置文件后缀
     */
    private static final String CONFIG_FILE_SUFFIX = ".yaml";

    /**
     * 值不存在时的占位符
     */
    private static final String NOT_EXIST_PLACEHOLDER = "（不存在）";

    /**
     * Nacos 配置查询服务（与 NacosConfigTool 共用）
     */
    @Autowired
    private INacosConfigService nacosConfigService;

    /**
     * 对比不同环境的配置差异
     * <p>
     * 执行流程：
     * <ol>
     *     <li>构建两个环境的配置文件名（如 share-redis-dev.yaml 和 share-redis-prd.yaml）</li>
     *     <li>调用 {@link INacosConfigService#getConfig} 分别读取两个环境的配置内容</li>
     *     <li>解析 YAML 结构，扁平化为 key-value Map</li>
     *     <li>对比差异，收集 key 不存在或值不同的项</li>
     *     <li>使用 {@link JsonUtil#classToJson} 序列化差异结果为 JSON 字符串并返回</li>
     * </ol>
     *
     * @param dataId 配置ID（不带环境后缀，如 share-redis，工具内部自动拼接 -{env}.yaml）
     * @param env1   环境1（dev/test/prd）
     * @param env2   环境2（dev/test/prd）
     * @return 配置差异对比 JSON；异常时返回错误 JSON
     */
    @org.springframework.ai.tool.annotation.Tool(
            description = "对比不同环境的配置差异，参数dataId为配置ID（不带环境后缀，如share-redis），env1和env2为要对比的两个环境（dev/test/prd），工具内部自动拼接 -{env}.yaml。用于排查环境差异问题。"
    )
    public String compareConfig(String dataId, String env1, String env2) {
        // 1. 校验 dataId
        if (!StringUtils.hasText(dataId)) {
            return buildErrorJson("dataId不能为空");
        }
        // 2. 校验 env 参数
        if (!StringUtils.hasText(env1) || !StringUtils.hasText(env2)) {
            return buildErrorJson("环境参数env1和env2不能为空");
        }
        // 3. 校验两个环境不能相同
        if (env1.equals(env2)) {
            return buildErrorJson("对比的两个环境不能相同");
        }
        try {
            // 4. 分别读取两个环境的配置
            String content1 = loadConfig(dataId, env1);
            String content2 = loadConfig(dataId, env2);
            // 5. 校验配置是否存在
            if (!StringUtils.hasText(content1)) {
                return buildErrorJson("配置不存在: " + dataId + "（" + env1 + "）");
            }
            if (!StringUtils.hasText(content2)) {
                return buildErrorJson("配置不存在: " + dataId + "（" + env2 + "）");
            }
            // 6. 解析 YAML 并对比差异
            Map<String, Object> flatten1 = parseAndFlatten(content1, env1);
            Map<String, Object> flatten2 = parseAndFlatten(content2, env2);
            List<Map<String, Object>> differences = computeDifferences(flatten1, flatten2, env1, env2);
            // 7. 构建返回结果
            Map<String, Object> result = new HashMap<>(2);
            result.put("differences", differences);
            result.put("total", differences.size());
            log.info("配置对比完成：dataId = {}, env1 = {}, env2 = {}, differences = {}",
                    dataId, env1, env2, differences.size());
            return JsonUtil.classToJson(result);
        } catch (Exception e) {
            log.warn("配置对比失败：dataId = {}, env1 = {}, env2 = {}, error = {}",
                    dataId, env1, env2, e.getMessage());
            return buildErrorJson("配置对比失败: " + e.getMessage());
        }
    }

    /**
     * 加载指定环境的配置内容
     *
     * @param dataId base dataId（不带环境后缀）
     * @param env    环境（dev/test/prd）
     * @return 配置内容；配置不存在返回 null
     */
    private String loadConfig(String dataId, String env) {
        String fullDataId = dataId + "-" + env + CONFIG_FILE_SUFFIX;
        String namespace = NAMESPACE_PREFIX + env;
        return nacosConfigService.getConfig(fullDataId, DEFAULT_GROUP, namespace);
    }

    /**
     * 解析 YAML 内容并扁平化为 key-value Map
     * <p>
     * 将嵌套的 YAML 结构转换为扁平化的 key-value Map，key 用点号分隔
     * （如 {@code spring.data.redis.host} -> {@code localhost}）。
     *
     * @param content YAML 配置内容
     * @param env     环境标识（用于错误日志）
     * @return 扁平化的 key-value Map
     * @throws RuntimeException YAML 解析失败时抛出
     */
    private Map<String, Object> parseAndFlatten(String content, String env) {
        try {
            Yaml yaml = new Yaml();
            Map<String, Object> loaded = yaml.load(content);
            return flatten(loaded, "");
        } catch (Exception e) {
            log.warn("YAML解析失败：env = {}, error = {}", env, e.getMessage());
            throw new RuntimeException("配置文件格式错误: " + e.getMessage(), e);
        }
    }

    /**
     * 扁平化嵌套 Map
     * <p>
     * 递归遍历嵌套 Map，将所有叶子节点扁平化为 key-value 对，key 用点号分隔。
     * List 类型不展开，直接作为值存储。
     *
     * @param source  源 Map
     * @param prefix  key 前缀（用于递归）
     * @return 扁平化后的 Map
     */
    private Map<String, Object> flatten(Map<String, Object> source, String prefix) {
        Map<String, Object> result = new LinkedHashMap<>();
        if (source == null) {
            return result;
        }
        for (Map.Entry<String, Object> entry : source.entrySet()) {
            String key = prefix.isEmpty() ? entry.getKey() : prefix + "." + entry.getKey();
            Object value = entry.getValue();
            if (value instanceof Map) {
                @SuppressWarnings("unchecked")
                Map<String, Object> nestedMap = (Map<String, Object>) value;
                result.putAll(flatten(nestedMap, key));
            } else {
                result.put(key, value);
            }
        }
        return result;
    }

    /**
     * 计算两个扁平化 Map 的差异
     * <p>
     * 对比所有 key，收集 key 不存在或值不同的项。
     *
     * @param flatten1 环境1的扁平化 Map
     * @param flatten2 环境2的扁平化 Map
     * @param env1     环境1标识
     * @param env2     环境2标识
     * @return 差异列表
     */
    private List<Map<String, Object>> computeDifferences(Map<String, Object> flatten1,
                                                          Map<String, Object> flatten2,
                                                          String env1, String env2) {
        // 收集所有 key（用 TreeSet 保证输出顺序稳定）
        Set<String> allKeys = new TreeSet<>();
        allKeys.addAll(flatten1.keySet());
        allKeys.addAll(flatten2.keySet());
        List<Map<String, Object>> differences = new ArrayList<>();
        for (String key : allKeys) {
            Object value1 = flatten1.get(key);
            Object value2 = flatten2.get(key);
            if (!Objects.equals(value1, value2)) {
                Map<String, Object> diff = new LinkedHashMap<>(4);
                diff.put("key", key);
                diff.put("env1Value", value1 != null ? String.valueOf(value1) : NOT_EXIST_PLACEHOLDER);
                diff.put("env2Value", value2 != null ? String.valueOf(value2) : NOT_EXIST_PLACEHOLDER);
                diff.put("env1", env1);
                diff.put("env2", env2);
                differences.add(diff);
            }
        }
        return differences;
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