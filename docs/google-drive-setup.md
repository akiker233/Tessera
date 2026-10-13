# Google Drive 云同步配置指南（Tessera / 凭牌）

> **先看结论**：后端 API 调用与**应用内授权都已实现**（2026-10-10）。你只需完成第一部分的控制台配置，然后在应用里点「登录 Google 账号」即可。

本文档基于本仓库的实际实现编写，不是通用教程：

| 事实 | 出处 |
|---|---|
| 用 `GoogleAccountCredential.usingOAuth2(context, DriveScopes.DRIVE_APPDATA)` 取令牌 | `sync/OAuthProviders.kt` |
| 备份写入 **appDataFolder**（应用私有目录），文件名 `tessera-backup-1.json` | 同上 |
| 先 `files.list` 找同名文件 → 有则 `files.update`，无则 `files.create` | 同上 |
| 调试安装的包名是 `com.tessera.otp.debug`（release 才是 `com.tessera.otp`） | `app/build.gradle.kts` 的 `applicationIdSuffix = ".debug"` |
| 授权改用 **GMS `AuthorizationClient`**（不是 GoogleSignIn） | `sync/GoogleDriveAuth.kt` |
| 令牌直接以 Bearer 注入 Drive 请求；不再使用 `GoogleAccountCredential` | `sync/OAuthProviders.kt` |

---

## 一、Google Cloud 控制台配置

### 1. 建项目
打开 https://console.cloud.google.com → 顶部项目选择器 → **新建项目**（名字随意，例如 `Tessera Sync`）。

### 2. 启用 Google Drive API
**API 和服务 → 库** → 搜索 `Google Drive API` → **启用**。
> 不启用就调用会得到 `403 accessNotConfigured`。

### 3. 配置 OAuth 同意屏幕（控制台已改版为 **Google Auth Platform**）
入口：**API 和服务 → OAuth 同意屏幕**（会自动跳到 Google Auth Platform），或直接访问 https://console.cloud.google.com/auth/overview

| 页面 | 要填什么 |
|---|---|
| **品牌宣传 (Branding)** | 应用名称（用户看到的名字）、用户支持邮箱、开发者联系邮箱 |
| **受众 (Audience)** | **外部 (External)**；处于 **测试** 状态时，把你的 Google 账号加进**测试用户**。若你有 Google Workspace 组织，可选"内部"免去测试用户限制 |
| **数据访问 (Data Access)** | 添加范围：`https://www.googleapis.com/auth/drive.appdata` |
| **客户端 (Clients)** | 下一步创建 |

**关于验证审核**：Google 把 scope 分为敏感/受限两级，需要提交验证的是敏感与受限范围。`drive.appdata` 与 `drive.file` 属于**非敏感**范围（因此这也是本项目选它、而不是 `drive` 全盘权限的原因之一）。
> ⚠️ 请以 **数据访问 (Data Access)** 页面当时显示的提示为准——Google 的 scope 分类会调整；如果它提示需要验证，你可以先用"测试"状态自用。

### 4. 创建 OAuth 客户端（**类型必须选 Android**）
**客户端 (Clients) → 创建客户端 → 应用类型选「Android」**

| 字段 | 值 |
|---|---|
| 软件包名称 | **`com.tessera.otp.debug`**（调试安装！见下方说明） |
| SHA-1 证书指纹 | 你的签名证书指纹（下方命令获取） |

> 🔴 **这是整个配置里最容易踩的坑**：Android 客户端靠「包名 + 签名指纹」识别应用，
> 二者**必须与实际安装的 APK 完全一致**。本项目的 debug 变体带 `.debug` 后缀，
> 所以调试包要登记 `com.tessera.otp.debug`；如果你将来出正式包，还要为 `com.tessera.otp`
> + release 签名指纹**再建一个** Android 客户端（同一项目可以有多个）。

### 5. 取 SHA-1（两种方式，任选）
```powershell
# 方式一（推荐）：直接读 debug 签名
cd D:\Projects\Android\2fa
.\gradlew.bat :app:signingReport

# 方式二：用 keytool 读默认 debug keystore
& "$env:JAVA_HOME\bin\keytool.exe" -list -v `
  -keystore "$env:USERPROFILE\.android\debug.keystore" `
  -alias androiddebugkey -storepass android -keypass android
```
输出里 **SHA1** 那一行（形如 `AA:BB:CC:...`）就是。填进控制台时**去掉冒号、全大写或全小写均可**（控制台接受带冒号格式，粘贴原样最保险）。

> ⚠️ `debug.keystore` 每台机器不同，换电脑/清了 `~/.android` 就会变——这时需要重新登记 SHA-1，
> 否则会得到经典的 `ApiException: 10 (DEVELOPER_ERROR)`。

### 6. 为什么**不需要**客户端 ID / 客户端密钥
- **Android 类型的 OAuth 客户端没有 client secret**（移动端是公共客户端）。
- 更关键的是：`GoogleAccountCredential` 通过 **Google Play 服务**获取访问令牌（`GoogleAuthUtil`），
  校验依据是**已登记的包名 + SHA-1**，**运行时根本用不到客户端 ID**。
- 因此本仓库里的 `GOOGLE_OAUTH_CLIENT_ID` 常量属于**死代码**，填它没有任何作用；
  真正决定成败的是第 4、5 步是否登记正确。

---

## 二、应用内授权实现说明（已完成，此处为原理与排错）

### 为什么必须有这一步
用户从未把 `drive.appdata` 权限授予本应用时，`GoogleAuthUtil.getToken` 会抛
`UserRecoverableAuthException`——它不是错误配置，而是"需要用户点同意"的信号。

### 推荐实现（Play Services 授权，`play-services-auth:22.0.0`）
> ⚠️ **勘误**：本文档早先写过"`GoogleSignIn` 在 22.0.0 中存在"，**那是错的**。
> 我当时的校验脚本用 `javap` 输出里是否含 "Error" 判断类是否存在，而本机 JDK 是中文的，
> 报错写的是"错误"，正则没命中，于是把不存在的类误判成存在。
> 改用**列出 AAR 内 classes.jar 清单**复核后确认：**`GoogleSignIn` 已从 play-services-auth 22.0.0 移除**，
> 同版本中可用的是 `com.google.android.gms.auth.api.identity.AuthorizationClient`。
> 本仓库的实现据此改为 AuthorizationClient。

```kotlin
// 1) 请求 drive.appdata 权限（会弹系统同意页）
val options = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
    .requestScopes(Scope(GoogleDriveProvider.DRIVE_APPDATA_SCOPE))
    .setAccountName(email)          // 或省略，让用户在系统账号选择器里选
    .build()
val client = GoogleSignIn.getClient(activity, options)

// 2) 用 ActivityResultContracts.StartActivityForResult 启动 client.signInIntent
//    回调拿到 GoogleSignInAccount 后，把 email 存进 SettingsRepository 的 googleAccount 字段
// 3) 之后 GoogleDriveProvider 用该 email 通过 Play Services 静默取令牌（自动刷新）
```

### 为什么这条路比 OneDrive 简单
Google Play 服务**托管令牌与刷新**：拿到同意后，应用无需自己保存 refresh token，
也不会遇到"1 小时后全 401"的问题（`GoogleAccountCredential` 每次调用自动取有效令牌）。

### 备选实现
**Credential Manager**（`androidx.credentials` + `com.google.android.gms.auth.api.identity.AuthorizationClient`）——
Google 新推荐的方式，同样在本仓库依赖中可用；较 legacy 的 `GoogleSignIn` 更符合未来方向，但代码量略多。

---

## 三、验证清单

1. **控制台探针**：启用 API 后访问
   https://console.cloud.google.com/apis/api/drive.googleapis.com/metrics
   能看到流量图 = API 已启用。
2. **应用内**：先设**同步口令** → 云同步 → Google Drive → 填/选账号 → 首次应弹出**同意页** →
   同意后点「立即同步」，提示已上传 N 条。
3. **落盘检查**：备份位于 **应用数据文件夹（appDataFolder）**，在 Drive 网页版里**看不到**。
   可用 [Google Drive API 的 files.list 试跑页](https://developers.google.com/drive/api/reference/rest/v3/files/list)
   指定 `spaces=appDataFolder` 查看是否出现 `tessera-backup-1.json`。
4. **双向验证**：清除应用数据 → 用**同一同步口令**同步 → 条目完整回来（同时验证下载链路）。

---

## 四、常见错误对照表

| 现象 / 报错 | 原因 | 处理 |
|---|---|---|
| `ApiException: 10 (DEVELOPER_ERROR)` | 包名或 SHA-1 与控制台登记不一致（含"用了 release 指纹装 debug 包"这类） | 重新按第 4、5 步登记；换机器后必须重新登记 |
| `UserRecoverableAuthException` | 用户尚未同意 `drive.appdata` | 补第二部分代码，弹同意页 |
| `403 accessNotConfigured` | Drive API 未启用 | 控制台启用 Google Drive API |
| `403 insufficientPermissions` / `ACCESS_TOKEN_SCOPE_INSUFFICIENT` | 授权范围不含 `drive.appdata` | 数据访问页添加该 scope，并重新授权 |
| `invalid_grant` / 用几天后突然 401 | 同意屏幕处于 **测试** 状态时，refresh token **7 天过期** | 加测试用户仅解决"能登录"，长期使用需发布为正式版（`drive.appdata` 非敏感，通常无需审核） |
| 应用内提示"尚未选择 Google 账号" | `googleAccount` 为空 | 先完成授权并把账号存进设置（第二部分） |

---

## 五、与 OneDrive 的现状对比（诚实说明）

| 项 | OneDrive | Google Drive |
|---|---|---|
| 后端 API 调用（上传/下载/eTag 并发） | ✅ 已实现（刚修过两个 bug） | ✅ 已实现 |
| 应用内登录/授权 UI | ✅ 已实现（AppAuth + PKCE） | ✅ 已实现（AuthorizationClient，云同步页可登录） |
| 令牌续期 | ✅ refresh token 自动续期 | ✅ 由 Play Services 托管，静默获取（无需存 refresh token） |
| 前置配置成本 | Azure 账号（个人账号需先建租户） | Google 账号即可，无需订阅 |
| 真机验证 | ❌ 未验证 | ❌ 未验证 |
| 授权实现方式 | AppAuth 授权码 + PKCE + 自管 refresh token | GMS AuthorizationClient（后台可静默取令牌） |

---

## 六、验证状态说明

本文档所述控制台步骤由我**核对过官方文档与当前控制台结构**（Google Auth Platform 的 品牌宣传/受众/数据访问/客户端 四个页面），
但**没有在真实 GCP 项目上走一遍**；应用内授权代码**尚未编写**。
因此：把本文当作"配置路线图 + 坑位清单"，实际执行中若某一步与控制台现状不符，把截图发我，我按你的实际界面校准文档。

> 文档中"drive.appdata 属非敏感范围、通常无需验证审核"来自 Google 的 scope 分类，抓取官方页面时网络失败未能复核，
> 请以**数据访问 (Data Access)** 页面的实时提示为准。

---

## 七、实现说明（2026-10-10 补充）

### 授权流程
1. 应用调用 `Identity.getAuthorizationClient(context).authorize(request)`（scope = `drive.appdata`）；
2. 若返回 `hasResolution() = true`，取其 `pendingIntent` 启动授权界面（账号选择 + 同意页）；
3. 回跳后用 `getAuthorizationResultFromIntent(intent)` 取出 `accessToken`，并把「已授权」标记写入设置；
4. 之后每次同步由 `GoogleDriveAuth.accessToken()` **静默**取令牌（后台 WorkManager 同步因此可用），
   令牌直接以 `Authorization: Bearer ...` 注入 Drive 请求。

### 关于"断开"
GMS 的授权**无法由应用单方面撤销**（旧的 `GoogleSignIn.signOut` 已随 API 一起移除）。
「断开」只清除本应用的标记；要彻底撤销请到 https://myaccount.google.com/permissions 移除本应用的访问权限。

### 关于客户端 ID
再次强调：**Android 类型 OAuth 客户端没有 client ID 需要填进应用**（也永远不会有 client secret）。
运行时靠控制台登记的 **包名 + SHA-1** 识别应用。仓库里原有的 `GOOGLE_OAUTH_CLIENT_ID` 常量已删除。
