# 一本书（MangaReader）

一款 Android 漫画阅读应用，支持本地文件、SAF 目录与多 WebDAV 源的漫画管理、自动抽取封面、分卷书架，以及阅读时的手势翻页与缩放。

> 应用显示名「一本书」，包名 `com.example.mangareader`。

## 功能特性

- **多来源书架**：本地文件、系统存储访问框架（SAF）目录、多个 WebDAV 账户（可自定义名称、按源单独成架）。
- **自动封面**：扫描到漫画即自动设置书架封面；每一卷用其第一页作为分卷封面（支持 zip / cbz / pdf / mobi 等）。
- **阅读体验**：左右/上下翻页、音量键翻页、点击分区翻页（中间=设置，左右=换页）、双击放大 200%、长按放大 150%。
- **启动图标**：自适应图标，绿色书形封面 + 白色「一本」二字。

## 技术栈

- Kotlin + Jetpack Compose
- Android Gradle Plugin 8.x，compileSdk / targetSdk 35，minSdk 26
- Room（本地数据库）、DataStore（偏好）、AndroidX Security（凭据加密）
- Coil + Telephoto（图片加载与手势缩放）
- Sardine-Android + OkHttp（WebDAV / 网络）
- Apache Commons Compress / Tukaani XZ / Junrar（压缩包与归档格式）

## 构建

### 环境要求

- JDK 17
- Android SDK（platform & build-tools 35.0.0）
- Gradle（项目自带 `gradlew`，无需单独安装）

### 步骤

1. 克隆仓库后，复制 `local.properties.example` 为 `local.properties`，填入本机 SDK 路径：

   ```properties
   sdk.dir=C:\\Users\\你的用户名\\Android\\Sdk
   ```

2. （可选）若要构建可正式发布的 release 包，在 `local.properties` 中补充签名信息：

   ```properties
   RELEASE_STORE_FILE=release-keystore.jks
   RELEASE_STORE_PASSWORD=你的密码
   RELEASE_KEY_ALIAS=mangareader
   RELEASE_KEY_PASSWORD=你的密码
   ```

   未配置签名时，`assembleRelease` 会回退到 debug 签名，仍可构建但不适用于发布。

3. 构建 release APK：

   ```bash
   ./gradlew assembleRelease
   ```

   产物位于 `app/build/outputs/apk/release/`。

## 版本

版本号在 `app/build.gradle.kts` 的 `defaultConfig` 中通过 `appVersionName` 定义，
`versionCode` 由其推导（`1.0` → `10`，`1.1` → `11`，依此类推）。

## 许可证

本项目以 MIT 许可证发布，详见 [LICENSE](LICENSE)。
