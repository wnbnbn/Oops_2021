# LocalFeed v0.5.1

Android 本地视频随机 Feed + 图片/视频相册。普通目录通过 SAF 授权，`.nomedia` 避免常规图库索引；不提供加密。

## 构建

JDK 17、Gradle 9.6.0、Android SDK 36 / build-tools 36.0.0。
AGP 9.4.0 自带 Kotlin，Media3 1.11.0。依赖版本沿用已验证的 GitHub v0.5 分支。

在本目录执行 `gradle :app:assembleDebug --no-daemon`。安装 kotlinc 后可执行 `bash tools/run-smoke-tests.sh`。
GitHub Actions 的 `LocalFeed v0.5 Compile` 直接构建本目录，已停止源码分片还原。
根目录的 `localfeed_ci/v05_source_sha256.json` 校验每个源文件内容，编译前须通过。

CI 使用既有固定测试 key 重签并检查 APK 实际证书 SHA256：
`2F:CF:7E:D9:C4:82:3A:A9:58:9C:53:10:2B:55:9D:E2:74:B7:AB:84:F6:36:DE:D7:99:57:AA:88:74:6A:B7:A6`。
这把 key 历史上公开，仅用于测试。未改应用包名，versionCode 6 / versionName 0.5.1。

## 本次恢复

以上传的完整 v0.4 ZIP 为基线，逐文件核对 GitHub v0.4 恢复结果，仅构建文件一处空行差异。
应用 GitHub `84a90cb5611e9a0232e44f84202a09908b6adfa2` 的全部 v0.5 补丁，补丁原文 SHA256：
`26c7ed83f4c70b44e0a916f4948f8c51ddd8316bd86104dea8c5d781ec73a99a`。
重建损坏的 SimilarVideoScanner，完整源码直接进 Git，不再使用损坏的 Scanner 压缩分片。

## 当前能力与范围

- Feed 仅视频；点赞/收藏保持轻加权，避免相邻同视频/同内容组。
- 播放恢复版取消 poster/首帧遮盖，页面选中立即切换单播放器；TextureView 避免页面切换时重建独立 Surface。
- Feed 无底部导航与文件名遮挡；右侧相册按钮和系统返回键都会定位到相册中的当前视频。
- 横屏有独立返回按钮；2× 锁定支持手势撤销和点击取消。
- 相似扫描：7 个时间点、全图/裁边/镜像 dHash、时长预筛、统一时间偏移比较、高度相似聚组、疑似对人工确认。
- 增量缓存按 id/大小/修改时间/算法版本有效；七帧缺失会计为失败；平坦帧不用于认定相似。
- 高度相似组提供保留建议；用户排除保存在 SQLite 中，传递聚组也遵守排除。
- 单视频查找、全库相似扫描、完整路径展示继承 GitHub v0.5 UI。

## 已知边界

- 新播放链路仍需 OnePlus Ace 6 / ColorOS 16 真机验收，编译成功不能证明闪屏已经完全消失。
- 相似识别为启发式，码率/镜像/轻黑边等真实素材的准确率仍需样本验证；不保证任意水印、裁剪、片头变化。
- 相似组仍为扫描会话状态；重启后需重新扫描（可复用指纹缓存）。组内处理/批量删除元数据合并与持久化内容组尚未完成。
- 完全相同文件仍交给原完全重复扫描；相似扫描跳过已知有效完全相同 Hash 对。
- Gallery 数据库分页、万级真机压测、持续 500 条播放与 30 分钟/1 小时温度耗电测试尚未完成。同长度素材的候选比较最坏仍为平方级。
- tests 的 Android 桩仅在独立 JVM smoke 命令中使用，不进入 App；不模拟真实解码器、SQLite 或 Surface。
- 恢复旧数据库后，扫描会跳过系统已撤销权限的 SAF 目录并保留旧媒体记录；重新选择原目录后才恢复扫描。
