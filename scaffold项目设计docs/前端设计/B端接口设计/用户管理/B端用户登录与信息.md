# B 端用户登录与信息

> 模块：用户管理 / B 端登录态
>
> 适用对象：B 端管理系统（`userFrom=sys` 的系统用户）
>
> 公共约定（网关地址、JWT 认证、统一响应结构 `Result<T>`、错误码、枚举值等）见 `总说明.md`。

---

## 功能介绍

本模块提供 B 端用户的登录、登录信息查询、退出登录三个接口，用于建立与销毁 B 端管理端的登录态。

- **登录**：账号密码登录，校验通过后签发 JWT（`userFrom=sys`），返回 `accessToken` 与过期时间。该接口在网关白名单内，**不需要 token**。
- **登录信息查询**：根据当前请求头中的 token，回查数据库返回当前登录 B 端用户的昵称、身份、状态等信息。前端常用于刷新页面后恢复用户上下文、控制 UI 权限。
- **退出登录**：解析当前 token，删除服务端 Redis 登录态缓存，使该 token 立即失效。

> 本模块接口均不涉及操作审计（源码未标注 `@LogAction`）。

---

## 一、B 端用户登录

### 1.1 接口信息

| 项目 | 内容 |
| --- | --- |
| 路径 | `POST /admin/sys_user/login/password` |
| 认证 | **不需要 token**（网关白名单 `/**/login/**` 命中） |
| 权限 | 无（任何人都可调用，校验账号密码） |
| Content-Type | `application/json` |
| 操作审计 | 无 |

> 网关 `StripPrefix=1`，前端请求 `/admin/sys_user/login/password`，网关剥离 `/admin` 后转发到 admin-service 的 `/sys_user/login/password`。

### 1.2 请求参数

请求体为 `PasswordLoginDTO`：

| 字段 | 类型 | 必传 | 校验规则 | 默认值 | 含义 |
| --- | --- | --- | --- | --- | --- |
| `phone` | String | 是 | `@NotBlank(message = "手机号不能为空")`；服务端再用 `VerifyUtil.checkPhone` 校验，正则 `^1[3-9]\d{9}$`（11 位，1 开头，第二位 3-9） | — | 手机号（**明文传输**） |
| `password` | String | 是 | `@NotBlank(message = "密码不能为空")` | — | 密码（**前端需先 AES 加密为 Hex 字符串再传输**，服务端会 `AESUtil.decryptHex` 解密后再做 SHA256 比对） |

> **重要（前端必读）**：
> - `phone` 为明文手机号，服务端会自行加密后与库中密文比对。
> - `password` **不是明文**，前端在发送前需使用与后端约定的 AES 密钥将明文密码加密为 Hex 字符串。服务端流程：`AESUtil.decryptHex(password)` → `DigestUtil.sha256Hex(...)` → 与数据库存储的 SHA256 密文比对。

### 1.3 请求示例

```http
POST /admin/sys_user/login/password
Content-Type: application/json

{
  "phone": "13800138000",
  "password": "a3f5e2b9c1d4...（前端 AES 加密后的 Hex 字符串）"
}
```

### 1.4 响应结构

`Result<TokenVO>`，其中 `TokenVO` 字段：

| 字段 | 类型 | 含义 |
| --- | --- | --- |
| `accessToken` | String | 访问令牌，后续所有需认证接口需在请求头 `Authorization: Bearer {accessToken}` 携带 |
| `expires` | Long | 过期时间（毫秒级时间戳） |

### 1.5 响应示例

```json
{
  "code": 200000,
  "errMsg": "操作成功",
  "data": {
    "accessToken": "eyJhbGciOiJIUzI1NiJ9.....",
    "expires": 1784900000000
  }
}
```

### 1.6 错误码说明

| code | errMsg | 触发场景 |
| --- | --- | --- |
| `400000` | 无效的参数 | 手机号格式不合理（不匹配 `^1[3-9]\d{9}$`）；手机号或密码错误（用户不存在或密码不匹配）；密码解析为空（AES 解密失败返回空） |
| `400007` | 账号已停用，登录失败 | 用户 `status=disable`（HTTP 200 + 业务 code） |
| `400020` | 请求过于频繁，请稍后重试 | 触发网关/接口限流 |

> 注：登录失败的参数错误统一以 `400000` 返回，消息文本区分「手机号不合理」「手机号或密码错误」「密码解析为空」。账号停用返回 `400007`。

---

## 二、获取 B 端登录用户信息

### 2.1 接口信息

| 项目 | 内容 |
| --- | --- |
| 路径 | `GET /admin/sys_user/login_info/get` |
| 认证 | 需要 token（`Authorization: Bearer {accessToken}`），且 `userFrom=sys` |
| 权限 | 所有已登录 B 端用户（`super_admin` / `platform_admin` 均可） |
| Content-Type | 无请求体 |
| 操作审计 | 无 |

### 2.2 请求参数

无请求体、无 query 参数。服务端从请求头 token 解析当前登录用户 ID，再查库。

### 2.3 请求示例

```http
GET /admin/sys_user/login_info/get
Authorization: Bearer eyJhbGciOiJIUzI1NiJ9.....
```

### 2.4 响应结构

`Result<SysUserLoginVO>`，`SysUserLoginVO` 继承自 `LoginUserVO`，字段如下：

| 字段 | 类型 | 来源 | 含义 |
| --- | --- | --- | --- |
| `token` | String | Redis 登录态 | 用户标识（当前 token） |
| `userId` | Long | Redis 登录态 | 用户 ID |
| `userName` | String | Redis 登录态 | 用户名（即昵称） |
| `loginTime` | Long | Redis 登录态 | 登录时间（毫秒级时间戳） |
| `expireTime` | Long | Redis 登录态 | 过期时间（毫秒级时间戳） |
| `nickName` | String | 数据库 `sys_user` | 昵称 |
| `identity` | String | 数据库 `sys_user` | 身份（`super_admin` / `platform_admin`） |
| `status` | String | 数据库 `sys_user` | 状态（`enable` / `disable`） |

### 2.5 响应示例

```json
{
  "code": 200000,
  "errMsg": "操作成功",
  "data": {
    "token": "eyJhbGciOiJIUzI1NiJ9.....",
    "userId": 1,
    "userName": "超级管理员",
    "loginTime": 1784800000000,
    "expireTime": 1784900000000,
    "nickName": "超级管理员",
    "identity": "super_admin",
    "status": "enable"
  }
}
```

### 2.6 错误码说明

| code | errMsg | 触发场景 |
| --- | --- | --- |
| `400000` | 无效的参数 | 用户令牌有误（token 解析出的用户为空）；用户不存在（数据库查不到） |
| `401000` | 令牌不能为空 | 缺失 token（HTTP 401） |
| `401001` | 令牌已过期或验证不正确！ | token 无效（HTTP 401） |
| `401003` | 登录状态已过期！ | Redis 登录态已过期（HTTP 401） |
| `401004` | 令牌验证失败！ | `userFrom` 不为 `sys`（C 端 token 越权访问）（HTTP 401） |

---

## 三、退出登录

### 3.1 接口信息

| 项目 | 内容 |
| --- | --- |
| 路径 | `DELETE /admin/sys_user/logout` |
| 认证 | 需要 token（`Authorization: Bearer {accessToken}`），且 `userFrom=sys` |
| 权限 | 所有已登录 B 端用户 |
| Content-Type | 无请求体 |
| 操作审计 | 无（仅服务端 `log.info` 记录退出日志，不写审计表） |

### 3.2 请求参数

无请求体、无 query 参数。服务端从请求头解析 token，删除对应 Redis 登录态。

### 3.3 请求示例

```http
DELETE /admin/sys_user/logout
Authorization: Bearer eyJhbGciOiJIUzI1NiJ9.....
```

### 3.4 响应结构

`Result<Void>`，`data` 为 `null`。

### 3.5 响应示例

```json
{
  "code": 200000,
  "errMsg": "操作成功",
  "data": null
}
```

### 3.6 错误码说明

| code | errMsg | 触发场景 |
| --- | --- | --- |
| `401000` | 令牌不能为空 | 缺失 token（HTTP 401） |
| `401001` | 令牌已过期或验证不正确！ | token 无效（HTTP 401） |
| `401003` | 登录状态已过期！ | Redis 登录态已过期（HTTP 401） |
| `401004` | 令牌验证失败！ | `userFrom` 不为 `sys`（HTTP 401） |

> 说明：若请求头中无 token，服务端 `logout` 直接返回成功（不做任何操作）；若有 token，则解析用户名/用户 ID 写日志后删除 Redis 缓存。该接口本身不主动抛业务错误，但若 token 失效会被网关在到达前拦截并返回上述 `401xxx`。

---

## 四、枚举值

### 4.1 B 端用户身份（identity）

| 值 | 含义 | 权限差异 |
| --- | --- | --- |
| `super_admin` | 超级管理员 | 可新增/编辑用户账号 |
| `platform_admin` | 平台管理员 | 不可新增/编辑用户账号 |

### 4.2 B 端用户状态（status）

| 值 | 含义 |
| --- | --- |
| `enable` | 启用 |
| `disable` | 停用（无法登录；被改为停用时会强制下线该用户的登录态） |

> 注意：B 端用户 `status` 为 **String 类型**（`enable`/`disable`），与字典状态（Integer `1`/`0`）不同，请勿混淆。

---

## 五、前端注意事项

1. **Token 存储**：登录成功后，将 `data.accessToken` 持久化（如 localStorage），并在后续所有需认证接口的请求头统一注入 `Authorization: Bearer {accessToken}`（`Bearer` 后必须有一个空格）。
2. **登录密码需 AES 加密**：登录接口的 `password` 字段不是明文，前端需先 AES 加密为 Hex 字符串再提交；手机号 `phone` 为明文。
3. **identity 控制 UI 权限**：通过 `GET /admin/sys_user/login_info/get` 获取 `identity` 字段，据此控制 UI 元素（如「新增用户」「编辑用户」按钮仅 `super_admin` 可见，`platform_admin` 隐藏）。
4. **停用强制下线**：当超级管理员将某用户状态改为 `disable` 时，服务端会主动删除该用户的 Redis 登录态，使其 token 立即失效。前端在被停用用户下次请求收到 `401003` 时应跳转登录页并提示账号已停用。
5. **token 失效处理**：收到 `401000`/`401001`/`401003`/`401004` 任意错误码时，清除本地 token 并跳转登录页。
6. **退出登录**：调用 `DELETE /admin/sys_user/logout` 后，无论服务端返回如何，前端都应清除本地 token 并跳转登录页。
7. **切勿复用 C 端 token**：B 端接口仅允许 `userFrom=sys` 的 token 访问，C 端 `/portal/login/**` 产出的 token（`userFrom=app`）访问 `/admin/**` 会被网关以 `401004` 拒绝。
