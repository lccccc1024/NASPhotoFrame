# NAS Photo Frame 电子相框

连接 QNAP NAS，自动扫描图片文件夹，随机幻灯片播放。

## 支持格式

- 传统: jpg, jpeg, png, gif, webp, bmp
- 现代: heic, heif, avif (iPhone 照片)

## 构建步骤

1. **打开项目**
   - Android Studio → File → Open
   - 选择文件夹 `/mnt/d/电子相框`

2. **等待同步**
   - 首次打开会下载依赖，可能需要几分钟
   - 底部状态栏显示 "Gradle sync finished" 即完成

3. **构建 APK**
   - 菜单: Build → Build Bundle(s) / APK(s) → Build APK(s)
   - 底部 Build 窗口显示进度
   - 成功: "BUILD SUCCESSFUL"
   - APK 位置: `app/build/outputs/apk/debug/app-debug.apk`

4. **安装到平板**
   - 通过 USB 连接
   - 运行: `adb install app/build/outputs/apk/debug/app-debug.apk`
   - 或复制 APK 文件到平板手动安装

首次使用 Android Studio？快速入门：

## 打开项目

1. 启动 Android Studio
2. 点击 "Open" 或菜单 File → Open
3. 找到并选择 `/mnt/d/电子相框` 文件夹
4. 点击 "OK"

## 等待同步

- 首次打开会显示 "Gradle sync in progress..."
- 等待底部显示 "Gradle sync finished"
- 如果有错误，尝试: Tools → Android → Sync Project with Gradle Files

## 构建 APK

1. 菜单: Build → Build Bundle(s) / APK(s) → Build APK(s)
2. 等待底部 Build 窗口完成
3. 成功会显示 "BUILD SUCCESSFUL"
4. 失败贴上错误信息问我

## 常见问题

| 问题 | 解决 |
|------|------|
| SDK 未安装 | 点击 "Install missing platforms" 提示 |
| Gradle 失败 | File → Invalidate Caches → Invalidate and Restart |
| 连接超时 | 设置代理或换国内镜像 |

## 使用说明

- 每 10 秒自动切换下一张图片
- 点击屏幕退出播放
- 应用会保持屏幕常亮

## 首次使用

1. 打开应用
2. 输入 NAS 配置:
   - NAS 地址: 你的威联通 IP (如 192.168.1.100)
   - 共享文件夹: 如 Multimedia
   - 用户名/密码: 你的 NAS 账号
3. 连接成功后自动开始播放