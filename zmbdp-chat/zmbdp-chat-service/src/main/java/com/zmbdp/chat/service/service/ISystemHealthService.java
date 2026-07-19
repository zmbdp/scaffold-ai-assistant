package com.zmbdp.chat.service.service;

import com.zmbdp.chat.api.system.domain.vo.SystemHealthVO;

/**
 * 系统健康检查服务
 * <p>
 * 检测 5 个核心组件（MySQL/Redis/Nacos/Milvus/LLM）的连通性，
 * 供 B 端管理系统健康检查接口调用。
 *
 * @author 稚名不带撇
 */
public interface ISystemHealthService {

    /**
     * 执行系统健康检查
     * <p>
     * 依次检测 5 个核心组件，单个组件检测失败不影响其他组件。
     *
     * @return 系统健康状态 VO
     */
    SystemHealthVO checkHealth();
}
