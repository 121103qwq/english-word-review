# 词序 · 圆屏手表版 v1

独立的原生 Android / Wear OS 应用（Android 8.0 / API 26 起），在圆屏上背词、点选题和翻面复习。与同仓库旧 HTML 应用并存；v1 不改旧应用的数据格式。

## 内容与进度

- 用户指定 CSV 是唯一底库，保存原 rank、原始字段和校验值。不会引入额外词典。异常导出标识保留并排除出题，等明确校对后再使用。
- AI 离线准备释义、例句、搭配及题目；手表不调用 AI API。每天默认 15 个新词，到期复习优先，熟词略过后从预备候选递补。动态新增词须有用户输入或学习材料的明确依据。
- 客观答题、看过讲解后的作答、回忆自评和熟词略过分别记录。做过或略过不等于已经掌握。
- 复习按上海自然日和 1/3/7/14/30/60 天间隔安排。错词可以在隔开至少五题后再试一次，重试不重复推进间隔。
- 事件与答题页状态一起写入本机原子文件；关闭后可继续。同步以不可变事件 ID 合并、去重，并在 GitHub 文件冲突时重读重试。
- 自动同步限 Wi-Fi/以太网，在进入应用、结束一轮及系统允许的约 6 小时后台任务中尝试。后台时机由系统决定；立即同步可使用移动网络。

## 安装及 GitHub Token

安装个人签名 APK 后即可使用内置首批学习包。开发版包名为 `cn.wordwatch.app.dev`，正式版为 `cn.wordwatch.app`。更新请保留原签名、直接覆盖安装。

在 GitHub 设置创建 **fine-grained personal access token**：只选择自己的私有学习数据仓库，Repository permissions → **Contents: Read and write**。有效期按自己的使用习惯设置，到期后换新。Token 不进入源码、APK 或学习数据。

应用设置填写账号、仓库、分支与 Token，点“保存配置”。Token 使用 Android Keystore 加密保存，设置页禁止屏幕截图；留空保存会保留已有 Token。长 Token 推荐从电脑输入：

```powershell
python .\configure-watch.py --adb 'D:\Android\platform-tools\adb.exe' --serial '手表的ADB地址或序列号'
```

先在手表开发者选项开启 ADB，并完成设备授权/无线配对；工具只操作指定的已连接设备。Token 在终端中隐藏输入，随后通过 ADB 标准输入传给已聚焦的设置字段，不写入参数或日志。工具会保存配置并触发同步。电脑应当是可信设备。

数据目录固定为私有仓库中的 `watch-words/current.json` 与 `watch-words/events/<device>-YYYY-MM.json`。首次只有 APK 和 Token 还不够：数据仓库必须先有经过验证的 current.json。晚间 AI 工作流见 [交接文档](docs/content-workflow.md)。手表尚未上传的记录不会出现在 AI 端。

## 编译

需要 JDK 17、Android SDK Platform 35、Build Tools 35 和 Gradle 8.9。Gradle/SDK 缓存可以放 D 盘。项目不需要原网页的 Node/Tauri/Capacitor 工具链。

```powershell
$env:JAVA_HOME='JDK17目录'
$env:ANDROID_HOME='Android SDK目录'
$env:GRADLE_USER_HOME='D:\CodexCache\gradle'
# 可选：构建时注入私有首包；默认不包含个人学习内容
$env:WORDWATCH_SEED_FILE='D:\private-data\watch-words\current.json'
.\gradlew.bat --no-daemon :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
```

正式签名另设 `WORDWATCH_KEYSTORE` 与 `WORDWATCH_SIGN_PASS`，密钥别名为 `wordwatch`，执行 `:app:assembleRelease`。签名文件和密码由个人独立保管，不提交 Git。未设置私钥时不生成可安装的已签名正式包。

`app/src/androidTest` 是仅限模拟器的原生端到端测试，测试 APK 不包含在正式包中。它覆盖圆屏配置、点选/撤销/提示后答对/熟词递补/到期回忆、Keystore，以及可选隔离目录中的真实 GitHub 上传与去重。运行需至少 21 个新词候选的 AI 首包和干净的模拟器学习状态。单元测试使用合成样例，不包含个人 CSV。

## 边界

v1 没有语音、拼写键盘、手机伴随 App、额外词库或自动定时生成任务。晚间 AI 对话是次日内容准备入口。跨设备实时推送、Google Play 上架和旧 HTML 进度迁移不属于此版；真实手表显示与无线网络还需要安装后的验收。
