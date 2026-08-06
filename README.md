# 英语单词速记 8.0.0

同一套 Vite + TypeScript 核心输出三端版本：单文件离线 HTML、Tauri 2 Windows 应用、Capacitor 8 Android 应用。8.0.0 冻结学习算法为 `weighted-random-v1`，只重构工程、存储、同步和平台适配。

## 开发与验证

```powershell
npm install
npm run check
npm test
npm run build
```

`npm run build` 生成可直接离线打开的 `dist/index.html`，所有脚本与样式均已内联。

Windows：

```powershell
npm run tauri:build
```

安装包位于 `release/windows-installer`，便携版位于 `release/windows-portable`。凭据写入 Windows 凭据管理器。安装器构建使用项目目录 `.tauri-tools/nsis-3.11` 中的官方 NSIS 3.11。

Android（需要 JDK 21 和 Android SDK 36）：

```powershell
npm run package:android
```

侧载 APK 位于 `release/android`。最低系统版本为 Android 8（API 26），凭据由 Android Keystore 加密；构建固定使用项目目录 `.gradle-dist/gradle-8.14.3` 中的 Gradle，避免 Wrapper 重复联网下载。

## 数据与同步

- `english-word-review-v4` 是完整 v4 快照；旧 v2/v3 和辅助 v1 键继续保留。
- 首次迁移会将所有旧键原文备份到 `english-word-review-pre-v4-backup`。
- “导出完整 v4”保留检查点、事件、版本和同步信息；“导出 v7.4.5 兼容版”只投影旧版字段。
- GitHub 使用私有仓库 Contents API 和文件 SHA；WebDAV 使用 HTTPS 与 ETag。
- 两个镜像都可读取时，同步后的事件会压缩为带设备序号向量的检查点；读取失败时保留事件，避免丢失尚未观察到的离线分支。
- HTML 凭据只存在 `sessionStorage`；浏览器若因 CORS 拒绝 WebDAV，GitHub 镜像仍会独立返回结果。

详细冻结参数和升级规则见 [docs/algorithm-compatibility.md](docs/algorithm-compatibility.md)。
