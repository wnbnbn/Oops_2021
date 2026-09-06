# LocalFeed v0.6.0

Android 本地视频随机 Feed + 图片/视频相册。普通目录通过 SAF 授权，`.nomedia` 避免常规图库索引；不提供加密。

## 构建

JDK 17、Gradle 9.6.0、Android SDK 36 / build-tools 36.0.0。
AGP 9.4.0 自带 Kotlin，Media3 1.11.0。依赖版本沿用已验证的 GitHub v0.5 分支。

在本目录执行 `gradle :app:assembleDebug --no-daemon`。安装 kotlinc 后可执行 `bash tools/run-smoke-tests.sh`。
GitHub Actions 的 `LocalFeed v0.6 Compile` 直接构建本目录，已停止源码分片还原。
根目录的 `localfeed_ci/v05_source_sha256.json` 校验每个源文件内容，编译前须通过。

CI 使用既有固定测试 key 重签并检查 APK 实际证书 SHA256：
`2F:CF:7E:D9:C4:82:3A:A9:58:9C:53:10:2B:55:9D:E2:74:B7:AB:84:F6:36:DE:D7:99:57:AA:88:74:6A:B7:A6`。
这把 key 历史上公开，仅用于测试。未改应用包名，versionCode 7 / versionName 0.6.0。

## 本次恢复

以上传的完整 v0.4 ZIP 为基线，逐文件核对 GitHub v0.4 恢复结果，仅构建文件一处空行差异。
应用 GitHub `84a90cb5611e9a0232e44f84202a09908b6adfa2` 的全部 v0.5 补丁，补丁原文 SHA256：
`26c7ed83f4c70b44e0a916f4948f8c51ddd8316bd86104dea8c5d781ec73a99a`。
重建损坏的 SimilarVideoScanner，完整源码直接进 Git，不再使用损坏的 Scanner 压缩分片。

## 当前能力与范围

- Feed 仅视频；默认完全均匀随机，只阻止同一视频紧邻重复。点赞、收藏、未看过可以分别开启轻度倾向。
- 相邻页在拖动阶段显示缓存首帧，TextureView 解码首帧出现后再撤掉底图。
- Feed 无底部导航、文件名和相册按钮；系统返回会定位并高亮相册中的当前视频。
- 横屏只保留返回和进度；2× 锁定支持再次长按下滑取消。
- 支持全局铺满/完整显示和单视频覆盖设置，记录每个视频的播放位置。
- 完全重复文件按组选择保留版本，并批量永久清理；模糊相似扫描已从产品入口移除。
- 图片支持单图缩放和纵向连续阅读。
- 首次扫描支持千级媒体库；后续扫描只为新增、变更或缺失信息的项目解析元数据。
- 从其他应用返回时自动检查已授权目录，显示新增、更新、失败数量，并保留最近导入记录。

## 已知边界

- 新播放链路仍需 OnePlus Ace 6 / ColorOS 16 真机验收，编译成功不能证明闪屏已经完全消失。
- Android SAF 没有稳定的跨应用实时文件通知；自动导入检查在 LocalFeed 从后台回到前台时运行，手动“扫描”仍可随时触发。
- Gallery 数据库分页、万级真机压测、持续 500 条播放与 30 分钟/1 小时温度耗电测试尚未完成。
- tests 的 Android 桩仅在独立 JVM smoke 命令中使用，不进入 App；不模拟真实解码器、SQLite 或 Surface。
- 恢复旧数据库后，扫描会跳过系统已撤销权限的 SAF 目录并保留旧媒体记录；重新选择原目录后才恢复扫描。
