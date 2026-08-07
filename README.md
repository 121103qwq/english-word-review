# 英语单词速记 8.1.1

同一套 Vite + TypeScript 核心输出单文件离线 HTML、Tauri 2 Windows 应用和 Capacitor 8 Android 应用。当前 8.1.1 发布线修复内容投影绕过刷新保护导致的快速闪烁；原有三种学习模式、检查背诵和 `weighted-random-v1` 抽题算法保持不变。

## 检查背诵与间隔复习

- 从工具栏手动进入，可检查当前词库全量，或额外加入最多 10 个已经到期的旧词；没有到期旧词时会明确退化为仅本期。
- 每轮混合“英→中”主动回忆与“中→英”完整拼写。首答立即反馈；首错会在至少 5 道其他主队列题后同向补测一次。
- 独立的 `spaced-review-v1` 使用 1、3、7、14、30、60 天固定间隔。到期答对晋级，提前练习不连续晋级，首答错误回到 1 天阶段。
- 长期检查事件和调度随 v4 完整备份及学习进度同步；未完成会话只保存在当前设备，退出后可继续或确认放弃。
- 这是可解释的固定间隔初版策略，不宣称精确复现“艾宾浩斯遗忘曲线”，也不包含通知、FSRS、SM-2、语音识别或个体化参数训练。

## 离线词典与手动词库

- 离线词典由固定提交的 ECDICT 和 Engra 构建，包含中文释义、音标、词形、词频及可靠词根关系。
- 词典按双字符分块、gzip 压缩并内嵌到单文件 HTML；查词、距离 1 纠错和词根推测不访问网络。
- 新词库日期必填，同一天可创建多个 UUID 词库；支持 `,，.。`、换行批量分隔和 Enter 逐行输入。
- 只有完整匹配失败后才给出最多 5 个距离恰好为 1 的建议；未知词可填写中文后作为用户词条提交。
- 单词可按当前词库或全部词库修改中文、词根、音标/读音，并可绑定多个内容寻址 MP3。没有自定义 MP3 时回退到浏览器或系统 TTS。

普通构建不会联网。只有明确更新词典源时才运行：

```powershell
npm run dictionary:refresh
```

固定源版本和生成哈希位于 `src/dictionary/generated/metadata.json`，第三方许可见 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)。

## 数据与同步

- `english-word-review-v4` 继续保存学习检查点和事件；旧 v2/v3 与辅助 v1 键保持不变。
- 独立 `ContentSnapshotV1`、本地 30 份备份、outbox 和 MP3 保存在 IndexedDB；完整导出包含学习快照、内容快照和音频清单，v7.4.5 兼容导出保持不变。
- GitHub 默认使用私有数据仓库 `121103qwq/english-word-review-data`；还可配置任意数量的 HTTPS WebDAV 镜像。公开代码仓库不保存个人词库、进度、MP3 或密钥。
- 启动与每次内容修改前比较所有镜像的混合逻辑时钟，严格采用最新完整内容快照；学习进度仍按 v4 事件并集合并。
- 每次修改先备份旧内容，再顺序写入当前快照；本地及每个可达镜像只保留最新 30 份。仅当当前快照、保留备份和待上传队列都不引用时才清理 MP3。
- 离线编辑进入 outbox，并在 15 秒、1 分钟、5 分钟及下次启动/修改时重试。GitHub 网络超时只显示“暂不可用，已保存本地并排队”。
- HTML 使用 PBKDF2-SHA256 + AES-256-GCM 加密凭据并要求每次重新打开输入主密码；Windows 使用凭据管理器，Android 使用 Keystore。

## 开发与验证

```powershell
npm install
npm run check
npm test
npm run package:html
```

单文件 HTML 输出到 `release/html`。

Windows：

```powershell
npm run tauri:build
```

安装版和便携版分别输出到 `release/windows-installer` 与 `release/windows-portable`。

Android（JDK 21、Android SDK 36，最低 Android 8 / API 26）：

```powershell
npm run package:android
```

侧载 APK 输出到 `release/android`。

算法冻结参数、版本升级规则与文献依据见 [docs/algorithm-compatibility.md](docs/algorithm-compatibility.md)。
