<div align="center">

<img src="branding/tessera-round-192.png" width="120" alt="Tessera" />

# Tessera · 凭牌

**Android 上的本地优先 TOTP 验证码应用** —— 加密存储、多云同步、多来源导入。

<p>
  <img alt="Platform" src="https://img.shields.io/badge/Platform-Android%2011%2B%20(API%2030)-3DDC84?logo=android&logoColor=white" />
  <img alt="Kotlin" src="https://img.shields.io/badge/Kotlin-2.3.21-7F52FF?logo=kotlin&logoColor=white" />
  <img alt="AGP" src="https://img.shields.io/badge/AGP-9.4.1-3DDC84?logo=android&logoColor=white" />
  <img alt="Compose" src="https://img.shields.io/badge/Compose%20BOM-2026.09.00-4285F4?logo=jetpackcompose&logoColor=white" />
  <img alt="License" src="https://img.shields.io/badge/License-GPL--3.0-blue" />
</p>

<p>
  <a href="https://github.com/akiker233/Tessera/actions/workflows/android.yml"><img alt="CI" src="https://github.com/akiker233/Tessera/actions/workflows/android.yml/badge.svg" /></a>
</p>

</div>

---

## 简介

Tessera（凭牌）是一款纯本地的双因素认证（TOTP）应用。验证码全部在设备上生成，条目密钥经 Android Keystore 主密钥加密后写入 SQLCipher 数据库；如需跨设备，还可把**端到端加密**的备份同步到你自己的云盘或代码托管平台——服务商只能看到密文。

- 🔐 **本地优先**：数据库 SQLCipher 加密，条目密钥由 Keystore 独立包裹，主密钥不出安全硬件。
- ☁️ **自带云**：WebDAV / S3 对象存储 / GitHub / Gist / Gitee / Google Drive / OneDrive，7 种后端任选，可同时启用多个。
- 👥 **多账号**：WebDAV、S3 对象存储、Google Drive、OneDrive 均支持配置**多个账号/服务器/桶**分别同步，每种后端都可起备注名加以区分。
- 📥 **易迁移**：支持 2FAS、Aegis、andOTP、Raivo、LastPass、Stratum、Google Authenticator 等来源导入。
- 🎨 **现代 UI**：Jetpack Compose + Material 3 + 动态取色，内置 1900+ 服务图标。

## 功能一览

| 功能 | 说明 |
|---|---|
| TOTP 生成 | RFC 6238，SHA1/SHA256/SHA512、6/7/8 位、自定义周期，兼容 Steam 字母表 |
| 服务图标 | `app/src/main/assets/icons/` 内 1954 张 WebP 全量打包，按 issuer 归一化（NFKC → 去符号 → 小写）匹配 1923 个索引键，支持 `_light`/`_dark` 变体 |
| 云同步 | 7 种后端；手动 + WorkManager 自动同步（15 分钟 ~ 1 天）；条目级 `syncRev` → `updatedAt` 合并，删除以墓碑参与，绝不盲写覆盖 |
| 多账号 | WebDAV / S3 对象存储 / Google Drive / OneDrive 可添加多份配置，各自独立绑定、独立启停；每种后端都能起备注名，同步结果里按备注名区分 |
| 云端数据管理 | 可查看各后端上的备份并单独删除 |
| 导入 | 2FAS / Stratum / Aegis / andOTP / Raivo OTP / LastPass / Google Authenticator，自动识别来源、失败隔离 |
| 拍照导入 | CameraX 实时扫码 + 相册图片识别（离线捆绑版 ML Kit，无 Play 服务也能解码） |
| 回收站 | 软删除 + 保留期（默认 30 天），支持还原 / 彻底删除 / 清空 |
| 分类 | 分类 Chip 筛选、首页按分类折叠分组、批量归类；分类数据随同步一起走 |
| 应用锁 | PIN（PBKDF2-HMAC-SHA256，21 万次迭代）+ 生物识别，锁定时自动唤起 |
| 隐私 | 默认禁止截图（`FLAG_SECURE`，设置可开）；编辑条目不回填明文密钥 |
| 其他 | 显隐验证码、issuer/账号模糊搜索、即将过期时淡入下一个令牌、导出/导入 Tessera 专属格式（可选加密） |

## 下载与安装

前往 [**Releases**](https://github.com/akiker233/Tessera/releases) 下载 APK。

- 打上 `v*` 标签时，CI 会自动构建**签名 release** 包并按 ABI 拆分：`Tessera-<tag>-<abi>.apk`（`universal` 版一包通吃，不确定架构就选它）。
- 若只是想要最新源码构建，可在 [Actions](https://github.com/akiker233/Tessera/actions/workflows/android.yml) 里下载 `tessera-debug-apk` 产物（universal debug 包）。

`minSdk 30`，即 Android 11 及以上。安装前请确认系统「未知来源应用」已允许。

## 从源码构建

**环境要求**

| 组件 | 版本 |
|---|---|
| JDK | 21（`JAVA_HOME` 指向 Temurin 21） |
| Gradle | 9.7.1（项目自带 wrapper，无需另装） |
| Android Gradle Plugin | 9.4.1（**内置 Kotlin 支持**，不要另加 `org.jetbrains.kotlin.android` 插件） |
| Android SDK | platform 37 + build-tools 37.0.0 |

```bash
git clone https://github.com/akiker233/Tessera.git
cd Tessera

# 编译 debug APK（产物在 app/build/outputs/apk/debug/）
./gradlew :app:assembleDebug

# 运行单元测试
./gradlew :app:testDebugUnitTest
```

> ⚠️ 本项目的 `gradle.properties` 中 `org.gradle.configuration-cache=true`，但在 AGP 9.4.1 下配置缓存会让 `testDebugUnitTest` 卡在配置阶段。命令行请显式加上 `--no-configuration-cache`：
> ```bash
> ./gradlew :app:testDebugUnitTest --no-configuration-cache
> ```
> `app/build.gradle.kts` 里已按 ABI 拆分（armeabi-v7a / arm64-v8a / x86 / x86_64 + universal），因此 `assembleDebug` 会产出 5 个 APK。

**可选的本地配置**（均通过 `local.properties` 注入，该文件已在 `.gitignore` 中）

```properties
# OneDrive OAuth 客户端 ID；未配置时「登录微软账号」入口自动禁用
ONEDRIVE_CLIENT_ID=你的客户端ID
```

`keystore.properties`（同样不入库）可提供 release 签名信息；缺失时 release 构建保持未签名，便于新克隆环境照常构建：

```properties
storeFile=/absolute/path/release.jks
storePassword=...
keyAlias=...
keyPassword=...
```

**重新生成图标索引**（仅在更新图标库后需要）

```powershell
pwsh -File tools/gen_icon_index.ps1
```

脚本会同步 `assets/icons/*.webp` 并生成 `app/src/main/assets/icon-index.json`（1923 个归一化键）。归一化规则必须与 Kotlin 端 `IconNormalizer` 保持一致，否则运行时匹配不到图标。

## 云同步配置

WebDAV / S3 对象存储 / GitHub 仓库 / GitHub Gist / Gitee 无需开发者账号：在应用内「云同步」处填写地址与令牌即可，凭据经 Keystore 加密后落盘。S3 兼容 AWS S3、Cloudflare R2、Backblaze B2、MinIO、阿里云 OSS、腾讯云 COS 等，填写 Endpoint / 区域 / 桶名 / Access Key 即可，自建 MinIO 等不支持虚拟主机风格时可打开「强制路径风格」。

Google Drive 与 OneDrive 需要在对应平台注册应用（后端调用与应用内授权均已实现）：

| 后端 | 前置配置 | 指南 |
|---|---|---|
| Google Drive | Google Cloud Console 创建 **Android 类型 OAuth 客户端**（登记包名 + SHA-1，运行时**无需**客户端 ID），scope 仅 `drive.appdata`（应用私有目录，网页版不可见） | [docs/google-drive-setup.md](docs/google-drive-setup.md) |
| OneDrive | Azure 应用注册（重定向 URI `tessera://auth`，权限 `Files.ReadWrite.AppFolder` + `offline_access`），把客户端 ID 填到 `local.properties`；走 AppAuth 授权码 + PKCE，无 client secret | [docs/onedrive-setup.md](docs/onedrive-setup.md) |

> 同步前必须先设置**同步口令**（≥8 位）：云端内容用它经 PBKDF2 派生的密钥加密，未设口令不会同步。口令一旦设定请务必留存——**丢失后云端备份无法解密**。

## 安全设计

- **本地机密**：SQLCipher 加密数据库；库口令与每条目的密钥均由 Android Keystore 中的 AES-256-GCM 主密钥包裹（`data/keystore/SecretCipher.kt`）。
- **云端密文**：同步包是 AES-256-GCM 加密的 JSON，密钥由**独立同步口令**派生，与 PIN、Keystore 主密钥彼此隔离；服务商无法读取内容。
- **最小权限**：Google Drive 只申请 `drive.appdata`（应用私有文件夹）；OneDrive 只申请 `Files.ReadWrite.AppFolder`（`/me/drive/special/approot`），均无法访问用户的其它文件。
- **令牌保护**：OAuth access/refresh token 用 Keystore 主密钥加密后落盘（`sync/OneDriveAuth.kt`），明文不写入磁盘。
- **界面防泄露**：默认禁止截图；编辑已有条目时不回填明文密钥（留空即保持原值），避免密钥进入输入法与剪贴板缓存。
- **合并安全**：条目级 `syncRev` → `updatedAt` 逐级比较，删除以墓碑参与同步，任何一侧都不会被盲目覆盖。

## 项目结构

```
app/src/main/java/com/tessera/otp/
├── data/          数据层：Room 实体与仓储、SQLCipher 与 Keystore、DataStore 设置、图标索引
│   ├── db/        Room 数据库、DAO、实体
│   ├── keystore/  SecretCipher（AES-256-GCM 主密钥包裹）
│   ├── prefs/     SettingsRepository（AppSettings 与各后端凭据，JSON blob 存储）
│   └── icon/      图标归一化与索引加载
├── domain/        TOTP 引擎、otpauth URI 解析
├── importer/      各来源导入器与来源识别
├── sync/          同步引擎、合并、编解码、7 个后端 Provider、授权（Google / OneDrive）、WorkManager
├── feature/       Compose 界面：home / add / detail / scan / importer / iconpicker / settings / sync / trash / exporter
├── ui/            主题（动态取色）与应用锁
└── di/            轻量依赖装配
```

## 测试

纯 JVM 单元测试，无需模拟器：**123 个用例 / 12 个测试类**。

```bash
./gradlew :app:testDebugUnitTest --no-configuration-cache
```

| 测试类 | 覆盖 |
|---|---|
| `TotpEngineTest` | RFC 6238 附录 B 全部官方向量（SHA1/256/512）、Steam 字母表、Base32、倒计时 |
| `OtpAuthUriTest` | otpauth URI 解析、Google Authenticator 迁移 protobuf（含畸形输入） |
| `ImporterTest` | 七种来源样本、来源自动识别、失败隔离 |
| `IconNormalizerTest` | 真实图标文件名的命名陷阱（多点号、Unicode、`_light`/`_dark`、全角） |
| `MergeTest` / `CategorySyncTest` / `SyncPortableTest` | 合并与冲突、分类同步、可移植格式 |
| `SyncTargetsTest` | 多 WebDAV/S3/Google/OneDrive 目标构造、绑定键、停用集合、旧配置迁移 |
| `S3ProviderTest` | AWS SigV4 签名向量（路径/虚拟主机风格、会话令牌、删除）、地址解析与端口处理、路径编码、`objectKey` 归一化、本地 HTTP 服务端往返 |
| `ExporterTest` / `TesseraBackupTest` | 导出格式与备份往返 |
| `SkeletonTest` | 冒烟 |

## APK 体积（实测，debug 未压缩）

| 产物 | 大小 |
|---|---|
| `app-universal-debug.apk` | 123,110,286 B ≈ 117.4 MiB |
| `app-x86-debug.apk` | 103,299,424 B ≈ 98.5 MiB |
| `app-x86_64-debug.apk` | 103,087,550 B ≈ 98.3 MiB |
| `app-arm64-v8a-debug.apk` | 101,957,056 B ≈ 97.2 MiB |
| `app-armeabi-v7a-debug.apk` | 99,203,120 B ≈ 94.6 MiB |

debug 包未做代码/资源收缩，因此偏大；release 构建开启 R8（`isMinifyEnabled`）与 `shrinkResources` 后会显著减小。体积主要来自 Compose + Google API 客户端（Drive）+ 离线捆绑版 ML Kit 与 SQLCipher 的 native 库，以及 9.2 MB 的图标资源。

## 已知取舍

- 图标选择器在图标存在多套变体时统一使用默认变体（`_light`/`_dark` 仅在存在时按主题优先命中）。
- 2FAS / Aegis / Raivo 的**加密导出**目前只识别并提示需要口令，未实现离线口令解密。
- HMAC-MD5 的迁移条目降级为 SHA1（`OtpAlgorithm` 不含 MD5）。
- OneDrive 使用 `net.openid:appauth` 而非微软官方 MSAL：MSAL 8.5.0 传递依赖的 `com.microsoft.device.display:display-mask:0.3.0` 已从 Maven Central 下架，会导致依赖解析失败。
- 应用仅产出 debug / 签名 release APK，未上架任何应用商店。

## 参与贡献

欢迎 Issue 与 PR。

1. Fork 本仓库并从 `main` 切出特性分支。
2. 提交前请确保 `./gradlew :app:testDebugUnitTest --no-configuration-cache` 全绿；新增逻辑请附带单测。
3. PR 描述里说明改动动机与验证方式（编译/单测，以及是否有真机验证）。

涉及密钥、令牌、口令的改动请勿将任何真实凭据写入仓库——`local.properties`、`keystore.properties`、`*.jks` 均已在 `.gitignore` 中。

## 许可

本项目基于 [GNU General Public License v3.0](LICENSE) 发布。

```
Tessera — Android TOTP authenticator
Copyright (C) 2026 akiker233

This program is free software: you can redistribute it and/or modify
it under the terms of the GNU General Public License as published by
the Free Software Foundation, either version 3 of the License, or
(at your option) any later version.
```
