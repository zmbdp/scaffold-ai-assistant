package com.zmbdp.chat.api.common.enums;

import lombok.Getter;

/**
 * 文档类型枚举
 * <p>
 * 定义知识源和文档的类型，用于 RAG 分块策略选择。
 *
 * @author 稚名不带撇
 */
@Getter
public enum DocumentType {

    /**
     * Markdown 文档
     */
    DOC("doc", "Markdown文档"),

    /**
     * JavaDoc 文档
     */
    JAVADOC("javadoc", "JavaDoc文档"),

    /**
     * 配置文件
     */
    CONFIG("config", "配置文件"),

    /**
     * 源代码文件（Java 源码，使用 JavaParser AST 解析分块）
     */
    CODE("code", "源代码文件");

    /**
     * 类型编码（存入数据库 sys_ai_knowledge_source.type / sys_ai_document.type 字段）
     */
    private final String code;

    /**
     * 类型描述
     */
    private final String description;

    /**
     * 构造函数
     *
     * @param code        类型编码
     * @param description 类型描述
     */
    DocumentType(String code, String description) {
        this.code = code;
        this.description = description;
    }

    /**
     * 根据编码获取枚举
     *
     * @param code 类型编码
     * @return 文档类型枚举，未匹配返回 null
     */
    public static DocumentType fromCode(String code) {
        if (code == null) {
            return null;
        }
        for (DocumentType type : values()) {
            if (type.getCode().equals(code)) {
                return type;
            }
        }
        return null;
    }
}