# SMB追剧

面向 Android TV 的轻量级 SMB 电影与电视剧播放器。无需部署媒体服务器，直接连接局域网内的 Windows、NAS 或其他 SMB 文件共享，按文件夹组织播放列表，并自动记忆上次播放的选集和进度。

## 功能

- 管理多个 SMB 连接，支持账号密码和匿名访问
- 自动发现局域网设备并浏览共享目录
- 后台递归扫描包含视频的目录
- 每个视频目录自动生成独立播放列表
- 按文件名自然排序，自动播放下一集或下一部视频
- 记忆每个播放列表的选集和播放位置
- 最近播放、播放列表、SMB 管理和手机遥控入口
- 支持字幕、音轨、倍速、快进快退和选集切换
- 自动优先选择简体中文字幕
- 画面适配、拉伸和填满模式
- 显示 SMB 速度、缓冲时长、视频格式和丢帧信息
- 针对 Android TV 遥控器方向键、确认键和返回键优化
- 手机扫描二维码后通过浏览器遥控播放和管理 SMB

## 支持的环境

- Android 5.0（API 21）及以上
- Android TV、Google TV 及兼容的电视盒子
- SMB 2/3 文件共享优先
- Windows 文件共享、NAS 和 Samba

实际音视频格式兼容性取决于电视提供的 Android 硬件解码器。4K、HDR、Dolby Vision、DTS 和 TrueHD 等格式在不同电视上的支持可能不同。

## 安装

从 [GitHub Releases](https://github.com/jingyugao/smb-watch-tv/releases) 下载 APK，通过 U 盘、ADB 或电视文件管理器安装。

首次侧载时，需要在电视系统设置中允许对应文件管理器或安装器安装未知来源应用。

## 添加 Windows SMB

在 Windows 中共享媒体目录，并为共享设置只读权限。然后在 App 的“SMB 管理”中填写：

```text
IP：192.168.0.100
共享名：media
用户名：a
密码：你的 SMB 密码
```

对应地址示例：

```text
smb://192.168.0.100/media/
```

建议仅在可信局域网内开放 SMB，禁止从公网访问 TCP 445，并为媒体共享配置只读权限。

## 电视遥控器操作

| 按键 | 播放界面操作 |
| --- | --- |
| 确认 | 显示控制栏或播放/暂停 |
| 左/右 | 快退/快进，长按连续加速 |
| 下 | 打开播放控制栏 |
| 上 | 控制栏显示时隐藏，否则打开选集 |
| 返回 | 优先关闭控制栏，再退出播放 |
| 媒体键 | 播放、暂停、上一集、下一集 |

## 播放列表规则

- 一个目录中的直接视频文件构成一个播放列表
- 视频按照文件名自然排序
- 子目录包含视频时，子目录会成为独立播放列表
- 不区分电影和电视剧
- 当前目录播放完成后自动播放下一个视频
- 同一个目录只会导入一次
- 已删除目录可以通过“清理无效项”移除

## 发布渠道

- Stable：正式版本，例如 `v1.4.0`
- Dev：测试版本，例如 `v1.4.0-beta.1`
- Main：每次推送由 GitHub Actions 生成 Debug 构建产物

Stable 和 Dev 使用同一个应用包名及签名证书，可以覆盖升级并保留数据。Dev 版本用于电视兼容性和新播放功能测试。

## 本地构建

项目提供 Docker 构建环境。先构建镜像：

```bash
docker build -t smb-watch-tv-builder .
```

之后使用统一脚本构建：

```bash
./scripts/build-apk.sh
```

脚本会自动递增内部版本号、构建 Debug APK，并默认复制到：

```text
D:\media
```

也可以指定输出目录：

```bash
APK_TARGET_DIR=/path/to/output ./scripts/build-apk.sh
```

## GitHub Release

推送测试标签：

```bash
git tag -a v1.4.0-beta.2 -m "SMB Watch TV 1.4.0 beta 2"
git push origin v1.4.0-beta.2
```

推送正式标签：

```bash
git tag -a v1.4.0 -m "SMB Watch TV 1.4.0"
git push origin v1.4.0
```

GitHub Actions 会自动构建签名 APK、生成 SHA-256 文件并创建 Release。带连字符的版本会标记为 Pre-release。

## 签名安全

- 签名私钥不保存在 Git 仓库中
- 本地通过忽略的 `.signing/` 和 `keystore.properties` 配置
- GitHub Actions 通过加密 Secrets 恢复发布密钥
- 所有后续版本必须使用相同签名证书，否则 Android 无法覆盖升级
- 请将正式 JKS 和密码保存到安全的离线备份

## 数据存储

SMB 连接、播放列表和播放进度保存在 App 私有数据目录中。

- 使用 `adb install -r` 或正常覆盖升级会保留数据
- 卸载 App 或清除应用数据会删除本地记录
- SMB 视频文件不会复制到电视本地

## 技术栈

- Java
- Android SDK 34
- AndroidX Media3 / ExoPlayer
- jcifs-ng
- ZXing
- GitHub Actions

## License

项目暂未指定开源许可证。在添加许可证前，源代码可公开查看，但不自动授予复制、修改或再分发权利。
