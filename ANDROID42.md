# Android 4.2 USB 有线 CarPlay：公开源码版

此分支保存旧车机适配源码。目标为 Android 4.2 / API17、ARMv7；不使用根目录的现代 `mobile` Gradle 模块构建。

- **保留**：USBMUX/Lockdown/iAP2/NCM、API17 USB 接口兼容、异步停止与重连资源释放、H.264 视频修复、屏幕检测、投屏分辨率/帧率/显示方式设置及持久化、黑屏批量排查、音视频自检和本地脱敏报告导出。
- **排除**：日志上传、自动补传、在线版本检查/下载安装、NAS 部署与发布脚本、个人服务器地址、实车原始日志、运行时认证资产、APK 签名密钥及安装包。
- 无线入口关闭，仅提供 USB 连接。协议内部仍复用部分原有网络/会话代码；USB NCM/VPN 需要 INTERNET 权限。

| 入口 | 用途 |
| --- | --- |
| [构建说明](docs/android42/BUILD.md) | 工具链、命令、可选外部认证与签名输入 |
| [验证与限制](docs/android42/VALIDATION.md) | 自动测试范围与实车边界 |
| [来源与修复](docs/android42/CHANGES.md) | 固定协议快照、兼容修复和许可证 |
| [接收器源码](legacy/android42/source/adaptation/receiver/) | API17 UI、服务、USB/媒体适配与测试 |
| [固定协议源码](legacy/android42/source/shared/) | 独立目标所用旧版源码，不是根目录现代 shared 模块 |

应用 ID 为 `local.airuize.diplaylegacy42`，公开源码构建版本为 `0.2.36-api17.7-public` / 44。
默认构建不含接收器认证身份，能用于编译和兼容性验证，但不能完成 iPhone 认证。
签名和接收器认证用途不同，均须在仓库外自行保存。自己生成的新签名不能覆盖用另一把密钥签名的旧安装。

本地参数在下一次连接时读取。分辨率是手机输出的投屏画布，不能改变中控面板的物理分辨率。
电脑测试不能保证具体旧固件上的连接、声音和重复重连；请停车后验证。
