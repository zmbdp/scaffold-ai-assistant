package com.zmbdp.chat.service.service;

import java.util.List;
import java.util.Map;

/**
 * Nacos 配置服务
 * <p>
 * <b>当前实现</b>：直接读取脚手架本地 yaml 配置文件（位于
 * {@code deploy/{env}/res/sql/DEFAULT_GROUP/} 目录下），不再通过 Nacos OpenAPI 远程查询。
 * 原因：脚手架所有 Nacos 配置均以 yaml 文件形式存在于 frameworkjava 仓库中，
 * 运行时再走 OpenAPI 回头查 Nacos 多此一举。详见实现类 {@code NacosConfigServiceImpl}。
 * <p>
 * <b>接口兼容</b>：方法签名保持 {@code (dataId, group, namespace)} 三参数形式，
 * namespace 仍按 {@code scaffold-ai-assistant-{env}} 约定传入，实现层从中解析 env 后拼接本地路径。
 * 三个 Agent 工具（NacosConfigTool/CompareConfigTool/PreDeployCheckTool）零改动可用。
 *
 * @author 稚名不带撇
 */
public interface INacosConfigService {

    /*=============================================    配置查询    =============================================*/

    /**
     * 获取指定配置内容
     * <p>
     * 实现层从 namespace 解析 env，拼接本地文件路径 {@code {base-path}/deploy/{env}/.../DEFAULT_GROUP/{dataId}} 读取。
     *
     * @param dataId    配置ID（完整文件名，如 share-redis-dev.yaml，由工具层拼接环境后缀）
     * @param group     分组（仅作记录，脚手架所有配置均在 DEFAULT_GROUP 下）
     * @param namespace 命名空间（scaffold-ai-assistant-{env}，从中解析 env）
     * @return 配置内容；配置不存在返回 {@code null}
     */
    String getConfig(String dataId, String group, String namespace);

    /**
     * 列出指定分组下的所有配置项
     * <p>
     * 实现层列出本地 DEFAULT_GROUP 目录下所有 yaml 文件，返回配置项列表（含 dataId、group、content）。
     *
     * @param group     分组（仅 DEFAULT_GROUP 有效）
     * @param namespace 命名空间（scaffold-ai-assistant-{env}）
     * @return 配置项列表（含 dataId、group、content 等字段）；目录不存在返回空列表
     */
    List<Map<String, Object>> listConfigs(String group, String namespace);
}
