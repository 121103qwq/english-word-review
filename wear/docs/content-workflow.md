# WordWatch 内容与晚间 AI 接续

## 唯一底库

唯一词表是用户提供的 `上海考纲词表_按词频排序.csv`。导入工具保留所有源行及 rank、word、phonetic、meaning、zipf、原始字段和源文件 SHA-256，并将字节完全相同的 CSV、catalog 与异常清单写入私有 `data/watch-words/`。公仓只放工具和说明，不放词表摘录、日报、进度或凭据。

重复 rank/word、列数或行数异常、rank 缺口，以及 `word` 含下划线的疑似导出标识都会保留原行、rank 派生 ID 和原始值，并标为“待核对”。异常清单将 mapping 留空并注明不得猜测；任何待核对行都不会进入 AI 候选或日报校验的可用词集合。当前底库应完整导入 4237 行；已发现 7 条含下划线的疑似导出标识和 7 条源音标缺失记录，共 14 条行异常、4223 条可用词、0 项全局异常。若统计不同，检查私有 `exceptions.json`，不要手工修词。导入发现异常时程序会返回状态码 2 以提醒检查，但完整导入和异常文件仍已写出。

首次导入：

~~~powershell
python D:\Codex\wear-words\source\wear\tools\content_pipeline.py import-csv "$env:USERPROFILE\Downloads\上海考纲词表_按词频排序.csv"
~~~

## 晚间执行流程

AI 作者在本地对话中撰写内容；CLI 不调用外部 AI API。所有输入、归档包和当前包只写入私有 `data/watch-words/`。

1. 先确认手表同步成功，再从私有数据仓库拉取远端最新 events：`git -C D:\Codex\wear-words\data pull --ff-only`。确保当前分支有 upstream；使用已登录的 GCM。CLI 随后直接读取私有 `watch-words/events/*.json`（每份格式为 `{schemaVersion:1,deviceId,events:[...]}`），不依赖 AI 手工抄录或补造进度。每晚记录已确认同步与拉取完成的截止时间；生成输入时通过 `--cutoff-at` 传入带时区的 ISO-8601 时间，输入文件会保存到 `progressCutoffAt`。省略该参数时会记录生成时间，因此只有刚完成同步和拉取后立即运行才可省略。

2. 按计划学习日期生成 AI 输入；若今晚制作次日学习包，`--date` 填次日日期。到期复习排最前；新动态词只能来自真实用户添加记录及其 evidence，优先于 CSV；CSV 按原 rank 顺序。动态词与 CSV 候选合计最多 45 个，每日新词上限固定为 15。已认识的词可跳过并继续取后续候选。缺少进度只表示状态未知，不表示用户不认识所有候选。

~~~powershell
python D:\Codex\wear-words\source\wear\tools\content_pipeline.py prepare-ai-input --date 2026-10-13 --cutoff-at "2026-10-12T22:45:00+08:00"
~~~

把输出中的 `ai-input` 路径、`progressAvailable`、`progressCutoffAt` 与复习/动态/CSV 候选数量一并核对。日期示例应替换为计划学习日期（通常为次日）；截止时间必须填写刚才确认的真实同步及拉取完成时间。

3. 将生成的私有 `ai-input/YYYY-MM-DD.json` 提供给本地 AI 对话，由 AI 亲自撰写完整日报包。严格按输入 ID 复用复习词和候选词，不得改 CSV 原词或 ID；动态词只能逐字取自有 evidence 的输入材料。没有动态词材料就不写动态词，禁止编造来源、学习记录或词根。

4. 每个词写简洁中文义、可靠音标、常用搭配、至少一条自然英中例句，并有 forward 和 reverse 两题；rare 题只在确有学习价值时添加。干扰项应合理、互异，正确答案必须唯一。逐题核对 `correctIndex`，确保它指向正确答案；同一包中答案位置要有变化。用 AI 撰写说明，不把 CSV 原有释义未经核验直接当作权威，也不编造词根或词源。

5. 将成品保存为私有 `watch-words/daily/YYYY-MM-DD.json`，并将完全相同的 JSON 内容写到 `watch-words/current.json`。先校验归档包；校验要确认 schema、ID 唯一、答案索引、必需内容和所有 CSV 词的原词/rank/底库哈希。校验通过后再核对归档包与 current 内容一致。

~~~powershell
python D:\Codex\wear-words\source\wear\tools\content_pipeline.py validate-package D:\Codex\wear-words\data\watch-words\daily\2026-10-13.json
~~~

## 进度、复习和动态词

目录输入会读取 `progress.json` 及 `events/*.json`，按事件 ID 合并；相同 ID 内容冲突会失败。事件以 `at/deviceId/seq/id` 排序，并按 `progressCutoffAt` 排除未来事件；截止后发生的 undo 也不会提前撤销回答。undo 会撤销目标回答，retry 不改变复习间隔，known 排除新词和复习；之后的 primary 非 skip 回答会重新纳入学习。辅助答对单独计数，不增加客观答对数；新词首次辅助答对安排次日复习，已有复习阶段不因辅助答对推进。提前答对不推进复习阶段。到期复习优先于新候选。

动态词示例（仅当真实材料中确有对应词及证据时使用；占位内容不可作为记录）：

~~~json
{
  "schemaVersion": 1,
  "events": [],
  "dynamicWords": [
    {
      "id": "dyn:record-123",
      "word": "实际用户添加的词",
      "evidence": {"source": "用户添加记录", "at": "真实记录时间"}
    }
  ]
}
~~~

工具拒绝缺少 `word` 或 `evidence` 的动态词。若同步材料中没有用户添加记录，动态候选保持为空，不从 CSV、对话示例或推测中补造。

## 私有发布

发布命令只暂存和提交私有 Git 仓库的 `watch-words/`，要求暂存区为空、当前分支有 upstream 且无待推提交，并再次核验提交路径。认证通过已经登录的 Git Credential Manager 完成，不把 token 写入命令、日志或文件。

~~~powershell
python D:\Codex\wear-words\source\wear\tools\content_pipeline.py publish
~~~
