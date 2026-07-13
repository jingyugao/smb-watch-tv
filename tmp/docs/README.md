# SMB追剧

SMB追剧是一款面向 Android TV 的局域网电影、电视剧播放器。它可以管理 SMB 连接、把电视剧目录加入播放列表、记忆选集和播放进度，并在一集结束后自动播放下一集。

## 功能

- SMB 连接管理和真实账号认证
- WS-Discovery 与 TCP 445 局域网设备发现
- SMB 共享目录浏览
- 文件夹视频列表，不区分电影和电视剧
- 扫描多层目录，每个含直属视频的文件夹生成独立播放列表
- 最近播放与断点续播
- 电视剧详情与完整选集列表
- 自动播放下一集
- Media3 流式播放
- 电视遥控器方向键、确认键和返回键操作
- 左侧电视导航与视频覆盖式控制栏
- Android Keystore 加密保存 SMB 凭据

## 环境

- JDK 17 或更高版本
- Android SDK 34
- Docker，可选

## 本机构建

```shell
./gradlew assembleDebug
```

APK 输出到 `app/build/outputs/apk/debug/`。

## Docker 构建

```shell
docker build -t smb-watch-tv-builder .
docker run --rm -v "$PWD:/workspace" -w /workspace smb-watch-tv-builder ./gradlew assembleDebug
```

## 安装

```shell
adb install -r app/build/outputs/apk/debug/smb-watch-tv-1.0.0-debug.apk
```

应用 ID：`com.smbwatch.tv`
