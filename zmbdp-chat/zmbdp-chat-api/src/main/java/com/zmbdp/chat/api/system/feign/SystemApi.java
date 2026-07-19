package com.zmbdp.chat.api.system.feign;

import com.zmbdp.chat.api.system.domain.vo.SystemHealthVO;
import com.zmbdp.common.domain.domain.Result;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * 系统管理远程调用 Api
 * <p>
 * 提供系统健康检查能力。
 * <p>
 * chat-service 内部检测 5 个核心组件（MySQL/Redis/Nacos/Milvus/LLM），
 * admin-service 可通过本 Feign 接口转发调用。
 *
 * @author 稚名不带撇
 */
@FeignClient(contextId = "systemApi", name = "zmbdp-chat-service", path = "/system")
public interface SystemApi {

    /**
     * 获取系统健康状态
     * <p>
     * 检测 5 个核心组件的连通性：
     * <ul>
     *     <li>MySQL：执行 {@code SELECT 1} 测试连接</li>
     *     <li>Redis：执行 {@code PING} 命令</li>
     *     <li>Nacos：通过 {@code DiscoveryClient.getServices()} 验证注册中心可达</li>
     *     <li>Milvus：调用 {@code MilvusServiceClient.getCollectionStats()} 验证向量库可达</li>
     *     <li>LLM：调用 ChatClient 发送极简 prompt 验证 DashScope API 可达</li>
     * </ul>
     * <p>
     * <b>降级策略</b>：单个组件检测失败不影响其他组件检测结果，
     * {@code overallStatus} 为 PARTIAL（部分组件异常）。
     *
     * @return 系统健康状态 VO
     */
    @GetMapping("/health")
    Result<SystemHealthVO> health();
}
