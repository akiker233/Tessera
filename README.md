# 双因素验证器（Tessera）

Android 上的 TOTP 验证码应用：***、加密本地存储、多云同步、多来源导入。

## 已实现功能

| 需求 | 实现位置 |
|---|---|
| 好看的 UI / 简洁风格 | Jetpack Compose + Material 3 + 动态取色（`ui/theme/Theme.kt`、`feature/home/HomeScreen.kt`） |
| 显示服务图标 | `icons/` 里 1954 张 PNG 全量打包进 assets，运行时按 issuer 归一化查索引（`data/icon/*`、`tools/gen_icon_index.ps1`） |
| 适配 Android 11–17 | `minSdk 30`、`targetSdk 36`、`compileSdk 37`（Android 15+ 强制 edge-to-edge 已启用） |
| 云同步 | WebDAV / GitHub 仓库 / GitHub Gist / Google Drive / OneDrive（`sync/*`） |
| 手动 + 自动同步 + 状态 | `sync/SyncWorker.kt`（WorkManager，15min–1天）、`feature/sync/SyncScreen.kt` |
| 从其他 2FA 软件导入 | 2FAS / Stratum / Aegis / andOTP / Raivo OTP / LastPass / Google Authenticator（`importer/*`） |
| 拍照导入 | ML Kit 相机扫码 + 相册图片识别（`feature/scan/ScanScreen.kt`） |
| 回收站 | 软删除 + 保留期（默认 30 天）+ 还原/彻底删除/清空（`feature/trash/*`） |
| 显隐验证码 | 首页眼睛开关，隐藏显示占位符 |
| 搜索 | issuer/账号模糊搜索（200ms 防抖由 ViewModel 承担） |
| 分类 | 分类 Chip 筛选 + 详情页新建分类 |
| 即将过期显示下一个令牌 | 剩余 ≤ 阈值（默认 5s，可调 3/5/10s）时淡入下一验证码 |
| 应用锁 | PIN（PBKDF2-HMAC-SHA256，21 万次迭代）+ 生物识别（`ui/lock/*`、`di/LockPrefs.kt`） |
| 截图开关 | 默认禁止截图（FLAG_SECURE），设置页可开 |

## 环境与构建

本机已验证可用组合（实测通过 `assembleDebug` 与 `testDebugUnitTest`）：

- JDK 21（`JAVA_HOME` 指向 Temurin 21）
- Gradle 9.7.1（项目自带 wrapper）
- AGP 9.4.1（**内置 Kotlin 支持，因此不要再加 `org.jetbrains.kotlin.android` 插件**）
- Android SDK：`D:/Android/Sdk`（`local.properties` 里已配置），platform 37 + build-tools 37.0.0

```powershell
# 编译 Debug APK
.\gradlew.bat :app:assembleDebug --no-daemon --no-configuration-cache

# 跑单元测试
.\gradlew.bat :app:testDebugUnitTest --no-daemon --no-configuration-cache
```

> 注意：本项目在 AGP 9.4.1 下**不要**开启 `org.gradle.configuration-cache`（实测 `testDebugUnitTest` 会在配置阶段卡住），
> 因此命令行请带 `--no-configuration-cache`。

### 重新生成图标索引

```powershell
pwsh -File tools/gen_icon_index.ps1
```

脚本会：把 `icons/*.png` 复制到 `app/src/main/assets/icons/`，并生成 `app/src/main/assets/icon-index.json`
（1923 个归一化键）。归一化规则（NFKC → 只保留字母数字 → 小写）与 Kotlin 端 `IconNormalizer` 必须一致，
否则运行时匹配不到图标。

## 需要你补全的配置

1. **Google Drive**：在 Google Cloud Console 创建 Android OAuth 客户端，把客户端 ID 填到
   `GoogleDriveProvider.GOOGLE_OAUTH_CLIENT_ID`，并用 GoogleSignIn 请求 `drive.appdata` 权限后调用
   `setAccessToken()`。当前为占位常量，未配置时同步会提示"尚未授权"。
2. **OneDrive**：在 Azure 门户注册应用（重定向 URI 用自定义 scheme），把客户端 ID 填到
   `OneDriveProvider.ONEDRIVE_CLIENT_ID`，用 MSAL 登录后调用 `setAccessToken()`。
   > 代码里**故意没有**硬依赖 MSAL 8.5.0：它传递依赖的 `com.microsoft.device.display:display-mask:0.3.0`
   > 已从 Maven Central 下架，会导致依赖解析失败。需要时请自行引入可用的 MSAL 版本。
3. **WebDAV / GitHub / Gist**：无需开发者账号，在应用「设置 → 云同步」里直接填地址与令牌即可使用。

## 安全设计

- **本地**：条目密钥用 Android Keystore 里的 AES-256-GCM 主密钥加密后入库（`data/keystore/SecretCipher.kt`）；
  数据库本身是 SQLCipher 加密库，库口令同样由 Keystore 包裹。
- **云端**：同步包是 AES-256-GCM 加密的 JSON，密钥由**独立同步口令**经 PBKDF2 派生；
  服务商（GitHub/WebDAV/云盘）无法读取内容，且同步口令与 PIN、Keystore 主密钥互相独立。
- **界面**：编辑已有条目时不回填明文密钥（留空即保持不变），避免密钥出现在输入框/输入法缓存里；
  默认禁止截图（FLAG_SECURE）。
- **合并**：条目级 `syncRev` → `updatedAt` 逐级比较，删除以墓碑参与同步，绝不盲写覆盖。

## 测试

`app/src/test/java/...` 下的 JVM 单元测试（无需模拟器）：

- `TotpEngineTest`：RFC 6238 附录 B 全部 18 个官方向量（SHA1/256/512）+ Steam 字母表 + Base32 + 倒计时
- `OtpAuthUriTest`：otpauth URI 解析、Google Authenticator 迁移 protobuf 解析（含畸形输入）
- `ImporterTest`：七种来源的手写样本 + 来源自动识别 + 失败隔离
- `IconNormalizerTest`：真实图标文件名的命名陷阱（多点号、Unicode、`_light`/`_dark`、全角）
- `MergeTest`：同步合并（新增/冲突/墓碑/稳定性）

## 已知取舍

- 图标选择器在**图标用变体**场景下统一使用默认变体（`_light`/`_dark` 只在存在时按主题优先命中）。
- 2FAS/Aegis/Raivo 的**加密导出**目前只识别并提示需要口令，未实现离线口令解密。
- HMAC-MD5 的迁移条目降级为 SHA1（`OtpAlgorithm` 不含 MD5）。
- 未接入签名发布版（无 keystore），只产出 debug APK。

## APK 体积（实测）

| 指标 | 数值 |
|---|---|
| `app-debug.apk` | **88.8 MB** |
| 其中 native 库 | 7.34 MB（SQLCipher 4 ABI + Play 服务扫码） |
| 其中 dex | 69.41 MB（debug 未开 R8，含 Compose/ML Kit/Play 服务） |
| 其中 assets | 10.71 MB（1954 张图标 + 索引） |

已做过一次体积优化：把 `com.google.mlkit:barcode-scanning`（内置模型）换成
`com.google.android.gms:play-services-mlkit-barcode-scanning`（运行时由 Play 服务提供），
native 库从 26.63 MB 降到 7.34 MB，APK 从 147.8 MB 降到 88.8 MB。

进一步压缩的手段（未实施，需要你确认）：
1. `release` 构建开启 R8 + `shrinkResources`（debug 无法收缩，预计 dex 大幅下降）；
2. `splits { abi { ... } }` 按 ABI 拆分，或只保留 arm64-v8a/armeabi-v7a（注意：会失去 x86_64 模拟器支持）；
3. 用 Pillow 把图标转成 ≤96px WebP（当前 1954 张平均 5.5 KB，全量仅 10.45 MB，收益有限）。
