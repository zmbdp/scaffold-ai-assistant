package com.zmbdp.chat.service.tool;

import com.zmbdp.chat.service.service.INacosConfigService;
import com.zmbdp.common.core.utils.JsonUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.HashMap;
import java.util.Map;

/**
 * Nacos 配置查询工具
 * <p>
 * 查询 Nacos 配置项的内容，用于用户询问配置项含义或查看具体配置内容。
 * <p>
 * <b>dataId 统一约定</b>：大模型传入的 dataId 为不带环境后缀的 base dataId
 * （如 {@code share-redis}、{@code zmbdp-chat-service}），工具内部根据 env 参数自动拼接完整 dataId
 * （如 {@code share-redis-dev.yaml}）。与 CompareConfigTool 对 dataId 的定义保持一致。
 * <p>
 * <b>namespace 约定</b>：项目使用 {@code scaffold-ai-assistant-{env}} 作为命名空间。
 *
 * @author 稚名不带撇
 */
@Slf4j
@Component
public class NacosConfigTool {

    /**
     * 默认分组（项目所有配置均使用 DEFAULT_GROUP）
     */
    private static final String DEFAULT_GROUP = "DEFAULT_GROUP";

    /**
     * 命名空间前缀（与 bootstrap.yml 的 namespace 配置一致）
     * <p>
     * 项目约定 namespace = scaffold-ai-assistant-{env}，如 scaffold-ai-assistant-dev。
     */
    private static final String NAMESPACE_PREFIX = "scaffold-ai-assistant-";

    /**
     * 配置文件后缀（项目统一使用 yaml 格式）
     */
    private static final String CONFIG_FILE_SUFFIX = ".yaml";

    /**
     * Nacos 配置查询服务（封装 Nacos OpenAPI 调用）
     */
    @Autowired
    private INacosConfigService nacosConfigService;

    /**
     * 查询 Nacos 配置项的内容
     * <p>
     * 执行流程：
     * <ol>
     *     <li>根据 env 参数自动构建完整 dataId：{dataId}-{env}.yaml（如 share-redis-dev.yaml）</li>
     *     <li>构建 namespace：scaffold-ai-assistant-{env}</li>
     *     <li>调用 {@link INacosConfigService#getConfig} 查询配置</li>
     *     <li>如果配置不存在，返回提示信息</li>
     *     <li>返回配置内容</li>
     * </ol>
     *
     * @param dataId 配置ID（不带环境后缀，如 share-redis，工具内部自动拼接 -{env}.yaml）
     * @param group  分组（默认 DEFAULT_GROUP）
     * @param env    环境（dev/test/prd，必填，用于拼接完整 dataId）
     * @return 配置文件内容字符串；异常时返回 JSON 格式的错误信息
     */
    @org.springframework.ai.tool.annotation.Tool(
            description = "查询Nacos配置项的内容，参数dataId为配置ID（不带环境后缀，如share-redis），group为分组（默认DEFAULT_GROUP），env为环境（dev/test/prd）。用于查看某个配置项的具体内容。"
    )
    public String nacosConfig(String dataId, String group, String env) {
        // 1. 校验 dataId
        if (!StringUtils.hasText(dataId)) {
            return buildErrorJson("dataId不能为空");
        }
        // 2. 校验 env
        if (!StringUtils.hasText(env)) {
            return buildErrorJson("环境参数env不能为空");
        }
        try {
            // 3. 构建完整 dataId 和 namespace
            String fullDataId = dataId + "-" + env + CONFIG_FILE_SUFFIX;
            String namespace = NAMESPACE_PREFIX + env;
            String actualGroup = StringUtils.hasText(group) ? group : DEFAULT_GROUP;
            // 4. 调用 NacosConfigService 查询配置
            String content = nacosConfigService.getConfig(fullDataId, actualGroup, namespace);
            // 5. 配置不存在
            if (!StringUtils.hasText(content)) {
                log.info("Nacos配置不存在：dataId = {}, group = {}, namespace = {}", fullDataId, actualGroup, namespace);
                return buildErrorJson("配置不存在: " + fullDataId);
            }
            log.info("查询Nacos配置成功：dataId = {}, group = {}, namespace = {}", fullDataId, actualGroup, namespace);
            return content;
        } catch (Exception e) {
            log.warn("查询Nacos配置失败：dataId = {}, env = {}, error = {}", dataId, env, e.getMessage());
            return buildErrorJson("Nacos配置中心连接失败: " + e.getMessage());
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