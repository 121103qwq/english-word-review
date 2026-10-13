# 词包协议 v2

本文定义手表可直接读取的词包格式、兼容边界、修订规则和内容工具命令。词包只携带纯文本与已声明能力；未知的可选扩展可以保存并忽略，未知的必需能力会被拒绝，以免设备静默误解题目或复习规则。

## 兼容与能力声明

应用继续读取历史 `schemaVersion: 1` 词包和现有事件 v1。v1 的新词上限必须是 `0..10000` 的整数；历史常见值 15 和 30 保持有效。新模板的默认值 30 只是生成默认，不是应用硬上限。v1 词目仍要求至少一题 `forward` 和一题 `reverse`，题目类型缺省时按选择题处理，模式只允许 `forward`、`reverse`、`rare`。`roots` 可继续用字符串，也可用字符串数组。

新词包建议使用 schema v2。它为词包增加修订、整包/增量、明确能力、撤回标记、更多题型、扩展说明和作者复习策略。设备支持的能力如下：

| 必需能力 | 作用 |
| --- | --- |
| `plain-text` | v2 基础能力；题干、释义、例句和说明均为纯文本。 |
| `choice-v1` | 选择题及 `options` / `correctIndex`。 |
| `input-v1` | 输入答案题及 `acceptedAnswers`。 |
| `recall-v1` | 显示答案并自评的回忆题。 |
| `word-notes-v1` | 词性、变化、词块、句法框架、易错点和提取提示。 |
| `review-policy-v1` | `reviewPolicy` 或 `reviewMode`。 |
| `package-delta-v1` | 增量包。 |
| `word-withdrawal-v1` | 词项 tombstone 撤回。 |

只有包实际使用的能力才需要列入 `requiredCapabilities`。不在上表中的必需能力会被拒绝；未声明为必需的 `x-*` 可选字段会保留，但当前应用不会据此执行新行为。语音识别、发音评分和作文评分尚未实现，不能用可选字段伪装成已支持能力；需要这些功能的包必须声明新的必需能力，因而会安全失败并等待兼容应用。

## v2 完整包

顶层必需字段：

| 字段 | 规则 |
| --- | --- |
| `schemaVersion` | 整数 `2`。 |
| `packageId` | 同一组日期修订保持不变。 |
| `date` | `YYYY-MM-DD`，按 `Asia/Shanghai` 生效。未来日期可提前下载，但不会提前进入学习队列。 |
| `timezone` | 固定 `Asia/Shanghai`。 |
| `contentMode` | 固定 `ai-authored`。 |
| `catalogSha256` | 64 位十六进制 SHA-256；同一包的修订和增量必须完全一致。 |
| `newWordLimit` | `-1..10000`。`-1` 表示本包全部新词候选；`0` 表示只复习。 |
| `revision` | 从 `1` 开始的正整数；修订只在同一 `packageId` 和日期内递增。 |
| `kind` | 完整快照写 `full`。 |
| `requiredCapabilities` | 已支持能力的字符串数组，至少含 `plain-text`。 |
| `words` | 最多 500 个完整词目对象。 |
| `newWordIds`、`reviewWordIds` | 分别按作者顺序列出新词和复习词；合起来必须恰好覆盖 `words` 中全部活动词 ID，不能重复。 |
| `withdrawnWordIds` | 必需字段；新包通常为空数组。撤回 ID 不能同时是活动词或出现在新词/复习列表。 |

示例使用合成词和合成释义，可复制结构后替换内容：

```json
{
  "schemaVersion": 2,
  "packageId": "synthetic-2030-04-05",
  "date": "2030-04-05",
  "timezone": "Asia/Shanghai",
  "contentMode": "ai-authored",
  "catalogSha256": "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
  "newWordLimit": 30,
  "revision": 1,
  "kind": "full",
  "requiredCapabilities": ["plain-text", "choice-v1", "input-v1", "recall-v1", "word-notes-v1", "review-policy-v1"],
  "reviewMode": "question",
  "reviewPolicy": {
    "intervalDays": [1, 3, 7, 14],
    "wrongAfterDays": 1,
    "hardAfterDays": 2
  },
  "words": [{
    "id": "csv:0001",
    "word": "alpha",
    "rank": 1,
    "phonetic": "/ˈælfə/",
    "meaning": "示例义甲",
    "generatedBy": "AI",
    "senses": ["示例义甲"],
    "roots": ["synthetic-root"],
    "notes": {
      "partOfSpeech": ["noun"],
      "forms": ["alphas"],
      "chunks": ["alpha sample"],
      "frames": ["This is alpha."],
      "pitfalls": ["synthetic contrast"],
      "retrievalTips": ["recall the sample"],
      "sections": [
        {"title": "Extra note", "body": "Synthetic text."},
        {"title": "Examples", "items": ["Synthetic item."]}
      ]
    },
    "examples": [{"en": "Alpha is first.", "zh": "Alpha 是示例项一。"}],
    "questions": [
      {
        "id": "q-alpha-choice",
        "type": "choice",
        "mode": "recognize",
        "prompt": "Choose the synthetic meaning.",
        "options": ["示例义甲", "示例义乙"],
        "correctIndex": 0,
        "explanation": "这是合成测试答案。"
      },
      {
        "id": "q-alpha-input",
        "type": "input",
        "mode": "spell-check",
        "prompt": "Type the synthetic word.",
        "acceptedAnswers": ["alpha", "ALPHA"],
        "caseSensitive": false,
        "ignorePunctuation": false,
        "explanation": "输入匹配合成答案。"
      },
      {
        "id": "q-alpha-recall",
        "type": "recall",
        "mode": "active-recall",
        "prompt": "Recall the synthetic word.",
        "answer": "alpha",
        "explanation": "这是自评题的合成答案。"
      }
    ]
  }],
  "newWordIds": ["csv:0001"],
  "reviewWordIds": [],
  "withdrawnWordIds": []
}
```

CSV ID 继续由原 rank 派生，`csv:0001` 的 `rank` 必须是 1；词形和 rank 不能借修订重绑到另一个身份。动态词使用稳定 `dyn:` ID。题目 ID 在修订中也应保持稳定，只有题目身份改变时才换 ID。

### 题目、说明和复习参数

v2 每个词可有 1–32 题，不要求同时有 `forward` 和 `reverse`。每题必须有唯一 `id`、安全模式名 `mode`、纯文本 `prompt` 和 `explanation`。`mode` 是短标识符，不决定交互类型；应用根据 `type` 渲染题目。日常生成默认使用选择题和自评回忆题；输入题只是可选接口，不应把手表打字设成常规必需步骤。

- `choice` 需要 `choice-v1`，提供 2–4 个互不重复的 `options` 和有效的 `correctIndex`。
- `input` 需要 `input-v1`，`acceptedAnswers` 为 1–32 个答案。默认只去除首尾空白并做 Unicode NFC 规范化；`caseSensitive` 默认 `true`，`ignorePunctuation` 默认 `false`。大小写或标点只有显式关闭/开启规则时才折叠。应用默认以触控的心里/口头提取加翻面自评方式呈现；只有学习者主动切换“输入作答”才显示输入框并客观匹配答案。
- `recall` 需要 `recall-v1`，提供显示用 `answer`；作答结果由学习者自评为记得、模糊或忘记。应用把它记录成自评证据，不把“看过答案”记成独立掌握。
- `reviewMode` 可选 `question` 或 `self-recall`；缺省为 `question`。它和 `reviewPolicy` 都需要 `review-policy-v1`。
- `reviewPolicy.intervalDays` 必须是 1–32 个严格递增的正整数，最大 3650；`wrongAfterDays`、`hardAfterDays` 各为 1–3650。应用在作答事件中保存实际 `scheduleDays`，投影按该事件日期计算到期日。新题答错或自评忘记/模糊按对应天数重排；提前答对不会推进已有阶段；旧事件没有此字段时继续用内置旧间隔。
- `notes` 需要 `word-notes-v1`。六个标准字段 `partOfSpeech`、`forms`、`chunks`、`frames`、`pitfalls`、`retrievalTips` 都是字符串数组。`sections` 可新增任意标题的纯文本栏目，每项需有 `title`，并至少包含 `body` 或 `items`；正文和列表均作为纯文本显示。

自由表达可接受合理同义答案；固定搭配、词形变化、句法框架等应按题目需要明确写进可接受答案。错题在后续到期日换语境提取，不要让设备立即重复刚显示过答案的同一题。

## 增量、修订与撤回

增量只针对一个已缓存的 v2 完整包，且必须精确匹配 `packageId`、`date`、`catalogSha256` 和 `baseRevision`。`revision` 必须高于基线。增量字段与完整包相同的身份/策略字段保持一致，额外写 `kind: "delta"`、`baseRevision`、`package-delta-v1`；`words` 只放新增或更新的词目，`withdrawnWordIds` 写本次 tombstone。`newWordIds` 和 `reviewWordIds` 仍写应用后的完整活动词清单，而非仅列出增量内容。

delta 只能应用到本机已有的精确基线。当前 `index.json` 每个日期只列一个日报文件，且新安装设备没有同日基线，因此对外发布的 `current.json` 和 `daily/` 必须是已物化的 `kind: "full"` 快照。`validate-package --base ... --materialized-output ...` 会先按基线应用并校验 delta，再写出可发布的完整结果。发布工具会拒绝任何 delta 快照，避免新设备收到没有基线的包。

增量示意（ID、日期和释义均为合成值）：

```json
{
  "schemaVersion": 2,
  "packageId": "synthetic-2030-04-05",
  "date": "2030-04-05",
  "timezone": "Asia/Shanghai",
  "contentMode": "ai-authored",
  "catalogSha256": "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
  "newWordLimit": 30,
  "revision": 2,
  "kind": "delta",
  "baseRevision": 1,
  "requiredCapabilities": ["plain-text", "choice-v1", "package-delta-v1", "word-withdrawal-v1"],
  "words": [{"id": "csv:0001", "word": "alpha", "rank": 1, "phonetic": "/ˈælfə/", "meaning": "更新的合成义", "generatedBy": "AI", "senses": ["更新的合成义"], "examples": [{"en": "Alpha changed.", "zh": "合成更新。"}], "questions": [{"id": "q-alpha", "type": "choice", "mode": "recognize", "prompt": "Choose.", "options": ["更新的合成义", "其他"], "correctIndex": 0, "explanation": "合成说明。"}]}],
  "newWordIds": ["csv:0001"],
  "reviewWordIds": [],
  "withdrawnWordIds": ["csv:0002"]
}
```

同 ID 的英文词形/身份不得变化。完整修订必须保留既有 tombstone；移除旧活动词必须显式撤回，不能静默删除。撤回 ID 不得复活。旧 revision 被拒绝；同 revision、同内容幂等接受；同 revision 内容冲突拒绝。应用 delta 后形成标准 `kind: "full"` 快照并累积 tombstone。v1 包可照常读取，也可在保持 packageId、日期和词身份的前提下迁移到新的 v2 完整快照；v1 没有 revision，`revision()` 返回 0。

## 多日索引与事件

可选 `watch-words/index.json` 结构为 `{ "schemaVersion": 1, "packages": [{"path": "daily/YYYY-MM-DD.json", "sha256": "64 位十六进制"}] }`，最多列最近 14 个日报。哈希针对日报文件的原始字节，不针对重新格式化后的 JSON。应用逐包校验下载字节和包结构，只激活上海日期不晚于今天的包；索引 404 时仍可使用 `current.json`。发布工具会从实际 `daily/` 文件生成索引。

事件保持 v1。`id` 是 UUID；`deviceId + seq` 标识同一设备的事件序列。多个分片中完全相同的事件 ID 会去重；同 ID 内容不同或同设备同 seq 对应不同 ID 会作为冲突拒绝。事件可以附带 `questionId`、`packageRevision`、`sessionId`、`evidence` 和正整数 `scheduleDays`。历史事件不要求这些新字段。跨文件分片暂时没有目标的 undo 在投影中忽略，目标事件到达后再由完整事件集重算。

应用在上海自然日内按有效事件 ID 对新词数去重，把多台设备已同步的有效新词作答扣出每日剩余额度；同词被重复答题不会重复计数。离线设备之间无法预先共享当日记录，故联网同步前可能暂时各自出题。错题不立即安排同题重试。`assisted`、`self-report`、独立选择/输入和熟词跳过按不同证据记录；自评结果即使题目 `mode` 是自定义名称或 `input` 也会保存为 self-report。提示或答案展示不等于独立掌握。

## 从输入到发布

以下命令使用 D 盘默认目录，日期和截止时间请按实际计划替换。先确认手表完成同步，并从私有数据仓库拉取最新事件；不要人工编造事件或动态词 evidence。

```powershell
$Source = 'D:\Codex\wear-words\source'
$Data = 'D:\Codex\wear-words\data'
$Tool = Join-Path $Source 'wear\tools\content_pipeline.py'
$Date = '2030-04-05'
$AiInput = Join-Path $Data "watch-words\ai-input\$Date.json"
$Package = Join-Path $Data "watch-words\daily\$Date.json"

python $Tool prepare-ai-input --date $Date --cutoff-at '2030-04-04T22:30:00+08:00' --output $AiInput
```

把生成的 AI 输入交给本地内容作者，要求输出完整 v2 包；默认用选择题和自评回忆题，不要求学习者在手表上打字；只在确有必要时增加可选 input 题。准确复用候选词 ID 和词形，并复制 manifest 中动态词的 evidence。模板默认 `newWordLimit` 为 30，也可依据内容设置其他 `0..10000` 值，或设置 `-1` 学习本包全部候选。新词候选总数最多 500。没有动态词候选或到期动态复习时，不得自行发明 `dyn:` 项。

保存完整包到 `$Package`，如需设备用 `current.json`，将相同内容复制过去。校验新日期完整包：

```powershell
python $Tool validate-package $Package --catalog (Join-Path $Data 'watch-words\catalog.json') --ai-input $AiInput
```

有 `dyn:` ID 的包必须传入与包的 `packageId`、`date`、`catalogSha256` 一致的 `--ai-input`；工具核对候选/到期复习 ID、词形和 evidence。纯 CSV 历史包可不传。CSV ID、原词、rank 和 catalog hash 会按私有底库核对。要验证同日的更高 revision 或 delta，再传精确基线：

```powershell
python $Tool validate-package $Package --catalog (Join-Path $Data 'watch-words\catalog.json') --ai-input $AiInput --base (Join-Path $Data 'watch-words\daily\2030-04-05.json')
```

作者若用 delta 修改当天内容，按精确基线先验证并物化；完整输出再复制到日报和当前包：

```powershell
$Delta = Join-Path $Data "drafts\$Date-delta.json"
$Base = Join-Path $Data "watch-words\daily\$Date.json"
$Materialized = Join-Path $Data "drafts\$Date-materialized.json"
python $Tool validate-package $Delta --catalog (Join-Path $Data 'watch-words\catalog.json') --ai-input $AiInput --base $Base --materialized-output $Materialized
Copy-Item $Materialized $Package -Force
Copy-Item $Materialized (Join-Path $Data 'watch-words\current.json') -Force
```

单独验收“新模板默认限制仍是 30”时才加 `--require-generated-limit`。普通兼容校验不强制 30，以便历史包和合法的新上限通过。校验通过后再核对 `daily/$Date.json` 与 `current.json` 内容完全一致。

最后从私有数据仓库根目录发布：

```powershell
python $Tool publish --data-root $Data
```

发布前要求仓库内有普通文件 `catalog.json`，并校验 `current.json` 和全部日报的包结构、CSV ID/词形/rank 与底库的对应关系；包含 `dyn:` ID 的日期还必须有匹配的 `ai-input/YYYY-MM-DD.json` evidence manifest。工具拒绝 delta；这些路径必须放已物化的完整快照。随后生成最多 14 日的 `index.json`，验证已配置的 origin push URL、同名 upstream 分支和提交范围，并用显式 `HEAD:refs/heads/<branch>` 推送。认证使用已登录的 Git Credential Manager；不要在命令参数、日志或词包里放 Token。公开源码仓库不保存真实日报、CSV、进度、证据或签名私钥。
