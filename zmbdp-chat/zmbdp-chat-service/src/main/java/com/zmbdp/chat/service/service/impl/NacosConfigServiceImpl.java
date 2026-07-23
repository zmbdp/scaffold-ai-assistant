package com.zmbdp.chat.service.service.impl;

import com.zmbdp.chat.service.service.INacosConfigService;
import com.zmbdp.common.core.utils.FileUtil;
import com.zmbdp.common.domain.domain.ResultCode;
import com.zmbdp.common.domain.exception.ServiceException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.context.config.annotation.RefreshScope;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Nacos 配置服务实现类
 * <p>
 * <b>改造说明</b>：原实现通过 Nacos OpenAPI（{@code GET /nacos/v1/cs/configs}）远程查询配置，
 * 但脚手架的所有 Nacos 配置均以 yaml 文件形式存放于 frameworkjava 脚手架的
 * {@code deploy/{env}/res/sql/DEFAULT_GROUP/} 目录下（部署时导入 Nacos）。
 * 运行时再通过 OpenAPI 回头查 Nacos 多此一举，故改为直接读取本地 yaml 文件。
 * <p>
 * <b>路径规律</b>（{@link #resolveConfigDir}）：
 * <ul>
 *     <li>dev：{@code {base-path}/deploy/dev/res/sql/DEFAULT_GROUP/}</li>
 *     <li>test：{@code {base-path}/deploy/test/res/sql/nacos_config/DEFAULT_GROUP/}（多一层 nacos_config）</li>
 *     <li>prd：{@code {base-path}/deploy/prd/vm1/res/sql/DEFAULT_GROUP/}（多一层 vm1）</li>
 * </ul>
 * <p>
 * <b>调用方</b>：NacosConfigTool、CompareConfigTool、PreDeployCheckTool 三个 Agent 工具。
 * <p>
 * <b>接口兼容</b>：{@link INacosConfigService} 接口签名保持不变（getConfig/listConfigs），
 * 三个工具类无需任何改动。namespace 参数仍按 {@code scaffold-ai-assistant-{env}} 约定传入，
 * 本实现从中解析 env 后拼接本地文件路径。
 *
 * @author 稚名不带撇
 */
@Slf4j
@Service
@RefreshScope
public class NacosConfigServiceImpl implements INacosConfigService {

    /**
     * 默认分组（脚手架所有配置均使用 DEFAULT_GROUP）
     */
    private static final String DEFAULT_GROUP = "DEFAULT_GROUP";

    /**
     * 命名空间前缀（与 NacosConfigTool/CompareConfigTool/PreDeployCheckTool 约定一致）
     * <p>
     * 项目约定 namespace = scaffold-ai-assistant-{env}，如 scaffold-ai-assistant-dev。
     */
    private static final String NAMESPACE_PREFIX = "scaffold-ai-assistant-";

    /**
     * dev 环境的 DEFAULT_GROUP 相对路径（相对于 base-path）
     */
    private static final String DEFAULT_GROUP_RELATIVE_DEV = "deploy/dev/res/sql/DEFAULT_GROUP";

    /**
     * test 环境的 DEFAULT_GROUP 相对路径（test 环境多一层 nacos_config）
     */
    private static final String DEFAULT_GROUP_RELATIVE_TEST = "deploy/test/res/sql/nacos_config/DEFAULT_GROUP";

    /**
     * prd 环境的 DEFAULT_GROUP 相对路径（prd 环境多一层 vm1）
     */
    private static final String DEFAULT_GROUP_RELATIVE_PRD = "deploy/prd/vm1/res/sql/DEFAULT_GROUP";

    /**
     * 知识库根路径（从 Nacos 配置 {@code knowledge.base-path} 读取）
     * <p>
     * dev 指向 {@code d:/GitHub/frameworkjava}；test/prd 容器内固定为 {@code /knowledge}。
     */
    @Value("${knowledge.base-path:}")
    private String basePath;

    /*=============================================    远程调用（实为本地文件读取）    =============================================*/

    /**
     * 获取指定配置内容
     * <p>
     * 执行流程：
     * <ol>
     *     <li>从 namespace 解析 env（frameworkjava-{env} → {env}）</li>
     *     <li>根据 env 拼接 DEFAULT_GROUP 目录路径</li>
     *     <li>拼接完整文件路径：{@code {configDir}/{dataId}}</li>
     *     <li>读取文件内容返回；文件不存在返回 {@code null}</li>
     * </ol>
     *
     * @param dataId    配置ID（完整文件名，如 share-redis-dev.yaml，由工具层拼接环境后缀）
     * @param group     分组（仅作记录，实际脚手架所有配置均在 DEFAULT_GROUP 下）
     * @param namespace 命名空间（scaffold-ai-assistant-{env}，从中解析 env）
     * @return 配置内容；配置不存在返回 {@code null}
     */
    @Override
    public String getConfig(String dataId, String group, String namespace) {
        if (!StringUtils.hasText(dataId)) {
            throw new ServiceException("dataId 不能为空", ResultCode.INVALID_PARA.getCode());
        }
        // 1. 从 namespace 解析 env
        String env = resolveEnvFromNamespace(namespace);
        if (env == null) {
            log.warn("无法从 namespace 解析 env：namespace = {}", namespace);
            return null;
        }
        // 2. 拼接配置文件路径
        String configDir = resolveConfigDir(env);
        if (configDir == null) {
            log.warn("不支持的 env：{}（仅支持 dev/test/prd）", env);
            return null;
        }
        String filePath = configDir + File.separator + dataId;
        // 3. 读取文件
        try {
            if (!FileUtil.exist(filePath)) {
                log.debug("Nacos 配置文件不存在：dataId = {}, env = {}, path = {}", dataId, env, filePath);
                return null;
            }
            String content = FileUtil.readUtf8String(filePath);
            log.debug("读取 Nacos 配置成功：dataId = {}, env = {}, namespace = {}", dataId, env, namespace);
            return content;
        } catch (Exception e) {
            log.warn("读取 Nacos 配置失败：dataId = {}, env = {}, path = {}, error = {}",
                    dataId, env, filePath, e.getMessage());
            return null;
        }
    }

    /**
     * 列出指定分组下的所有配置项
     * <p>
     * 列出 DEFAULT_GROUP 目录下所有 yaml 文件，返回配置项列表（含 dataId、group、content）。
     * <p>
     * <b>说明</b>：当前三个 Agent 工具（NacosConfigTool/CompareConfigTool/PreDeployCheckTool）
     * 均未调用本方法，但为保持接口完整仍予实现，便于未来扩展（如新增"列出所有配置"工具）。
     *
     * @param group     分组（仅 DEFAULT_GROUP 有效）
     * @param namespace 命名空间（scaffold-ai-assistant-{env}）
     * @return 配置项列表（含 dataId、group、content 等字段）；目录不存在返回空列表
     */
    @Override
    public List<Map<String, Object>> listConfigs(String group, String namespace) {
        // 1. 从 namespace 解析 env
        String env = resolveEnvFromNamespace(namespace);
        if (env == null) {
            log.warn("无法从 namespace 解析 env：namespace = {}", namespace);
            return new ArrayList<>();
        }
        // 2. 拼接配置目录
        String configDir = resolveConfigDir(env);
        if (configDir == null) {
            log.warn("不支持的 env：{}", env);
            return new ArrayList<>();
        }
        // 3. 列出目录下所有 yaml 文件
        File dir = new File(configDir);
        if (!dir.exists() || !dir.isDirectory()) {
            log.warn("配置目录不存在：{}", configDir);
            return new ArrayList<>();
        }
        List<File> yamlFiles = FileUtil.loopFiles(dir, file -> {
            if (!file.isFile()) {
                return false;
            }
            String name = file.getName().toLowerCase();
            return name.endsWith(".yaml") || name.endsWith(".yml");
        });
        if (yamlFiles.isEmpty()) {
            return new ArrayList<>();
        }
        // 4. 构建配置项列表
        String actualGroup = StringUtils.hasText(group) ? group : DEFAULT_GROUP;
        List<Map<String, Object>> result = new ArrayList<>(yamlFiles.size());
        for (File file : yamlFiles) {
            Map<String, Object> item = new LinkedHashMap<>(4);
            item.put("dataId", file.getName());
            item.put("group", actualGroup);
            try {
                item.put("content", FileUtil.readUtf8String(file));
            } catch (Exception e) {
                log.warn("读取配置文件失败：{}, error = {}", file.getAbsolutePath(), e.getMessage());
                item.put("content", "");
            }
            result.add(item);
        }
        log.info("列出 Nacos 配置完成：env = {}, group = {}, 数量 = {}", env, actualGroup, result.size());
        return result;
    }

    /*=============================================    私有方法    =============================================*/

    /**
     * 从 namespace 解析 env
     * <p>
     * namespace 格式约定为 {@code scaffold-ai-assistant-{env}}，
     * 解析出 {@code env}（dev/test/prd）。
     *
     * @param namespace 命名空间
     * @return 环境标识；namespace 为空或不匹配前缀返回 {@code null}
     */
    private String resolveEnvFromNamespace(String namespace) {
        if (!StringUtils.hasText(namespace)) {
            return null;
        }
        if (!namespace.startsWith(NAMESPACE_PREFIX)) {
            return null;
        }
        return namespace.substring(NAMESPACE_PREFIX.length());
    }

    /**
     * 根据 env 拼接 DEFAULT_GROUP 目录的完整路径
     * <p>
     * 路径规律：
     * <ul>
     *     <li>dev：{@code {base-path}/deploy/dev/res/sql/DEFAULT_GROUP}</li>
     *     <li>test：{@code {base-path}/deploy/test/res/sql/nacos_config/DEFAULT_GROUP}</li>
     *     <li>prd：{@code {base-path}/deploy/prd/vm1/res/sql/DEFAULT_GROUP}</li>
     * </ul>
     *
     * @param env 环境标识（dev/test/prd）
     * @return DEFAULT_GROUP 目录完整路径；env 不支持或 base-path 未配置返回 {@code null}
     */
    private String resolveConfigDir(String env) {
        if (!StringUtils.hasText(basePath)) {
            log.warn("knowledge.base-path 未配置，无法定位 Nacos 配置目录");
            return null;
        }
        String relativePath;
        switch (env) {
            case "dev":
                relativePath = DEFAULT_GROUP_RELATIVE_DEV;
                break;
            case "test":
                relativePath = DEFAULT_GROUP_RELATIVE_TEST;
                break;
            case "prd":
                relativePath = DEFAULT_GROUP_RELATIVE_PRD;
                break;
            default:
                return null;
        }
        // 统一路径分隔符为 /，兼容 Windows 和 Linux
        String normalizedBase = basePath.replace('\\', '/');
        if (normalizedBase.endsWith("/")) {
            return normalizedBase + relativePath;
        }
        return normalizedBase + "/" + relativePath;
    }
}
