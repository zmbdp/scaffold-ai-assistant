# B 端用户管理

> 模块：用户管理 / B 端用户账号维护
>
> 适用对象：B 端管理系统（`userFrom=sys` 的系统用户）
>
> 公共约定（网关地址、JWT 认证、统一响应结构 `Result<T>`、错误码、枚举值等）见 `总说明.md`。

---

## 功能介绍

本模块提供 B 端用户账号的维护能力，包含两个接口：

- **新增或编辑用户**：合并接口，通过 `userId` 是否为空区分新增/编辑。仅 **超级管理员（`super_admin`）** 可调用，平台管理员（`platform_admin`）调用会被拒绝。可用于新增账号、编辑账号信息、修改密码、切换身份、启用/停用账号。停用账号时服务端会强制下线对应用户。
- **查询 B 端用户列表**：根据条件查询 B 端用户列表，所有已登录 B 端用户均可调用。**该接口不分页**，返回完整匹配列表。

> 本模块接口均不涉及操作审计（源码未标注 `@LogAction`）。

---

## 一、新增或编辑用户

### 1.1 接口信息

| 项目 | 内容 |
| --- | --- |
| 路径 | `POST /admin/sys_user/add_edit` |
| 认证 | 需要 token（`Authorization: Bearer {accessToken}`），且 `userFrom=sys` |
| 权限 | **仅 `super_admin` 可调用**；`platform_admin` 调用返回 `500001 平台管理员不能新增或修改账号信息` |
| Content-Type | `application/json` |
| 操作审计 | 无 |

### 1.2 请求参数

请求体为 `SysUserDTO`：

| 字段 | 类型 | 必传 | 校验规则 | 默认值 | 含义 |
| --- | --- | --- | --- | --- | --- |
| `userId` | Long | 否 | — | `null` | 用户 ID。**为空表示新增**，非空表示编辑该 ID 对应用户 |
| `phoneNumber` | String | 是 | `@NotBlank(message = "手机号不能为空")`；新增时再校验 `VerifyUtil.checkPhone`，正则 `^1[3-9]\d{9}$` | — | 手机号（**明文传输**，服务端加密后入库） |
| `nickName` | String | 是 | `@NotBlank(message = "昵称不能为空")` | — | 昵称 |
| `status` | String | 是 | `@NotBlank(message = "状态不能为空")`；服务端再校验该值在字典中存在（`getDicDataByKey`） | — | 状态：`enable` / `disable` |
| `identity` | String | 否 | 新增时必传且服务端校验在字典中存在；编辑时可不传（保留原值） | — | 身份：`super_admin` / `platform_admin` |
| `password` | String | 条件必传 | 新增必传；编辑时不传则不改密码，传则校验 `checkPassword`（见下） | — | 密码（**明文传输**，服务端 SHA256 加密后入库） |
| `remark` | String | 否 | — | — | 备注 |

**`checkPassword` 校验规则（服务端 `SysUserDTO#checkPassword`）**：

- 密码不能为空；
- 密码长度 `<= 20`；
- 正则 `^[a-zA-Z0-9!@#$%^&*]+$`（仅允许字母、数字及 `!@#$%^&*` 特殊字符）。

> **重要（前端必读）**：
> - 该接口的 `password` 为**明文**（与登录接口的 AES 加密传输不同），服务端直接 `DigestUtil.sha256Hex` 后入库。
> - `phoneNumber` 为明文，服务端 `AESUtil.encryptHex` 加密后入库/比对。
> - 新增时手机号必须唯一（与库中已有手机号比对加密值）；编辑时不修改手机号（手机号仅用于新增场景校验，编辑流程不会回写手机号）。

### 1.3 服务端处理逻辑

| 场景 | 处理 |
| --- | --- |
| 通用前置 | 取当前登录用户 ID，查库判断其 `identity`；非 `super_admin` 直接抛 `500001` |
| 新增（`userId=null`） | 校验手机号格式 → 校验密码（非空 + `checkPassword`）→ 校验手机号唯一 → 校验 `identity` 在字典存在 → 加密手机号、SHA256 加密密码 → 入库 |
| 编辑（`userId!=null`） | 若 `password` 非空：校验 `checkPassword` 通过后 SHA256 加密回写；若 `password` 为空：不修改密码 → 校验 `status` 在字典存在 → 回写 `nickName`/`identity`/`status`/`remark` → 入库 |
| 停用踢人 | 编辑场景下，若 `status=disable`，服务端调用 `tokenService.delLoginUser(userId, "sys")` **强制下线该用户**，使其 token 立即失效 |

> 注意：编辑场景不会修改手机号（即便传入 `phoneNumber`，编辑分支不会回写 `phoneNumber` 字段到实体）。手机号仅作为必传字段做 `@NotBlank` 校验，实际仅在新增分支使用。

### 1.4 请求示例

新增用户：

```http
POST /admin/sys_user/add_edit
Authorization: Bearer eyJhbGciOiJIUzI1NiJ9.....
Content-Type: application/json

{
  "phoneNumber": "13800138001",
  "nickName": "张三",
  "identity": "platform_admin",
  "status": "enable",
  "password": "Abc@1234",
  "remark": "运营平台管理员"
}
```

编辑用户（修改密码并停用）：

```http
POST /admin/sys_user/add_edit
Authorization: Bearer eyJhbGciOiJIUzI1NiJ9.....
Content-Type: application/json

{
  "userId": 2,
  "phoneNumber": "13800138001",
  "nickName": "张三",
  "identity": "platform_admin",
  "status": "disable",
  "password": "NewPass@2026",
  "remark": "已停用"
}
```

编辑用户（不改密码，仅改备注）：

```http
POST /admin/sys_user/add_edit
Authorization: Bearer eyJhbGciOiJIUzI1NiJ9.....
Content-Type: application/json

{
  "userId": 2,
  "phoneNumber": "13800138001",
  "nickName": "张三",
  "status": "enable",
  "remark": "备注更新"
}
```

### 1.5 响应结构

`Result<Long>`，`data` 为新增/更新后的用户 ID。

### 1.6 响应示例

```json
{
  "code": 200000,
  "errMsg": "操作成功",
  "data": 2
}
```

### 1.7 错误码说明

| code | errMsg | 触发场景 |
| --- | --- | --- |
| `500001` | 操作失败 | 当前登录用户为 `platform_admin`（消息为「平台管理员不能新增或修改账号信息」） |
| `400000` | 无效的参数 | 新增：手机格式错误 / 密码校验失败 / 当前手机号已注册 / 用户身份错误（`identity` 不在字典） |
| `400000` | 无效的参数 | 通用：用户状态错误（`status` 不在字典） |
| `400000` | 无效的参数 | 编辑：密码校验失败（`password` 非空但不满足 `checkPassword` 规则） |
| `400020` | 请求过于频繁，请稍后重试 | 触发限流 |
| `401000`/`401001`/`401003`/`401004` | token 相关 | 未携带/无效/过期/越权 token（HTTP 401） |

> 注：`@NotBlank` 注解校验失败（手机号/昵称/状态为空）由全局异常处理返回 `400000`，消息为注解中的 message 文本。

---

## 二、查询 B 端用户列表

### 2.1 接口信息

| 项目 | 内容 |
| --- | --- |
| 路径 | `POST /admin/sys_user/list` |
| 认证 | 需要 token（`Authorization: Bearer {accessToken}`），且 `userFrom=sys` |
| 权限 | 所有已登录 B 端用户（`super_admin` / `platform_admin` 均可） |
| Content-Type | `application/json` |
| 操作审计 | 无 |

### 2.2 请求参数

请求体为 `SysUserListReqDTO`（**无分页字段**）：

| 字段 | 类型 | 必传 | 校验规则 | 默认值 | 含义 | 查询匹配方式 |
| --- | --- | --- | --- | --- | --- | --- |
| `userId` | Long | 否 | — | `null` | 用户 ID | 精确匹配（`id =`） |
| `identity` | String | 否 | — | `null` | 身份 | 精确匹配（`identity =`） |
| `phoneNumber` | String | 否 | — | `null` | 手机号（**明文**，服务端加密后比对） | 精确匹配（`phone_number =` 加密值） |
| `nickName` | String | 否 | — | `null` | 昵称 | 精确匹配（`nick_name =`） |
| `status` | String | 否 | — | `null` | 状态 | 精确匹配（`status =`） |
| `remark` | String | 否 | — | `null` | 备注 | 模糊匹配（`remark like concat('%', ?, '%')`） |

> **重要**：
> - 该接口**不分页**，返回所有匹配记录。前端若数据量大需自行前端分页或后端改造。
> - 除 `remark` 为模糊匹配外，其余字段均为**精确匹配**。`nickName` 是精确匹配（非模糊），如需模糊查询昵称需后端调整 SQL。
> - 所有过滤字段均为可选，全部不传则返回全部 B 端用户。
> - 请求体 DTO 无 `@Validated` 校验注解，字段全部可空。

### 2.3 请求示例

查询所有平台管理员：

```http
POST /admin/sys_user/list
Authorization: Bearer eyJhbGciOiJIUzI1NiJ9.....
Content-Type: application/json

{
  "identity": "platform_admin"
}
```

按昵称精确查询：

```http
POST /admin/sys_user/list
Authorization: Bearer eyJhbGciOiJIUzI1NiJ9.....
Content-Type: application/json

{
  "nickName": "张三"
}
```

查询全部（空对象）：

```http
POST /admin/sys_user/list
Authorization: Bearer eyJhbGciOiJIUzI1NiJ9.....
Content-Type: application/json

{}
```

### 2.4 响应结构

`Result<List<SysUserVO>>`，列表元素 `SysUserVO` 字段：

| 字段 | 类型 | 含义 |
| --- | --- | --- |
| `userId` | Long | 用户 ID |
| `identity` | String | 身份（`super_admin` / `platform_admin`） |
| `phoneNumber` | String | 手机号（**服务端解密后的明文**） |
| `nickName` | String | 昵称 |
| `status` | String | 状态（`enable` / `disable`） |
| `remark` | String | 备注 |

> 注意：响应**不含密码字段**，密码永远不会返回给前端。

### 2.5 响应示例

```json
{
  "code": 200000,
  "errMsg": "操作成功",
  "data": [
    {
      "userId": 1,
      "identity": "super_admin",
      "phoneNumber": "13800138000",
      "nickName": "超级管理员",
      "status": "enable",
      "remark": "系统初始账号"
    },
    {
      "userId": 2,
      "identity": "platform_admin",
      "phoneNumber": "13800138001",
      "nickName": "张三",
      "status": "enable",
      "remark": "运营平台管理员"
    }
  ]
}
```

### 2.6 错误码说明

| code | errMsg | 触发场景 |
| --- | --- | --- |
| `401000`/`401001`/`401003`/`401004` | token 相关 | 未携带/无效/过期/越权 token（HTTP 401） |

> 该接口为纯查询，不主动抛业务错误。无匹配数据时返回 `data` 为空数组 `[]`，`code` 仍为 `200000`。

---

## 三、枚举值

### 3.1 B 端用户身份（identity）

| 值 | 含义 | 是否可新增/编辑用户 |
| --- | --- | --- |
| `super_admin` | 超级管理员 | 是 |
| `platform_admin` | 平台管理员 | 否（调用 `add_edit` 返回 `500001`） |

### 3.2 B 端用户状态（status）

| 值 | 含义 | 说明 |
| --- | --- | --- |
| `enable` | 启用 | 可正常登录 |
| `disable` | 停用 | 无法登录；被改为停用时会**强制下线**该用户（删除其 Redis 登录态） |

> 注意：B 端用户 `status` 为 **String 类型**（`enable`/`disable`），与字典状态（Integer `1`/`0`）不同，请勿混淆。`identity` 与 `status` 的合法值均由字典数据校验，前端下拉选项宜从字典获取。

---

## 四、前端注意事项

1. **按钮权限控制**：「新增用户」「编辑用户」按钮仅当当前登录用户 `identity === 'super_admin'` 时显示；`platform_admin` 调用 `add_edit` 会被服务端以 `500001` 拒绝。
2. **新增/编辑共用接口**：通过 `userId` 字段区分。新增时不传 `userId`（或传 `null`）；编辑时必传 `userId`。
3. **密码字段处理**：
   - 该接口密码为**明文传输**（与登录接口 AES 加密不同），前端无需加密。
   - 编辑时若不修改密码，**不要传 `password` 字段**（或传空），传非空值会触发密码校验并覆盖原密码。
   - 密码校验规则：长度 1-20，仅允许字母、数字及 `!@#$%^&*`。前端应做相同前置校验以避免 `400000`。
4. **手机号唯一性**：新增时手机号必须未被注册，否则返回 `400000 当前手机号已注册`。编辑场景不修改手机号。
5. **停用即下线**：将用户 `status` 改为 `disable` 后，该用户会立即被强制下线，其下次请求会收到 `401003`。前端停用操作应二次确认。
6. **列表不分页**：`POST /admin/sys_user/list` 返回完整匹配列表，无 `totals`/`totalPages`。如需分页展示，前端可前端分页或后端改造为分页接口。
7. **列表查询匹配方式**：`phoneNumber`/`nickName`/`identity`/`status`/`userId` 均为精确匹配，仅 `remark` 为模糊匹配。若需按昵称模糊搜索，当前接口不支持，需后端调整。
8. **手机号加解密**：列表查询入参 `phoneNumber` 传明文，服务端加密后比对；响应中 `phoneNumber` 为服务端解密后的明文，前端可直接展示。
9. **身份/状态下拉选项**：`identity` 与 `status` 的合法值受字典约束，建议前端下拉选项从字典接口获取以保证与后端一致。
