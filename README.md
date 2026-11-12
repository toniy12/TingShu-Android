# 听书 Android APP（0.3.0）

原生 Android / Kotlin / Jetpack Compose 听书应用，采用深色界面，当前优先实现免费公开书库、本地音频和音源导入。

## 本版已加入的功能

- **免费公开书库**：接入 LibriVox 官方目录 API，可加载最新作品并按标题搜索；点击作品后读取其 RSS 章节列表，选择章节后通过 Media3 在线播放。
- **本地音频导入**：通过 Android 系统文件选择器导入设备可访问的音频文件；使用持久化 URI 权限，避免应用重启后失去访问权限。
- **网络音源导入**：可粘贴直接音频 URL（HTTP/HTTPS），添加后保存到书架并尝试播放。
- **书架管理**：保存本地文件、网络音源和已选公开书库章节；支持再次播放、删除。
- **后台媒体服务基础**：使用 AndroidX Media3 MediaSessionService。
- **本地保存**：当前书架数据保存在 SharedPreferences。

## 公开书库说明

公开目录使用 LibriVox API：`https://librivox.org/api/feed/audiobooks`，音频章节通过作品 RSS feed 的 enclosure 地址读取。LibriVox 说明其录音属于公有领域，并提供 API 给第三方应用使用；但作品版权状态可能因国家/地区而异，使用者应核对当地规则。API 请求请保持适度频率。

官方资料：
- https://librivox.org/api/info
- https://librivox.org/2026/09/16/librivox-api-update/
- https://librivox.org/pages/help/

## 网络音源导入限制

请提供直接可访问的音频流地址，例如 MP3、M4A、AAC、WAV 或兼容的 HLS 流。普通网页地址、需要登录的会员链接、DRM 加密内容或防盗链资源通常不能直接播放。请仅导入自己拥有或获准使用的音源。

## 0.3.0 新补齐的功能

- **播放进度记忆**：每个音频按地址保存进度，再次播放自动续听（播完则清零）。
- **章节连播**：从公开书库选章节时整本章节作为播放队列，自动播放下一章；支持上一章/下一章。
- **进度条与拖动跳转**、**倍速**（0.75x / 1x / 1.25x / 1.5x / 2x）。
- **定时关闭**：15 / 30 / 45 / 60 / 90 分钟。
- **播放历史**（最近 30 条，可清空）。
- **通知栏/锁屏/耳机控制**：Media3 会话 + 通知权限（Android 13+ 运行时申请），后退 15 秒 / 前进 30 秒。
- 启动图标、播放错误提示、http 链接自动升级为 https、书籍简介去除 HTML 标签。

## 仍未完成

- 批量下载与离线管理（“下载管理”入口目前仅提示）。
- Room 数据库与封面图片。
- 定时关闭在 App 被彻底关闭（进程被杀）后不再生效。
- 未接入任何商业平台的会员/付费音频 API。

## Windows 11 编译

1. 安装 Android Studio 稳定版、JDK 17。
2. 在 Android Studio 的 SDK Manager 安装 Android SDK Platform 35 和 Build Tools 35.x。
3. 解压 ZIP，在 Android Studio 中打开 `TingShu` 项目根目录。
4. 等待 Gradle 同步并联网下载 Android Gradle Plugin、Kotlin、Compose 和 Media3 依赖。
5. 选择 **Build > Build Bundle(s) / APK(s) > Build APK(s)**。
6. Debug APK 位置：`app/build/outputs/apk/debug/app-debug.apk`。

> 本开发环境没有 Android SDK/Gradle 安装，因此此 ZIP 是已更新的源码工程，尚未在此环境完成 APK 编译和真机验证。首次编译请在 Windows Android Studio 中完成。

## 命令行编译（已内置 Gradle Wrapper）

仓库已包含 `gradlew` / `gradlew.bat` 和 `gradle/wrapper/gradle-wrapper.jar`，无需额外安装 Gradle：

```bash
export ANDROID_HOME=/path/to/Android/Sdk   # Windows: set ANDROID_HOME=...
./gradlew assembleDebug
```

产物：`app/build/outputs/apk/debug/app-debug.apk`。首次运行会下载 Gradle 8.11.1 与依赖，需要 JDK 17。

## 项目信息

- Application ID：`com.example.tingshu`
- Version：`0.3.0`（versionCode 3）
- Minimum Android：8.0（API 26）
- Compile / Target SDK：35
- JDK：17

## 通过 GitHub Actions 云端编译 APK

推送到 GitHub 后，在仓库 **Actions > Build APK** 中等待完成（约 5–10 分钟），在运行结果底部 Artifacts 下载 `TingShu-debug-apk`。也可点击 **Run workflow** 手动触发。
