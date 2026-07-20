# NAS Photo Frame 电子相框

连接 QNAP NAS，自动扫描图片文件夹，幻灯片播放。

## 支持格式

- 传统: jpg, jpeg, png, gif, webp, bmp
- 现代: heic, heif, avif (iPhone 照片)

## 构建

```bash
# 以 Android Studio 打开项目根目录即可，Gradle wrapper 会自动处理依赖

# 或命令行构建 debug APK:
./gradlew assembleDebug    # macOS / Linux
gradlew.bat assembleDebug  # Windows

# APK 输出位置:
# app/build/outputs/apk/debug/app-debug.apk
```

## 使用说明

1. 首次打开输入 NAS 配置（IP、共享文件夹、用户名、密码）
2. 连接成功后自动开始幻灯片播放，每 10 秒切换一张
3. 点击屏幕 / 遥控器确认键 → 暂停 / 继续
4. 遥控器方向键 → 上一张 / 下一张
5. 长按屏幕 → 清除凭据，回到配置页
6. 屏幕常亮，适合长时间展示

## 技术栈

- Kotlin + Coroutines
- [SMBJ](https://github.com/hierynomus/smbj) — SMB 协议客户端
- [Glide](https://github.com/bumptech/flide) — 图片加载与解码
- [AVIF Coder](https://github.com/awxkee/avif-coder) — AVIF/HEIC 支持
- Android Keystore — AES-256-GCM 凭据加密