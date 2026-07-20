package com.zmbdp.chat.api.system.domain.vo;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.util.List;

/**
 * 系统健康状态 VO
 * <p>
 * 用于 B 端管理系统健康检查接口返回，包含 5 个核心组件的检测结果
 * <p>
 * <b>组件列表</b>：MySQL、Redis、Nacos、Milvus、LLM（DashScope API）。
 *
 * @author 稚名不带撇
 */
@Data
public class SystemHealthVO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 整体状态（UP/DOWN/PARTIAL）
     * <p>
     * <ul>
     *     <li>UP：所有组件正常</li>
     *     <li>PARTIAL：部分组件异常</li>
     *     <li>DOWN：全部组件异常</li>
     * </ul>
     */
    private String overallStatus;

    /**
     * 各组件状态列表
     */
    private List<Component> components;

    /**
     * 检测时间（格式：yyyy-MM-dd HH:mm:ss）
     */
    private String checkTime;

    /**
     * 单个组件的健康状态
     */
    @Data
    public static class Component implements Serializable {

        @Serial
        private static final long serialVersionUID = 1L;

        /**
         * 组件名称（MySQL/Redis/Nacos/Milvus/LLM）
         */
        private String name;

        /**
         * 组件状态（UP/DOWN）
         */
        private String status;

        /**
         * 检测延迟（毫秒）
         */
        private Long latency;

        /**
         * 详细信息（异常时含错误原因）
         */
        private String details;

        public Component() {
        }

        public Component(String name, String status, Long latency, String details) {
            this.name = name;
            this.status = status;
            this.latency = latency;
            this.details = details;
        }
    }
}
