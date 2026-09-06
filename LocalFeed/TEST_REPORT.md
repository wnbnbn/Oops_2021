# v0.5 测试说明

本次自动门禁：完整源文件 SHA256/严格 UTF-8、Random Feed V2、FeedSession、AlbumQuery、缓存相似扫描 smoke、真实 Android assembleDebug、APK 固定签名证书、APK SHA256、构建产物上传。

实际运行结果以对应 commit 的 GitHub Actions 日志和 artifacts 内 BUILD_REPORT.txt 为准。Scanner smoke 使用 Android/数据库测试桩，仅证明算法与缓存控制流程；真实 APK 编译使用 Android SDK。

待真机：OnePlus Ace 6 / ColorOS 16 正常上下刷、快速连续 20+ 次、不同宽高/分辨率、横竖切换后继续刷；检查黑闪/白闪/poster 闪/残帧/音画同步；相似扫描与播放并行时的响应和温度、真实素材准确率、千/五千/万条库和长期滚动内存。

保留现有播放防闪代码；尚未声明真机闪烁已修复。大库分页、内容组持久化、整理时合并元数据等后续 P2 项未在本次恢复任务中补齐。
