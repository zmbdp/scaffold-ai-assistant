package com.zmbdp.chat.service.tool;

import com.zmbdp.chat.service.service.INacosConfigService;
import com.zmbdp.common.core.utils.FileUtil;
import com.zmbdp.common.core.utils.JsonUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 部署前静态配置检查工具
 * <p>
 * 部署前静态配置检查（仅检查配置文件完整性，不做运行时连接测试），
 * 用于用户部署前检查配置是否完整、格式是否正确。
 * <p>
 * <b>设计决策</b>：原设计包含"数据库连接检查"、"Redis配置检查"、"端口占用检查"等运行时检查项，
 * 但工具运行在 chat-service 容器内，无法检查目标部署服务器的端口占用；
 * 运行时连接检查应由 Spring Boot Actuator 健康检查负责。故简化为静态配置检查。
 * <p>
 * <b>检查项清单</b>：
 * <ol>
 *     <li>Nacos 共享配置完整性：检查 share-mysql/share-redis 等共享配置是否存在</li>
 *     <li>chat-service 专属配置：检查 zmbdp-chat-service-{env}.yaml 是否存在且包含必需配置项</li>
 *     <li>Dockerfile 配置：检查 FROM/EXPOSE/ENTRYPOINT 是否正确</li>
 *     <li>docker-compose 配置：检查服务定义、端口映射、环境变量</li>
 *     <li>环境变量完整性：检查 RUN_ENV、NACOS_ADDR 等必需环境变量</li>
 * </ol>
 *
 * @author 稚名不带撇
 */
@Slf4j
@Component
public class PreDeployCheckTool {

    /**
     * 默认分组
     */
    private static final String DEFAULT_GROUP = "DEFAULT_GROUP";

    /**
     * 命名空间前缀
     */
    private static final String NAMESPACE_PREFIX = "scaffold-ai-assistant-";

    /**
     * 配置文件后缀
     */
    private static final String CONFIG_FILE_SUFFIX = ".yaml";

    /**
     * 检查状态：通过
     */
    private static final String STATUS_PASS = "PASS";

    /**
     * 检查状态：失败
     */
    private static final String STATUS_FAIL = "FAIL";

    /**
     * chat-service 必需的共享配置列表（启动依赖）
     */
    private static final List<String> REQUIRED_SHARED_CONFIGS = Arrays.asList(
            "share-mysql",
            "share-redis",
            "share-token",
            "share-rabbitmq"
    );

    /**
     * chat-service 可选的共享配置列表（缺失会降级但不影响启动）
     */
    private static final List<String> OPTIONAL_SHARED_CONFIGS = Arrays.asList(
            "share-caffeine",
            "share-map",
            "share-filter",
            "share-log",
            "share-monitor",
            "share-xxljob"
    );

    /**
     * chat-service 专属配置中必需的配置项前缀
     */
    private static final List<String> REQUIRED_CONFIG_KEYS = Arrays.asList(
            "server:",
            "spring:",
            "ai:",
            "milvus:"
    );

    /**
     * Dockerfile 必需的指令
     */
    private static final List<String> REQUIRED_DOCKERFILE_INSTRUCTIONS = Arrays.asList(
            "FROM",
            "EXPOSE",
            "ENTRYPOINT"
    );

    /**
     * docker-compose 必需的环境变量
     */
    private static final List<String> REQUIRED_ENV_VARS = Arrays.asList(
            "RUN_ENV",
            "NACOS_ADDR"
    );

    /**
     * 支持的环境列表
     */
    private static final List<String> SUPPORTED_ENVS = Arrays.asList("dev", "test", "prd");

    /**
     * Dockerfile 相对路径（相对于 basePath）
     */
    private static final String DOCKERFILE_RELATIVE_PATH = "zmbdp-chat-service/Dockerfile";

    /**
     * docker-compose 文件相对路径模板（相对于 basePath）
     * <p>
     * dev/test 使用 docker-compose-mid.yml，prd 使用 docker-compose-app.yml
     */
    private static final String DOCKER_COMPOSE_DEV_TEST_PATH = "deploy/%s/app/docker-compose-mid.yml";

    /**
     * prd 环境的 docker-compose 文件相对路径
     */
    private static final String DOCKER_COMPOSE_PRD_PATH = "deploy/prd/vm1/app/docker-compose-app.yml";

    /**
     * Nacos 配置查询服务
     */
    @Autowired
    private INacosConfigService nacosConfigService;

    /**
     * 路径安全校验器（用于校验部署文件路径）
     */
    @Autowired
    private PathSecurityValidator pathSecurityValidator;

    /**
     * 项目根路径（从 Nacos 配置 {@code knowledge.base-path} 读取）
     * <p>
     * 用于定位 Dockerfile 和 docker-compose 文件。
     */
    @Value("${knowledge.base-path:}")
    private String basePath;

    /**
     * 部署前静态配置检查
     * <p>
     * 执行流程：
     * <ol>
     *     <li>校验 env 参数</li>
     *     <li>检查 Nacos 共享配置完整性（通过 INacosConfigService 读取）</li>
     *     <li>检查 chat-service 专属配置（zmbdp-chat-service-{env}.yaml）</li>
     *     <li>检查 Dockerfile 配置（读取 Dockerfile 文件）</li>
     *     <li>检查 docker-compose 配置（读取 docker-compose 文件）</li>
     *     <li>汇总检查结果，返回检查报告</li>
     * </ol>
     *
     * @param env 目标环境（dev/test/prd）
     * @return 检查报告 JSON；异常时返回错误 JSON
     */
    @Tool(
            description = "部署前静态配置检查，参数env为目标环境（dev/test/prd）。检查Nacos配置项完整性、Dockerfile和docker-compose配置是否合理。不做运行时连接测试。"
    )
    public String preDeployCheck(String env) {
        // 1. 校验 env 参数
        if (!StringUtils.hasText(env)) {
            return buildErrorJson("目标环境不能为空");
        }
        if (!SUPPORTED_ENVS.contains(env)) {
            return buildErrorJson("无效的环境: " + env + "，支持 dev/test/prd");
        }
        log.info("开始部署前检查：env = {}", env);
        // 2. 执行各项检查
        List<Map<String, Object>> checkItems = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        try {
            // 检查项1：Nacos 共享配置完整性
            checkSharedConfigs(env, checkItems, warnings, errors);
            // 检查项2：chat-service 专属配置
            checkChatServiceConfig(env, checkItems, warnings, errors);
            // 检查项3：Dockerfile 配置
            checkDockerfile(checkItems, warnings, errors);
            // 检查项4 & 5：docker-compose 配置 + 环境变量完整性
            checkDockerCompose(env, checkItems, warnings, errors);
        } catch (Exception e) {
            log.warn("部署前检查异常：env = {}, error = {}", env, e.getMessage(), e);
            return buildErrorJson("部署前检查失败: " + e.getMessage());
        }
        // 3. 汇总结果
        long passCount = checkItems.stream()
                .filter(item -> STATUS_PASS.equals(item.get("status"))).count();
        long failCount = checkItems.stream()
                .filter(item -> STATUS_FAIL.equals(item.get("status"))).count();
        String summary = passCount + "项通过，" + failCount + "项失败";
        Map<String, Object> result = new LinkedHashMap<>(4);
        result.put("checkItems", checkItems);
        result.put("summary", summary);
        result.put("warnings", warnings);
        result.put("errors", errors);
        log.info("部署前检查完成：env = {}, summary = {}", env, summary);
        return JsonUtil.classToJson(result);
    }

    /**
     * 检查 Nacos 共享配置完整性
     * <p>
     * 检查 chat-service 依赖的共享配置（share-mysql/share-redis 等）在目标环境是否存在。
     *
     * @param env        目标环境
     * @param checkItems 检查项列表
     * @param warnings   警告列表
     * @param errors     错误列表
     */
    private void checkSharedConfigs(String env, List<Map<String, Object>> checkItems,
                                     List<String> warnings, List<String> errors) {
        List<String> missingRequired = new ArrayList<>();
        List<String> missingOptional = new ArrayList<>();
        // 检查必需共享配置
        for (String configName : REQUIRED_SHARED_CONFIGS) {
            if (!isConfigExists(configName, env)) {
                missingRequired.add(configName + "-" + env + ".yaml");
            }
        }
        // 检查可选共享配置
        for (String configName : OPTIONAL_SHARED_CONFIGS) {
            if (!isConfigExists(configName, env)) {
                missingOptional.add(configName + "-" + env + ".yaml");
            }
        }
        // 构建检查结果
        boolean passed = missingRequired.isEmpty();
        Map<String, Object> checkItem = new LinkedHashMap<>(4);
        checkItem.put("item", "Nacos共享配置完整性");
        checkItem.put("status", passed ? STATUS_PASS : STATUS_FAIL);
        StringBuilder details = new StringBuilder();
        details.append("必需配置: ").append(REQUIRED_SHARED_CONFIGS.size()).append("项");
        if (!missingRequired.isEmpty()) {
            details.append("，缺失: ").append(String.join(", ", missingRequired));
        }
        details.append("；可选配置: ").append(OPTIONAL_SHARED_CONFIGS.size()).append("项");
        if (!missingOptional.isEmpty()) {
            details.append("，缺失: ").append(String.join(", ", missingOptional));
        }
        checkItem.put("details", details.toString());
        checkItem.put("suggestion", passed ? "" : "请通过 Nacos 控制台补充缺失的必需共享配置");
        checkItems.add(checkItem);
        // 收集错误和警告
        for (String missing : missingRequired) {
            errors.add(missing + " 不存在，chat-service 启动会失败");
        }
        for (String missing : missingOptional) {
            warnings.add(missing + " 未配置，相关功能将不可用或降级");
        }
    }

    /**
     * 检查 chat-service 专属配置
     * <p>
     * 检查 zmbdp-chat-service-{env}.yaml 是否存在且包含必需配置项（port、spring.ai、milvus）。
     *
     * @param env        目标环境
     * @param checkItems 检查项列表
     * @param warnings   警告列表
     * @param errors     错误列表
     */
    private void checkChatServiceConfig(String env, List<Map<String, Object>> checkItems,
                                         List<String> warnings, List<String> errors) {
        String configName = "zmbdp-chat-service";
        String content = loadConfigContent(configName, env);
        Map<String, Object> checkItem = new LinkedHashMap<>(4);
        checkItem.put("item", "chat-service专属配置");
        if (!StringUtils.hasText(content)) {
            checkItem.put("status", STATUS_FAIL);
            checkItem.put("details", "zmbdp-chat-service-" + env + ".yaml 不存在");
            checkItem.put("suggestion", "请创建 zmbdp-chat-service-" + env + ".yaml 配置文件");
            errors.add("zmbdp-chat-service-" + env + ".yaml 不存在，chat-service 无法启动");
        } else {
            // 检查必需配置项
            List<String> missingKeys = new ArrayList<>();
            for (String requiredKey : REQUIRED_CONFIG_KEYS) {
                if (!content.contains(requiredKey)) {
                    missingKeys.add(requiredKey);
                }
            }
            boolean passed = missingKeys.isEmpty();
            checkItem.put("status", passed ? STATUS_PASS : STATUS_FAIL);
            StringBuilder details = new StringBuilder();
            details.append("配置文件存在，大小: ").append(content.length()).append(" 字节");
            if (!missingKeys.isEmpty()) {
                details.append("，缺失配置项: ").append(String.join(", ", missingKeys));
            }
            checkItem.put("details", details.toString());
            checkItem.put("suggestion", passed ? "" : "请补充缺失的配置项");
            if (!passed) {
                errors.add("zmbdp-chat-service-" + env + ".yaml 缺失配置项: " + String.join(", ", missingKeys));
            }
        }
        checkItems.add(checkItem);
    }

    /**
     * 检查 Dockerfile 配置
     * <p>
     * 读取 Dockerfile，检查 FROM、EXPOSE、ENTRYPOINT 指令是否存在。
     *
     * @param checkItems 检查项列表
     * @param warnings   警告列表
     * @param errors     错误列表
     */
    private void checkDockerfile(List<Map<String, Object>> checkItems,
                                  List<String> warnings, List<String> errors) {
        String dockerfilePath = buildBasePath(DOCKERFILE_RELATIVE_PATH);
        Map<String, Object> checkItem = new LinkedHashMap<>(4);
        checkItem.put("item", "Dockerfile配置");
        String content = readDeployFile(dockerfilePath);
        if (content == null) {
            checkItem.put("status", STATUS_FAIL);
            checkItem.put("details", "Dockerfile 不存在: " + dockerfilePath);
            checkItem.put("suggestion", "请创建 zmbdp-chat-service/Dockerfile");
            errors.add("Dockerfile 不存在，无法构建镜像");
        } else {
            // 检查必需指令
            List<String> missingInstructions = new ArrayList<>();
            for (String instruction : REQUIRED_DOCKERFILE_INSTRUCTIONS) {
                if (!content.contains(instruction)) {
                    missingInstructions.add(instruction);
                }
            }
            boolean passed = missingInstructions.isEmpty();
            checkItem.put("status", passed ? STATUS_PASS : STATUS_FAIL);
            StringBuilder details = new StringBuilder();
            details.append("Dockerfile 存在，").append(content.split("\n").length).append(" 行");
            if (!missingInstructions.isEmpty()) {
                details.append("，缺失指令: ").append(String.join(", ", missingInstructions));
            }
            checkItem.put("details", details.toString());
            checkItem.put("suggestion", passed ? "" : "请补充缺失的 Dockerfile 指令");
            if (!passed) {
                warnings.add("Dockerfile 缺失指令: " + String.join(", ", missingInstructions));
            }
        }
        checkItems.add(checkItem);
    }

    /**
     * 检查 docker-compose 配置和环境变量完整性
     * <p>
     * 读取 docker-compose 文件，检查服务定义和环境变量。
     *
     * @param env        目标环境
     * @param checkItems 检查项列表
     * @param warnings   警告列表
     * @param errors     错误列表
     */
    private void checkDockerCompose(String env, List<Map<String, Object>> checkItems,
                                     List<String> warnings, List<String> errors) {
        String composePath = buildDockerComposePath(env);
        String content = readDeployFile(composePath);
        // 检查项4：docker-compose 配置
        Map<String, Object> composeCheckItem = new LinkedHashMap<>(4);
        composeCheckItem.put("item", "docker-compose配置");
        if (content == null) {
            composeCheckItem.put("status", STATUS_FAIL);
            composeCheckItem.put("details", "docker-compose 文件不存在: " + composePath);
            composeCheckItem.put("suggestion", "请创建 docker-compose 配置文件");
            errors.add("docker-compose 文件不存在: " + composePath);
        } else {
            boolean hasServices = content.contains("services:");
            boolean hasChatService = content.contains("zmbdp-chat-service");
            boolean passed = hasServices && hasChatService;
            composeCheckItem.put("status", passed ? STATUS_PASS : STATUS_FAIL);
            StringBuilder details = new StringBuilder();
            details.append("文件存在，大小: ").append(content.length()).append(" 字节");
            if (!hasServices) {
                details.append("，缺失 services 段");
            }
            if (!hasChatService) {
                details.append("，未定义 zmbdp-chat-service 服务");
            }
            composeCheckItem.put("details", details.toString());
            composeCheckItem.put("suggestion", passed ? "" : "请补充 docker-compose 配置");
            if (!passed) {
                warnings.add("docker-compose 配置不完整: " + details);
            }
        }
        checkItems.add(composeCheckItem);
        // 检查项5：环境变量完整性（基于 docker-compose 内容）
        Map<String, Object> envCheckItem = new LinkedHashMap<>(4);
        envCheckItem.put("item", "环境变量完整性");
        if (content == null) {
            envCheckItem.put("status", STATUS_FAIL);
            envCheckItem.put("details", "无法检查环境变量（docker-compose 文件不存在）");
            envCheckItem.put("suggestion", "请先创建 docker-compose 配置文件");
        } else {
            List<String> missingEnvVars = new ArrayList<>();
            for (String envVar : REQUIRED_ENV_VARS) {
                if (!content.contains(envVar)) {
                    missingEnvVars.add(envVar);
                }
            }
            boolean passed = missingEnvVars.isEmpty();
            envCheckItem.put("status", passed ? STATUS_PASS : STATUS_FAIL);
            StringBuilder details = new StringBuilder();
            details.append("必需环境变量: ").append(String.join(", ", REQUIRED_ENV_VARS));
            if (!missingEnvVars.isEmpty()) {
                details.append("，缺失: ").append(String.join(", ", missingEnvVars));
            }
            envCheckItem.put("details", details.toString());
            envCheckItem.put("suggestion", passed ? "" : "请在 docker-compose 的 environment 段补充缺失的环境变量");
            if (!passed) {
                errors.add("docker-compose 缺失环境变量: " + String.join(", ", missingEnvVars));
            }
        }
        checkItems.add(envCheckItem);
    }

    /**
     * 判断指定配置在目标环境是否存在
     *
     * @param configName 配置名（不带环境后缀，如 share-redis）
     * @param env        目标环境
     * @return true=存在，false=不存在
     */
    private boolean isConfigExists(String configName, String env) {
        return StringUtils.hasText(loadConfigContent(configName, env));
    }

    /**
     * 加载指定环境的配置内容
     *
     * @param configName 配置名（不带环境后缀）
     * @param env        环境
     * @return 配置内容；不存在返回 null
     */
    private String loadConfigContent(String configName, String env) {
        String fullDataId = configName + "-" + env + CONFIG_FILE_SUFFIX;
        String namespace = NAMESPACE_PREFIX + env;
        return nacosConfigService.getConfig(fullDataId, DEFAULT_GROUP, namespace);
    }

    /**
     * 读取部署文件内容（受路径白名单限制）
     *
     * @param filePath 文件路径
     * @return 文件内容；文件不存在或路径不合法返回 null
     */
    private String readDeployFile(String filePath) {
        try {
            pathSecurityValidator.validatePath(filePath);
            if (!FileUtil.exist(filePath)) {
                log.debug("部署文件不存在：{}", filePath);
                return null;
            }
            return FileUtil.readUtf8String(filePath);
        } catch (SecurityException e) {
            log.warn("部署文件路径校验失败：filePath = {}, error = {}", filePath, e.getMessage());
            return null;
        } catch (Exception e) {
            log.warn("读取部署文件失败：filePath = {}, error = {}", filePath, e.getMessage());
            return null;
        }
    }

    /**
     * 基于 basePath 构建完整路径
     *
     * @param relativePath 相对路径
     * @return 完整路径
     */
    private String buildBasePath(String relativePath) {
        if (!StringUtils.hasText(basePath)) {
            return relativePath;
        }
        String normalizedBase = basePath.replace('\\', '/');
        String normalizedRelative = relativePath.replace('\\', '/');
        if (normalizedBase.endsWith("/")) {
            return normalizedBase + normalizedRelative;
        }
        return normalizedBase + "/" + normalizedRelative;
    }

    /**
     * 构建 docker-compose 文件路径
     * <p>
     * dev/test 使用 docker-compose-mid.yml，prd 使用 docker-compose-app.yml。
     *
     * @param env 环境
     * @return docker-compose 文件完整路径
     */
    private String buildDockerComposePath(String env) {
        String relativePath;
        if ("prd".equals(env)) {
            relativePath = DOCKER_COMPOSE_PRD_PATH;
        } else {
            relativePath = String.format(DOCKER_COMPOSE_DEV_TEST_PATH, env);
        }
        return buildBasePath(relativePath);
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