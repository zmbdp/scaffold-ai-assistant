package com.zmbdp.chat.api.chat.domain.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * RAG 检索请求 DTO
 * <p>
 * 用于 portal-service 或 admin-service（召回测试）调用 chat-service 的 RAG 检索能力。
 * 输入用户问题，返回相关文档分块（不调用 LLM，仅做向量检索 + 重排序）。
 *
 * @author 稚名不带撇
 */
@Data
public class RetrieveReqDTO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 用户问题（必填，用于生成 Embedding 向量）
     */
    @NotBlank(message = "问题不能为空")
    private String question;

    /**
     * RAG 检索数量（1-20，默认 5）
     */
    @Min(value = 1, message = "topK最小为1")
    @Max(value = 20, message = "topK最大为20")
    private Integer topK;

    /**
     * 文档类型过滤（doc/javadoc/config/code，可选）
     */
    private String sourceType;

    /**
     * 模块过滤（可选，如 common-cache）
     */
    private String module;
}