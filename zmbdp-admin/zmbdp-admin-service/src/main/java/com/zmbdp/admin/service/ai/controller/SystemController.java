package com.zmbdp.admin.service.ai.controller;

import com.zmbdp.chat.api.system.domain.vo.SystemHealthVO;
import com.zmbdp.common.domain.domain.Result;
import com.zmbdp.admin.service.ai.service.IAiAdminService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * B端系统管理 Controller
 * <p>
 * 作为B端系统健康检查的统一入口，通过 Feign 调用 chat-service 的 {@code SystemApi}。
 * <p>
 * <b>接口路径</b>：
 * <ul>
 *     <li>{@code GET /system/health}：获取系统健康状态（含 5 个组件检测）</li>
 * </ul>
 * <p>
 * <b>网关路径映射</b>：前端请求 {@code /admin/system/health} → gateway StripPrefix=1 → 本 Controller {@code /system/health}
 * <p>
 * <b>检测组件</b>：
 * <ul>
 *     <li>MySQL：执行 {@code SELECT 1} 测试连接</li>
 *     <li>Redis：执行 {@code PING} 命令</li>
 *     <li>Nacos：通过 {@code DiscoveryClient.getServices()} 验证注册中心可达</li>
 *     <li>Milvus：调用 {@code MilvusServiceClient.hasCollection()} 验证向量库可达</li>
 *     <li>LLM：调用 ChatClient 发送极简 prompt 验证 DashScope API 可达</li>
 * </ul>
 * <p>
 * <b>降级策略</b>：单个组件检测失败不影响其他组件，{@code overallStatus} 为 UP/PARTIAL/DOWN。
 *
 * @author 稚名不带撇
 */
@Slf4j
@RestController
@RequestMapping("/system")
public class SystemController {

    /**
     * B端 AI 管理业务编排服务
     */
    @Autowired
    private IAiAdminService aiAdminService;

    /**
     * 获取系统健康状态
     * <p>
     * 检测 5 个核心组件（MySQL/Redis/Nacos/Milvus/LLM）的连通性，
     * 单个组件检测失败不影响其他组件。
     *
     * @return 系统健康状态 VO
     */
    @GetMapping("/health")
    public Result<SystemHealthVO> health() {
        log.info("获取系统健康状态");
        return Result.success(aiAdminService.getSystemHealth());
    }
}
