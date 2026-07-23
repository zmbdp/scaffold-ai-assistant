# C 端用户查询

> 模块：用户管理 / B 端管理 C 端用户
>
> 适用对象：B 端管理系统（`userFrom=sys` 的系统用户）
>
> 公共约定（网关地址、JWT 认证、统一响应结构 `Result<T>`、分页结构 `BasePageVO<T>`、错误码、枚举值等）见 `总说明.md`。

---

## 功能介绍

本模块提供 B 端对 C 端用户（`app_user` 表）的**只读分页查询**能力。

- **查询 C 端用户列表**：B 端管理后台分页查询 C 端用户，支持按用户 ID、手机号、昵称、微信 openId 等条件过滤。**B 端只能查询，不能新增/编辑/删除 C 端用户**（C 端用户新增由 C 端注册流程通过内部 Feign 调用完成，编辑由 C 端自身接口完成，均不在 B 端前端调用范围内）。

> C 端用户注册方式有三种：微信 openId 注册、手机号注册、邮箱注册，均由 C 端注册流程内部调用，B 端不涉及。
>
> 本接口不涉及操作审计（源码未标注 `@LogAction`）。

---

## 一、查询 C 端用户列表

### 1.1 接口信息

| 项目 | 内容 |
| --- | --- |
| 路径 | `POST /admin/app_user/list/search` |
| 认证 | 需要 token（`Authorization: Bearer {accessToken}`），且 `userFrom=sys` |
| 权限 | 所有已登录 B 端用户（`super_admin` / `platform_admin` 均可） |
| Content-Type | `application/json` |
| 操作审计 | 无 |

> 网关 `StripPrefix=1`，前端请求 `/admin/app_user/list/search`，转发到 admin-service 的 `/app_user/list/search`。

### 1.2 请求参数

请求体为 `AppUserListReqDTO`（继承 `BasePageReqDTO`，含分页字段）：

| 字段 | 类型 | 必传 | 校验规则 | 默认值 | 含义 | 查询匹配方式 |
| --- | --- | --- | --- | --- | --- | --- |
| `pageNo` | Integer | 否 | — | `1` | 页码，从 1 开始 | — |
| `pageSize` | Integer | 否 | — | `10` | 每页大小 | — |
| `userId` | Long | 否 | — | `null` | C 端用户 ID | 精确匹配（`id =`） |
| `phoneNumber` | String | 否 | — | `null` | 手机号（**明文**，服务端加密后比对） | 精确匹配（`phone_number =` 加密值） |
| `nickName` | String | 否 | — | `null` | 昵称 | 模糊匹配（`nick_name like concat('%', ?, '%')`） |
| `openId` | String | 否 | — | `null` | 微信 openId | 精确匹配（`open_id =`） |
| `email` | String | 否 | — | `null` | 邮箱（**明文**，服务端会加密） | **当前版本 SQL 未使用该字段作为过滤条件，传值不生效** |

> **重要（前端必读）**：
> - 该接口为**分页查询**，使用 `pageNo`/`pageSize`，响应为 `BasePageVO<AppUserVO>`。
> - `phoneNumber` 为明文，服务端 `AESUtil.encryptHex` 加密后与库中密文精确比对；若查不到可能是手机号未注册或格式不符。
> - `nickName` 为模糊匹配，`userId`/`phoneNumber`/`openId` 为精确匹配。
> - **`email` 字段虽存在于 DTO 且服务端会对其加密，但实际 SQL（`selectCount`/`selectPage`）未引用 `email` 条件，因此按邮箱过滤当前不生效**。如需按邮箱查询，需后端补充 SQL 条件。
> - 所有过滤字段均为可选，全部不传则分页返回全部 C 端用户（按 `id desc` 排序）。
> - 请求体 DTO 无 `@Validated` 校验注解，字段全部可空。

### 1.3 请求示例

分页查询全部 C 端用户（第 1 页，每页 10 条）：

```http
POST /admin/app_user/list/search
Authorization: Bearer eyJhbGciOiJIUzI1NiJ9.....
Content-Type: application/json

{
  "pageNo": 1,
  "pageSize": 10
}
```

按昵称模糊查询：

```http
POST /admin/app_user/list/search
Authorization: Bearer eyJhbGciOiJIUzI1NiJ9.....
Content-Type: application/json

{
  "pageNo": 1,
  "pageSize": 20,
  "nickName": "脚手架"
}
```

按手机号精确查询：

```http
POST /admin/app_user/list/search
Authorization: Bearer eyJhbGciOiJIUzI1NiJ9.....
Content-Type: application/json

{
  "pageNo": 1,
  "pageSize": 10,
  "phoneNumber": "13800138002"
}
```

### 1.4 响应结构

`Result<BasePageVO<AppUserVO>>`，外层 `BasePageVO` 字段：

| 字段 | 类型 | 含义 |
| --- | --- | --- |
| `totals` | Integer | 总记录数 |
| `totalPages` | Integer | 总页数 |
| `list` | List&lt;AppUserVO&gt; | 数据列表 |

列表元素 `AppUserVO` 字段：

| 字段 | 类型 | 含义 |
| --- | --- | --- |
| `userId` | Long | C 端用户 ID |
| `nickName` | String | 昵称 |
| `phoneNumber` | String | 手机号（**服务端解密后的明文**，未绑定时为 `null`） |
| `openId` | String | 微信 openId（未绑定时为 `null`） |
| `email` | String | 邮箱（**服务端解密后的明文**，未绑定时为 `null`） |
| `avatar` | String | 用户头像 URL |

> C 端用户可能只绑定了 openId、手机号、邮箱中的一种或多种（取决于注册方式），未绑定的字段为 `null`。

### 1.5 响应示例

```json
{
  "code": 200000,
  "errMsg": "操作成功",
  "data": {
    "totals": 25,
    "totalPages": 3,
    "list": [
      {
        "userId": 30,
        "nickName": "Java脚手架用户5234",
        "phoneNumber": "13800138002",
        "openId": null,
        "email": null,
        "avatar": "https://example.com/default-avatar.png"
      },
      {
        "userId": 29,
        "nickName": "微信用户_ab12",
        "phoneNumber": null,
        "openId": "oX1234567890abcdef",
        "email": null,
        "avatar": "https://example.com/default-avatar.png"
      }
    ]
  }
}
```

无匹配数据时：

```json
{
  "code": 200000,
  "errMsg": "操作成功",
  "data": {
    "totals": 0,
    "totalPages": 0,
    "list": []
  }
}
```

### 1.6 错误码说明

| code | errMsg | 触发场景 |
| --- | --- | --- |
| `400020` | 请求过于频繁，请稍后重试 | 触发限流 |
| `401000`/`401001`/`401003`/`401004` | token 相关 | 未携带/无效/过期/越权 token（HTTP 401） |

> 该接口为纯查询，不主动抛业务错误。查询结果为空时 `totals=0`、`list=[]`，`code` 仍为 `200000`。

---

## 二、枚举值

### 2.1 C 端用户注册方式（推断）

C 端用户通过以下三种方式之一注册（由 C 端注册流程内部调用，B 端不直接调用）：

| 注册方式 | 标识字段 | 说明 |
| --- | --- | --- |
| 微信 openId 注册 | `openId` 非空 | 微信小程序/公众号登录后注册 |
| 手机号注册 | `phoneNumber` 非空 | 手机号验证码登录后注册 |
| 邮箱注册 | `email` 非空 | 邮箱登录后注册 |

> C 端用户注册时昵称自动生成（格式如「Java脚手架用户XXXX」），头像使用 Nacos 配置的默认头像。注册后用户可通过 C 端接口编辑昵称/头像等信息。

### 2.2 C 端用户状态

C 端用户表 `app_user` **无状态字段**（无启用/停用概念），与 B 端用户（`enable`/`disable`）不同。B 端无法停用 C 端用户。

---

## 三、前端注意事项

1. **只读查询**：B 端对 C 端用户仅提供分页查询，无新增/编辑/删除/停用能力。如需编辑 C 端用户信息，由 C 端用户自身通过 C 端接口完成。
2. **分页参数**：`pageNo` 默认 1，`pageSize` 默认 10。响应中 `totals` 为总记录数，`totalPages` 为总页数。当查询页码超过总页数时，`list` 为空数组但 `totals` 仍有值。
3. **手机号/邮箱加解密**：
   - 入参 `phoneNumber`、`email` 传明文，服务端加密后比对。
   - 响应中 `phoneNumber`、`email` 为服务端解密后的明文，前端可直接展示。
   - 未绑定的字段为 `null`。
4. **邮箱过滤不生效**：`email` 字段虽在 DTO 中存在，但当前 SQL 未将其作为过滤条件，按邮箱查询不会过滤结果。如需该能力需后端补充。
5. **昵称模糊查询**：`nickName` 为模糊匹配（`like %nickName%`），适合搜索框使用。
6. **排序**：结果按 `id desc`（用户 ID 倒序）排列，即最新注册的用户在前。
7. **切勿复用 C 端 token**：本接口为 B 端接口（`/admin/**`），仅允许 `userFrom=sys` 的 token 访问。
8. **数据脱敏展示**：C 端用户手机号/邮箱为明文返回，前端展示时建议根据业务需要做脱敏处理（如手机号中间四位用 `*` 替换）。
