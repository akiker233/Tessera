# OneDrive 云同步配置教程（Tessera / 凭牌）

> **应用内登录已于 2026-10-10 实现**（AppAuth 授权码 + PKCE，令牌自动续期）。
> 你现在只需做第一部分（Azure 配置）+ 第二部分第 5 步（填客户端 ID），然后在应用里点登录即可。

---

## 一、Azure 侧配置（必做，约 10 分钟）

### 1. 进入应用注册
- 打开 https://portal.azure.com → 搜索并进入 **Microsoft Entra ID**（旧名 Azure Active Directory）
- 左侧 **应用注册** → **新注册**

### 2. 填写注册信息
| 字段 | 建议值 | 说明 |
|---|---|---|
| 名称 | `Tessera`（随你） | 只用于你在门户里辨认 |
| 支持的账户类型 | **任何组织目录中的账户和个人 Microsoft 账户** | 个人 OneDrive 用户也能登录；只选"仅个人账户"亦可 |
| 重定向 URI | 平台选 **移动和桌面应用程序**，填 `msal<客户端ID>://auth` | 见下面第 3 步；也可以自定义成 `tessera://auth` |

注册后记录 **应用程序(客户端) ID**。

> ⚠️ **不要创建客户端密码（client secret）**。Android 属于"公共客户端"，secret 放在 APK 里等于公开；OAuth 2.0 授权码 + PKCE 才是移动端的正确做法。

### 3. 重定向 URI 的两种写法
- 用 **MSAL** 库：`msal{客户端ID}://auth`（例如 `msal1234abcd-....://auth`）
- 用 **AppAuth** 或自己写：任意自定义 scheme，例如 `tessera://auth`

无论哪种，**Android 侧都要在 AndroidManifest 里声明对应的 intent-filter**（见第二部分）。

### 4. 配置 API 权限
- 应用注册 → **API 权限** → **添加权限** → **Microsoft Graph** → **委托的权限**
- 勾选：
  - `Files.ReadWrite.AppFolder` —— **只需要这一个就够了**
  - `offline_access` —— 拿 refresh token（否则 1 小时后失效，无法自动续期）
  - `User.Read`（可选，仅用于显示当前登录账号）
- 个人账户无需"授予管理员同意"；工作/学校账户若被管理员限制策略，需管理员同意

### 5. 为什么只要 `Files.ReadWrite.AppFolder`
该权限只允许访问应用的**私有文件夹** `/me/drive/special/approot`——也就是本应用代码里使用的路径。
这个文件夹在 OneDrive 网页/客户端里**不可见**，也不会出现在"我的文件"中；即使令牌泄露，攻击者也接触不到你其它 OneDrive 文件。这正是本项目选择它的原因。

---

## 二、客户端代码（已实现，此处为对照说明）

### 1. 声明重定向 intent-filter
在 `app/src/main/AndroidManifest.xml` 的 `<activity android:name=".MainActivity">` 内加入（scheme 与你在 Azure 填的一致）：

```xml
<intent-filter>
    <action android:name="android.intent.action.VIEW" />
    <category android:name="android.intent.category.DEFAULT" />
    <category android:name="android.intent.category.BROWSABLE" />
    <data android:scheme="tessera" android:host="auth" />
</intent-filter>
```

### 2. 引入一个 OAuth 库
| 方案 | 依赖 | 备注 |
|---|---|---|
| **AppAuth（推荐）** | `net.openid:appauth:0.11.1` | 轻量、通用 OIDC，支持 PKCE，也可用于其它 OIDC 服务 |
| MSAL | `com.microsoft.identity.client:msal` | 微软官方，但 **8.5.0 会拉取已从 Maven Central 下架的 `com.microsoft.device.display:display-mask:0.3.0`**，导致依赖解析失败（本项目曾因此移除该依赖）。要用就得选一个不依赖该工件的版本，或加 `exclude` |

### 3. 授权码 + PKCE 换取令牌
流程（AppAuth）：
1. `AuthorizationRequest` 指向 `https://login.microsoftonline.com/common/oauth2/v2.0/authorize`
2. scope = `Files.ReadWrite.AppFolder offline_access`
3. 用 CustomTabs 打开 → 用户登录 → 回调到 `tessera://auth` 拿 **authorization code**
4. 用 code + PKCE verifier 换 **access_token + refresh_token**
5. 把 accessToken 交给现有代码：
   ```kotlin
   OneDriveProvider(accessToken)   // 或 provider.setAccessToken(token)
   ```

### 4. 令牌续期（必须）
access token 只有 **约 1 小时**有效期。必须：
- 把 `refresh_token` 加密存储（用项目里已有的 `SecretCipher` + Keystore 即可）
- 每次同步前检查是否过期，过期就用 refresh_token 换新的
- 否则会出现"刚配好能同步，一小时后全部 401"

### 5. 填入客户端 ID（**你现在唯一必须做的配置改动**）
在项目根目录的 `local.properties`（已被 `.gitignore` 忽略、不会入库）里加一行：

```properties
ONEDRIVE_CLIENT_ID=你的客户端ID
```

构建时会经 BuildConfig 注入到 `OneDriveProvider.ONEDRIVE_CLIENT_ID`。未配置时该项为空串，云同步页的「登录微软账号」入口会自动禁用并提示先配置。

---

## 三、当前状态（重要）

| 项 | 状态 |
|---|---|
| OneDrive 后端（Graph 调用、上传/下载、eTag 并发控制） | ✅ 已实现可用 |
| Azure 配置步骤 | 📄 就是本文第一部分 |
| **应用内登录入口** | ✅ **已实现**——云同步页「登录微软账号」，AppAuth 授权码 + PKCE |
| 令牌续期 | ✅ 已实现——refresh token 自动续期，令牌用 Keystore 主密钥加密后落盘 |
| 同步口令（加密用） | ✅ 已实现（设置 → 云同步 → 同步口令），**同步前必须先设置** |

### 因此目前可用的云同步方式
| 方式 | 可用性 |
|---|---|
| **WebDAV** | ✅ 只需地址 + 用户名 + 密码，开箱可用 |
| **GitHub 仓库 / Gist** | ✅ 只需 Personal Access Token，开箱可用 |
| **Gitee** | ✅ 只需私人令牌（projects 权限），开箱可用 |
| Google Drive | ⚠️ 缺登录入口（只接受手填账号邮箱） |
| **OneDrive** | ✅ 已可用（登录入口已实现；需先填客户端 ID） |

---

## 四、修复记录（2026-10-10）

配置教程写完后发现 OneDrive 后端有两个**真 bug**（与配置无关，是代码问题），已一并修复：

1. **读/写文件名不一致**：`pull()` 找的是 `tessera-backup-1.json`，而 `push()` 写的是 `tessera.vault` —— 结果是"写得进、读不出来"。
   修复：抽出常量 `BACKUP_FILE_NAME`，读与写都从它拼 URL。

2. **`pull()` 根本没下载内容**：原实现只取了 `ETag`，返回 `RemoteFile(content = "")`。
   后果比第 1 点更严重：同步引擎看到"远端内容为空"会判定**云端为空**，于是直接用本地覆盖云端——多设备场景下会**反复覆盖、永远拉不到别人的改动**。
   修复：改为两步——先取元数据（`eTag` + `@microsoft.graph.downloadUrl`），再按 downloadUrl 下载内容；并处理 Graph 把 `&` 转义成 `\u0026` 的问题。

---

## 五、验证清单（补齐登录后按此验收）

1. **命令行探针**（确认 Azure 配置与权限正确）
   ```bash
   curl -H "Authorization: Bearer <access_token>" \
     https://graph.microsoft.com/v1.0/me/drive/special/approot
   ```
   返回 200 且 JSON 里 `name` 为 `approot` → 权限配置正确。

2. **应用内**
   - 先在「云同步 → 同步口令」设置一个 ≥8 位口令（云端内容用它加密）
   - 选择 OneDrive → 完成登录 → 点「立即同步」
   - 首次同步应提示"已上传 N 条（云端此前为空）"

3. **落盘检查**：OneDrive 网页版的"应用"文件夹（通常不可见，可用 `/me/drive/special/approot/children` 查）应出现 `tessera-backup-1.json`，且内容为 Base64/加密文本，**不含任何可读的账号或密钥明文**。

4. **双向验证**：清除应用数据 → 用**同一个同步口令**同步 → 条目应完整回来。
   > 这里能同时验证"下载链路"是否正常——正是第四节第 2 个 bug 的验收点。

---

## 六、应用内操作步骤（登录已实现）

1. **Azure**：按第一部分完成注册，重定向 URI 填 `tessera://auth`，权限加 `Files.ReadWrite.AppFolder` + `offline_access`，复制**应用程序(客户端) ID**。
2. **填 ID**：在项目根目录 `local.properties` 里加 `ONEDRIVE_CLIENT_ID=你的客户端ID`（该文件不入库），然后重新编译安装。
3. **设同步口令**：应用 → 云同步 → 同步口令（≥8 位）。云端内容是**用这个口令加密**的，没设口令不会同步。
4. **登录**：云同步 → OneDrive → **登录微软账号** → 跳系统浏览器登录 → 授权 → 自动回到应用；卡片状态变为"已连接"。
5. **同步**：点「立即同步」。首次应提示已上传 N 条。
6. **验收**：另一台设备/清数据后用**同一口令**同步，条目应完整回来。

### 登录失败时怎么看原因
应用会直接把原因弹出来（不会静默失败）。常见对照：

| 提示 | 原因 | 处理 |
|---|---|---|
| `AADSTS50011` / redirect_uri mismatch | Azure 里登记的重定向 URI 与应用不一致 | 必须是 `tessera://auth`（注意没有末尾斜杠） |
| `invalid_grant` | 授权码已用过/过期，或 code_verifier 丢失 | 重新登录一次 |
| `AADSTS65001` / consent required | 权限未授予（工作/学校账号常需管理员同意） | 让管理员授予同意，或换个人账号 |
| `尚未连接 OneDrive` | 同步时未登录 | 先完成第 4 步 |

### 安全说明
- 走的是 **PKCE**，APK 里**没有也不需要** client secret；
- access/refresh token 用 Android Keystore 主密钥加密后存储，明文不落盘；
- 权限只有 `Files.ReadWrite.AppFolder`，只够访问应用私有文件夹，无法读取你其它 OneDrive 文件；
- 「断开」只清除本地令牌，**不会删除云端备份**。

---

## 七、验证状态（诚实说明）

| 项 | 状态 |
|---|---|
| 编译 + 打包 | ✅ 通过 |
| 单元测试（59 项） | ✅ 通过 |
| **真机登录流程** | ❌ **未经任何真机验证**——AppAuth 的浏览器回跳、code_verifier 传递、令牌续期全都没在设备上跑过 |

登录链路涉及"浏览器跳转 → 自定义 scheme 回跳 → 冷启动/onNewIntent"多个环节，这类流程**必须在真机上验证**。如果卡在某一步，把应用弹出的错误信息发我，我按错误码定位。
