package com.zmbdp.chat.service.controller;

import com.zmbdp.chat.api.system.domain.vo.SystemHealthVO;
import com.zmbdp.chat.api.system.feign.SystemApi;
import com.zmbdp.chat.service.service.ISystemHealthService;
import com.zmbdp.common.domain.domain.Result;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 系统管理 Controller（chat-service 端）
 * <p>
 * 实现 {@link SystemApi} Feign 接口，提供系统健康检查能力
 *
 * @author 稚名不带撇
 */
@Slf4j
@RestController
@RequestMapping("/system")
public class SystemController implements SystemApi {

    /**
     * 系统健康检查服务
     */
    @Autowired
    private ISystemHealthService systemHealthService;

    /**
     * 获取系统健康状态
     * <p>
     * 检测 5 个核心组件（MySQL/Redis/Nacos/Milvus/LLM）的连通性，
     * 单个组件检测失败不影响其他组件。
     *
     * @return 系统健康状态 VO
     */
    @Override
    public Result<SystemHealthVO> health() {
        log.info("获取系统健康状态");
        SystemHealthVO vo = systemHealthService.checkHealth();
        return Result.success(vo);
    }
}
