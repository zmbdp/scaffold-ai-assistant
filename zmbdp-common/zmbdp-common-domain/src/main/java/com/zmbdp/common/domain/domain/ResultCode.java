package com.zmbdp.common.domain.domain;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 响应码枚举
 *
 * @author 稚名不带撇
 */
@Getter
@AllArgsConstructor
public enum ResultCode {

    //---------------------------2xx

    /**
     * 操作成功
     */
    SUCCESS(200000, "操作成功"),

    //------------------------4xx
    // 400

    /**
     * 无效的参数
     */
    INVALID_PARA(400000, "无效的参数"),

    /**
     * 无效的验证码
     */
    INVALID_CODE(400001, "无效的验证码"),

    /**
     * 错误的验证码
     */
    ERROR_CODE(400002, "错误的验证码"),

    /**
     * 手机号格式错误
     */
    ERROR_PHONE_FORMAT(400003, "手机号格式错误"),

    /**
     * 超过每日发送次数限制
     */
    SEND_MSG_OVERLIMIT(400004, "超过每日发送次数限制"),

    /**
     * 无效的区划
     */
    INVALID_REGION(400005, "无效的区划"),

    /**
     * 参数类型不匹配
     */
    PARA_TYPE_MISMATCH(400006, "参数类型不匹配"),

    /**
     * 请求过于频繁（频控 / 防刷）
     */
    REQUEST_TOO_FREQUENT(400020, "请求过于频繁，请稍后重试"),

    /**
     * 账号已停用，登录失败
     */
    USER_DISABLE(400007, "账号已停用，登录失败"),

    // 401

    /**
     * 令牌不能为空
     */
    TOKEN_EMPTY(401000, "令牌不能为空"),

    /**
     * 令牌已过期或验证不正确！
     */
    TOKEN_INVALID(401001, "令牌已过期或验证不正确！"),

    /**
     * 令牌已过期！
     */
    TOKEN_OVERTIME(401002, "令牌已过期！"),

    /**
     * 登录状态已过期！
     */
    LOGIN_STATUS_OVERTIME(401003, "登录状态已过期！"),

    /**
     * 令牌验证失败！
     */
    TOKEN_CHECK_FAILED(401004, "令牌验证失败！"),

    //404

    /**
     * 服务未找到！
     */
    SERVICE_NOT_FOUND(404000, "服务未找到"),

    /**
     * url 未找到
     */
    URL_NOT_FOUND(404001, "url 未找到"),

    //405

    /**
     * 请求方法不支持！
     */
    REQUEST_METHOD_NOT_SUPPORTED(405000, "请求方法不支持"),

    //---------------------5xx

    /**
     * 服务繁忙请稍后重试！
     */
    ERROR(500000, "服务繁忙请稍后重试"),

    /**
     * 操作失败
     */
    FAILED(500001, "操作失败"),

    /**
     * 短信发送失败
     */
    SEND_MSG_FAILED(500002, "短信发送失败"),

    /**
     * 获取直传地址失败
     */
    PRE_SIGN_URL_FAILED(500003, "获取直传地址失败"),

    /**
     * 上传oss异常，请稍后重试
     */
    OSS_UPLOAD_FAILED(500004, "上传 oss 异常，请稍后重试"),

    /**
     * 获取地图数据失败，请稍后重试
     */
    QQMAP_QUERY_FAILED(500005, "获取地图数据失败，请稍后重试"),

    /**
     * 城市信息获取失败
     */
    QQMAP_CITY_UNKNOW(500006, "城市信息获取失败"),

    /**
     * 根据位置获取城市失败
     */
    QQMAP_LOCATE_FAILED(500007, "根据位置获取城市失败"),

    /**
     * 地图特性未开启
     */
    MAP_NOT_ENABLED(500008, "地图特性未开启,开启方式参考使用手册"),

    /**
     * 地图区划特性未开启
     */
    MAP_REGION_NOT_ENABLED(500009, "地图区划特性未开启,开启方式参考使用手册"),

    /**
     * 邮件发送失败
     */
    EMAIL_SEND_FAILED(500010, "邮件发送失败"),

    /**
     * 邮件配置错误
     */
    EMAIL_CONFIG_ERROR(500011, "邮件配置错误"),

    /**
     * 邮件地址格式错误
     */
    EMAIL_ADDRESS_ERROR(500012, "邮件地址格式错误"),

    /**
     * 邮件附件处理失败
     */
    EMAIL_ATTACHMENT_FAILED(500013, "邮件附件处理失败"),

    /**
     * 邮件模板错误
     */
    EMAIL_TEMPLATE_ERROR(500014, "邮件模板错误"),

    /**
     * 邮件收件人为空
     */
    EMAIL_RECIPIENT_EMPTY(500015, "邮件收件人为空"),

    /**
     * Excel 导出失败
     */
    EXCEL_EXPORT_FAILED(500016, "Excel 导出失败"),

    /**
     * Excel 导入失败
     */
    EXCEL_IMPORT_FAILED(500017, "Excel 导入失败"),

    /**
     * Excel 格式错误
     */
    EXCEL_FORMAT_ERROR(500018, "Excel 格式错误"),

    /**
     * Excel 数据验证失败
     */
    EXCEL_VALIDATE_FAILED(500019, "Excel 数据验证失败"),

    /**
     * Excel 文件读取失败
     */
    EXCEL_READ_FAILED(500020, "Excel 文件读取失败"),

    /**
     * Excel 文件写入失败
     */
    EXCEL_WRITE_FAILED(500021, "Excel 文件写入失败"),

    //---------------------AI 模块业务错误码（从 500022 开始）

    /**
     * AI 配置未设置（API Key 等配置缺失）
     */
    AI_CONFIG_NOT_SET(500022, "AI 配置未设置"),

    /**
     * AI 服务连接失败（无法连接到 AI 模型服务）
     */
    AI_SERVICE_CONNECT_FAILED(500023, "AI 服务连接失败"),

    /**
     * AI 服务响应超时（AI 模型响应超时）
     */
    AI_SERVICE_TIMEOUT(500024, "AI 服务响应超时"),

    /**
     * 工具调用失败（Agent 工具执行出错）
     */
    AI_TOOL_CALL_FAILED(500025, "工具调用失败"),

    /**
     * 路径不在白名单（文件读取路径不在允许范围）
     */
    AI_PATH_NOT_ALLOWED(500026, "路径不在白名单"),

    /**
     * 文件类型不支持（上传文件类型不在允许列表）
     */
    AI_FILE_TYPE_NOT_SUPPORTED(500027, "文件类型不支持"),

    /**
     * 文件超过大小限制（上传文件超过最大限制）
     */
    AI_FILE_TOO_LARGE(500028, "文件超过大小限制"),

    /**
     * 点踩原因不能为空（feedbackType=DISLIKE 时 dislikeReason 必填）
     */
    FEEDBACK_DISLIKE_REASON_REQUIRED(500031, "点踩原因不能为空"),

    /**
     * 会话不属于当前用户（sessionId 归属校验失败，C 端用户尝试访问/操作他人会话）
     */
    SESSION_NOT_BELONG_TO_USER(500032, "会话不属于当前用户"),

    // 403 AI 权限相关

    /**
     * 无权限访问此资源（用户来源与接口不匹配，Gateway 层）
     */
    AI_FORBIDDEN(403000, "无权限访问此资源"),

    /**
     * 无模型调用权限（未配置该模型的调用权限）
     */
    AI_NO_MODEL_PERMISSION(403001, "无模型调用权限"),

    /**
     * 模型调用配额已用尽（权限配额使用完毕）
     */
    AI_QUOTA_EXHAUSTED(403002, "模型调用配额已用尽"),

    /**
     * 权限已过期（权限有效期已过）
     */
    AI_PERMISSION_EXPIRED(403003, "权限已过期"),

    // 404 AI 资源不存在

    /**
     * 知识源不存在（知识源查询失败）
     */
    AI_KNOWLEDGE_SOURCE_NOT_FOUND(404002, "知识源不存在"),

    /**
     * 文档不存在（文档查询失败）
     */
    AI_DOCUMENT_NOT_FOUND(404003, "文档不存在"),

    /**
     * AI 操作日志不存在（操作日志查询失败）
     */
    AI_OPERATION_LOG_NOT_FOUND(404004, "AI 操作日志不存在"),


    //---------------------枚举占位
    /**
     * 占位专用
     */
    RESERVED(99999999, "占位专用");

    /**
     * 响应码
     */
    private int code;

    /**
     * 响应消息
     */
    private String errMsg;
}