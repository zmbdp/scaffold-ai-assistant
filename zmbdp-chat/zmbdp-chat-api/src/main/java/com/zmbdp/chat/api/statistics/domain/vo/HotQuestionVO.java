package com.zmbdp.chat.api.statistics.domain.vo;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * 热门问题 VO
 * <p>
 * 从 sys_ai_conversation 表按 question 字段分组统计（过滤 is_deleted=0），按被问次数降序排序。
 *
 * @author 稚名不带撇
 */
@Data
public class HotQuestionVO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 问题内容
     */
    private String question;

    /**
     * 被问次数
     */
    private Integer count;

    /**
     * 最后一次提问时间（时间戳，毫秒）
     */
    private Long lastAskedTime;
}
