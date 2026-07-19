package com.zmbdp.chat.service.service;

import java.util.List;
import java.util.Map;

/**
 * Nacos 配置服务
 * <p>
 * 封装 Nacos 配置中心的 HTTP API 调用，支持按 dataId/group/namespace 查询配置内容。
 * <p>
 * <b>核心依赖</b>：NacosRestTemplate 或 WebClient。
 * <p>
 * <b>调用接口</b>：Nacos OpenAPI（{@code GET /nacos/v1/cs/configs}）。
 *
 * @author 稚名不带撇
 */
public interface INacosConfigService {

    /*=============================================    远程调用    =============================================*/

    /**
     * 获取指定配置内容
     * <p>
     * 调用 Nacos OpenAPI {@code GET /nacos/v1/cs/configs} 获取配置。
     *
     * @param dataId    配置ID
     * @param group     分组
     * @param namespace 命名空间
     * @return 配置内容；配置不存在返回 {@code null}
     */
    String getConfig(String dataId, String group, String namespace);

    /**
     * 列出指定分组下的所有配置项
     * <p>
     * 调用 Nacos OpenAPI 列出指定 group/namespace 下的所有配置项列表。
     *
     * @param group     分组
     * @param namespace 命名空间
     * @return 配置项列表（含 dataId、group、content 等字段）
     */
    List<Map<String, Object>> listConfigs(String group, String namespace);
}
