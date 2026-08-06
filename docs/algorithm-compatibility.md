# weighted-random-v1 兼容性约束

8.0.0 的共享核心将 7.4.5 行为登记为 `weighted-random-v1`；8.1.0 继续原样使用。时间戳和复习间隔只随事件记录，不参与抽题，离线词典与手动词库也不改变抽题公式。

## 冻结参数

| 行为 | 参数 |
| --- | --- |
| 最近项屏蔽 | 最多 6 项；词库不足时保留至少一个候选 |
| 普通/强化/词根错题权重 | `min(30, wrong × (wrong + 4))` |
| 答对减权 | `min(wrongBonus, right × 3)` |
| 低答对次数补偿 | `max(0, 2 - right)` |
| 中→英答错 | `reverseReviewWeight + 16`，最高 80 |
| 中→英答对 | `reverseReviewWeight × 0.12` |
| 熟词生义 | 按义项相对位置映射为 1–5 权重 |
| 掌握 | 对应题型答对至少 2 次 |
| 通关 | 全部掌握且累计正确率至少 90% |

参数集中在 `src/core/config.ts`，公式位于 `src/core/algorithm.ts`，候选和干扰项位于 `src/core/selection.ts`。黄金测试固定这些结果；修改公式或阈值时必须新增 `algorithmVersion`，不能覆盖 `weighted-random-v1`。

## v4 合并模型

每次答题记录设备 ID、设备递增序号、混合逻辑时钟、作用域、题型、结果、算法版本和重置代次。撤销事件指向原答题事件；重置事件创建新代次。合并时按事件 ID 求并集并按 `(wallTime, logical, deviceId, eventId)` 重放，因此重复同步不会重复计数，顺序相关的 12% 减权也可重现。

压缩检查点保存设备序号向量和祖先检查点。只有配置了多个镜像且全部成功读取时才压缩；单镜像或任一读取失败时保留活动事件。SHA、ETag 或 `If-None-Match` 冲突会重新读取、合并并只重试一次。

## 阅读器保护

快照同时记录 `schemaVersion`、`appVersion`、`contentVersion`、`algorithmVersion`、`minReaderVersion` 与 `requiredFeatures`。若阅读器版本过低、算法未知或必需功能未知，界面进入只读保护：允许查看和导出完整备份，不记录答题、不重置、不同步回写。

## 研究依据

- [Karpicke & Roediger, 2008](https://doi.org/10.1126/science.1152408)
- [Bahrick et al., 1993](https://doi.org/10.1111/j.1467-9280.1993.tb00571.x)
- [Pavlik & Anderson, 2008](https://pubmed.ncbi.nlm.nih.gov/18590367/)
- [Lindsey et al., 2014](https://doi.org/10.1177/0956797613504302)
- [Webb, 2009](https://doi.org/10.1177/0033688209343854)
- [Roediger & Marsh, 2005](https://scholars.duke.edu/publication/677292)
- [Butler & Roediger, 2008](https://doi.org/10.3758/MC.36.3.604)
- [Qu et al., 2026](https://pubmed.ncbi.nlm.nih.gov/42322471/)
- [Settles & Meeder, 2016](https://aclanthology.org/P16-1174/)
