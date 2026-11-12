# 听书书库与音源接入方案（0.2.0）

## 1. 免费公开书库

当前接入 LibriVox 官方目录 API：

- 最新目录：`https://librivox.org/api/feed/audiobooks?format=json&extended=1&limit=30&sort_order=desc`
- 标题搜索：`https://librivox.org/api/feed/audiobooks?format=json&extended=1&limit=30&title={关键词}`
- 作品的 `url_rss` 提供章节 RSS，客户端解析 `item/enclosure@url` 后播放章节音频。

API 文档： https://librivox.org/api/info

LibriVox API 当前每次请求最多 500 条；本 APP 单次只取 30 条，避免不必要的目录抓取。

## 2. 本地音源

使用 Android Storage Access Framework 的 `ACTION_OPEN_DOCUMENT` / `OpenDocument`，让用户选择系统可访问的音频文件，并保存持久化 URI 读取权限。应用不申请宽泛的存储读写权限。

## 3. 网络音源

用户可输入名称和 HTTP/HTTPS 直链。客户端保存地址并交由 Media3 播放。该入口不是通用网页解析器，不会绕过登录、DRM、付费墙或平台访问控制。

## 4. 后续扩展

可在 `AudioSource` 模型上增加下载状态、封面、章节索引、播放进度与授权来源字段；商业有声书平台必须使用平台明确授权的 API/SDK。
