package com.zmbdp.chat.api.knowledge.domain.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * 知识源请求 DTO
 * <p>
 * 用于新增 / 更新知识源（文档目录、JavaDoc、配置文件、Java 源码）。
 * 类型字段对应 {@link com.zmbdp.chat.api.common.enums.DocumentType} 枚举。
 *
 * @author 稚名不带撇
 */
@Data
public class KnowledgeSourceReqDTO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 知识源名称（必填）
     */
    @NotBlank(message = "知识源名称不能为空")
    private String name;

    /**
     * 知识源路径（必填，文件系统绝对路径或项目相对路径）
     */
    @NotBlank(message = "知识源路径不能为空")
    private String path;

    /**
     * 类型（必填，doc/javadoc/config/code，对应 DocumentType 枚举）
     */
    @NotBlank(message = "类型不能为空")
    private String type;

    /**
     * 是否启用（可选，默认 true）
     */
    private Boolean enabled;

    /**
     * 分块大小（可选，默认 500，范围 100-2000）
     */
    @Min(value = 100, message = "分块大小最小为100")
    @Max(value = 2000, message = "分块大小最大为2000")
    private Integer chunkSize;

    /**
     * 分块重叠大小（可选，默认 50，范围 0-200，需小于 chunkSize）
     */
    @Min(value = 0, message = "分块重叠大小最小为0")
    @Max(value = 200, message = "分块重叠大小最大为200")
    private Integer chunkOverlap;
}
