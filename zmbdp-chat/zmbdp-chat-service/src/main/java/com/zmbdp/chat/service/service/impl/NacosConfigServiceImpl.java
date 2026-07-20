package com.zmbdp.chat.service.service.impl;

import com.zmbdp.chat.service.service.INacosConfigService;
import com.zmbdp.common.core.utils.JsonUtil;
import com.zmbdp.common.domain.exception.ServiceException;
import com.zmbdp.common.domain.domain.ResultCode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.context.config.annotation.RefreshScope;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Nacos 配置服务实现类
 * <p>
 * 封装 Nacos 配置中心的 HTTP API 调用，支持按 dataId/group/namespace 查询配置内容。
 * <p>
 * <b>调用方式</b>：Nacos OpenAPI（{@code GET /nacos/v1/cs/configs}）。
 * <p>
 * <b>调用方</b>：NacosConfigTool、CompareConfigTool、PreDeployCheckTool 三个 Agent 工具。
 *
 * @author 稚名不带撇
 */
@Slf4j
@Service
@RefreshScope
public class NacosConfigServiceImpl implements INacosConfigService {

    /**
     * Nacos OpenAPI 路径：获取单个配置
     */
    private static final String API_GET_CONFIG = "/nacos/v1/cs/configs";

    /**
     * Nacos OpenAPI 路径：列出配置项
     */
    private static final String API_LIST_CONFIGS = "/nacos/v1/cs/configs/search";

    /**
     * 默认分组
     */
    private static final String DEFAULT_GROUP = "DEFAULT_GROUP";

    /**
     * Nacos 服务地址（从 bootstrap.yml 读取，形如 127.0.0.1:8848）
     */
    @Value("${spring.cloud.nacos.config.server-addr:${spring.cloud.nacos.discovery.server-addr:127.0.0.1:8848}}")
    private String serverAddr;

    /**
     * RestTemplate（Nacos OpenAPI 调用）
     * <p>
     * 使用 JDK 原生 HttpClient 构建，避免引入额外 Bean。
     */
    private final RestTemplate restTemplate = new RestTemplate();

    /*=============================================    远程调用    =============================================*/

    /**
     * 获取指定配置内容
     * <p>
     * 执行流程：
     * 1. 构建 Nacos OpenAPI URL：http://{server-addr}/nacos/v1/cs/configs
     * 2. 设置请求参数：dataId、group、tenant(namespace)
     * 3. 发送 HTTP GET 请求
     * 4. 解析响应，返回配置内容字符串
     * 5. 若配置不存在，返回 null
     *
     * @param dataId    配置ID
     * @param group     分组
     * @param namespace 命名空间
     * @return 配置内容；配置不存在返回 {@code null}
     */
    @Override
    public String getConfig(String dataId, String group, String namespace) {
        if (!StringUtils.hasText(dataId)) {
            throw new ServiceException("dataId 不能为空", ResultCode.INVALID_PARA.getCode());
        }
        if (!StringUtils.hasText(group)) {
            group = DEFAULT_GROUP;
        }
        // 构建 URL
        UriComponentsBuilder builder = UriComponentsBuilder
                .fromHttpUrl("http://" + serverAddr + API_GET_CONFIG)
                .queryParam("dataId", dataId)
                .queryParam("group", group);
        if (StringUtils.hasText(namespace)) {
            builder.queryParam("tenant", namespace);
        }
        URI uri = builder.build(true).toUri();
        // 发送请求
        try {
            String response = restTemplate.getForObject(uri, String.class);
            log.debug("获取 Nacos 配置成功：dataId = {}, group = {}, namespace = {}", dataId, group, namespace);
            return response;
        } catch (Exception e) {
            log.warn("获取 Nacos 配置失败：dataId = {}, group = {}, namespace = {}, error = {}",
                    dataId, group, namespace, e.getMessage());
            return null;
        }
    }

    /**
     * 列出指定分组下的所有配置项
     * <p>
     * 调用 Nacos OpenAPI 列出指定 group/namespace 下的所有配置项列表。
     *
     * @param group     分组
     * @param namespace 命名空间
     * @return 配置项列表（含 dataId、group、content 等字段）
     */
    @Override
    public List<Map<String, Object>> listConfigs(String group, String namespace) {
        if (!StringUtils.hasText(group)) {
            group = DEFAULT_GROUP;
        }
        // 构建 URL（searchAccurate=false 模糊查询，pageNo=1，pageSize=1000 一次拉全）
        UriComponentsBuilder builder = UriComponentsBuilder
                .fromHttpUrl("http://" + serverAddr + API_LIST_CONFIGS)
                .queryParam("dataId", "")
                .queryParam("group", group)
                .queryParam("search", "accurate")
                .queryParam("pageNo", 1)
                .queryParam("pageSize", 1000);
        if (StringUtils.hasText(namespace)) {
            builder.queryParam("tenant", namespace);
        }
        URI uri = builder.build(true).toUri();
        // 发送请求
        try {
            String response = restTemplate.getForObject(uri, String.class);
            if (!StringUtils.hasText(response)) {
                return new ArrayList<>();
            }
            // 解析响应 JSON（Nacos 返回 {"totalCount":N,"pageNumber":1,"pageSize":1000,"pageItems":[...]}）
            Map<String, Object> respMap = JsonUtil.jsonToClass(response,
                    new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {});
            if (respMap == null || !respMap.containsKey("pageItems")) {
                return new ArrayList<>();
            }
            Object pageItems = respMap.get("pageItems");
            if (!(pageItems instanceof List)) {
                return new ArrayList<>();
            }
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> result = (List<Map<String, Object>>) pageItems;
            return result;
        } catch (Exception e) {
            log.warn("列出 Nacos 配置失败：group = {}, namespace = {}, error = {}",
                    group, namespace, e.getMessage());
            return Collections.emptyList();
        }
    }
}