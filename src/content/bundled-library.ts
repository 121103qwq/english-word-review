import type { LegacyWord } from "../core/types";
import type { ContentSnapshotV1, CustomLibrary, CustomLibraryWord } from "./types";

export interface BundledLibraryEntry {
  word: string;
  meaning: string;
  root: string;
  rootMeaning: string;
  rootHint: string;
}

/** Exact active library embedded by the user's supplied v7.4.6 rebuilt HTML. */
export const V746_BUNDLED_LIBRARY: readonly BundledLibraryEntry[] = [
  { word: "programme", meaning: "节目；计划；方案；程序；编制节目；制定计划", root: "pro- / gram", rootMeaning: "向前；写、记录", rootHint: "pro-（向前）+ gram（写、记录）→把将要做的内容先写下来并排成一套安排" },
  { word: "protection", meaning: "保护；防护；保护措施", root: "pro- / tect", rootMeaning: "在前；覆盖、保护", rootHint: "pro-（在前）+ tect（覆盖、遮蔽）→像挡在前面盖住一样，让对象少受外界伤害" },
  { word: "anxiety", meaning: "焦虑；忧虑；担心；渴望", root: "anx / ang", rootMeaning: "压紧；焦虑感", rootHint: "anx / ang（压紧、使心里发紧）+ -iety（状态）→心里像被什么收紧一样，难以放松" },
  { word: "anxious", meaning: "焦虑的；担心的；渴望的", root: "anx / ang", rootMeaning: "压紧；焦虑感", rootHint: "anx / ang（压紧、使心里发紧）+ -ous（具有……状态的）→心里一直绷紧，等待某件事情发生" },
  { word: "bath", meaning: "洗澡；浴缸；浴室", root: "bath", rootMeaning: "洗浴", rootHint: "bath（洗浴）→与把身体放进水中清洁、浸泡这一动作或设施有关" },
  { word: "limit", meaning: "限制；限度；界限；限定", root: "limit", rootMeaning: "边界；界限", rootHint: "limit（边界、界限）→像先画出一条线，数量或行为不能继续越过它" },
  { word: "symbol", meaning: "符号；象征；标志", root: "sym- / bol", rootMeaning: "共同；投、放", rootHint: "sym-（共同）+ bol（投、放）→把一个记号和某种意义放在一起，用它代表另一件事物" },
  { word: "symptom", meaning: "症状；征兆", root: "sym- / ptom", rootMeaning: "共同；发生、出现", rootHint: "sym-（共同）+ ptom（发生、出现）→某种身体状态发生时一起出现、能被观察到的表现" },
  { word: "architect", meaning: "建筑师；设计师", root: "archi- / tect", rootMeaning: "首要、主要；建造", rootHint: "archi-（首要、主要）+ tect（建造）→负责在建造之前先统筹和设计的人" },
  { word: "architecture", meaning: "建筑学；建筑设计；建筑风格；体系结构", root: "archi- / tect", rootMeaning: "首要、主要；建造", rootHint: "archi-（首要、主要）+ tect（建造）+ -ure（体系、结果）→围绕建筑设计和结构形成的一整套体系" },
  { word: "commercial", meaning: "商业的；贸易的；营利的；商业广告", root: "commerc / -ial", rootMeaning: "贸易、商业；与……有关的", rootHint: "commerce（贸易、买卖）+ -ial（与……有关的）→与买卖、盈利和市场活动有关" },
  { word: "super", meaning: "极好的；超级的；特级的", root: "super-", rootMeaning: "在上；超过", rootHint: "super-（在上、超过）→程度或等级超过普通水平" },
  { word: "indicate", meaning: "表明；指出；显示；暗示", root: "in- / dic", rootMeaning: "向内、加强；说、指出", rootHint: "in-（向内、加强）+ dic（说、指出）+ -ate（动词后缀）→把信息明确“指出来”，让别人据此判断" },
  { word: "predict", meaning: "预测；预言；预报", root: "pre- / dict", rootMeaning: "预先；说", rootHint: "pre-（预先）+ dict（说）→事情发生之前先把结果“说出来”" },
  { word: "addicted", meaning: "上瘾的；入迷的；沉迷的", root: "addict / -ed", rootMeaning: "沉迷；处于……状态", rootHint: "addict（被某事牢牢吸住）+ -ed（处于……状态）→对某件事依赖很强，很难主动停止" },
  { word: "ocean", meaning: "海洋；大海", root: "ocean", rootMeaning: "海洋", rootHint: "ocean（大片海域）→比普通 sea 更广阔、连接大片陆地之间的水域" },
  { word: "fortune", meaning: "运气；财富；命运；巨款", root: "fortun", rootMeaning: "命运；机遇", rootHint: "fortun（命运、机遇）→由机遇带来的处境或结果，也可进一步联想到积累起来的大量钱财" },
  { word: "voluntary", meaning: "自愿的；志愿的；主动的", root: "volunt", rootMeaning: "意愿；自愿", rootHint: "volunt（意愿）+ -ary（具有……性质的）→行为由自己的意愿发起，而不是被强迫" },
  { word: "volunteer", meaning: "志愿者；自愿做；自愿提供", root: "volunt", rootMeaning: "意愿；自愿", rootHint: "volunt（意愿）+ -eer（从事某事的人）→因为自己愿意而主动参加某件事的人，也可表示主动去做" },
  { word: "approach", meaning: "接近；靠近；方法；途径；处理；接洽", root: "proach", rootMeaning: "靠近；接近", rootHint: "proach（靠近）→人与目标之间的距离不断缩短；也可引申为走向目标所采用的办法" },
  { word: "improve", meaning: "改善；改进；提高；变得更好", root: "improve", rootMeaning: "使变得更好", rootHint: "improve（向更好的状态发展）→原有水平发生正向变化，比之前更好" },
  { word: "approval", meaning: "赞成；批准；认可", root: "approv / -al", rootMeaning: "认可、赞成；行为或结果", rootHint: "approve（认可、赞成）+ -al（行为或结果）→对某件事表示认可后形成的态度或许可" },
  { word: "absolute", meaning: "绝对的；完全的；确实的", root: "ab- / solut", rootMeaning: "离开；松开、解除", rootHint: "ab-（离开）+ solut（松开、解除）→摆脱其他条件的限制，不再依赖外部参照" },
  { word: "absorb", meaning: "吸收；吸引；理解；使专心", root: "ab- / sorb", rootMeaning: "吸走；吸入", rootHint: "ab-（吸走）+ sorb（吸入）→把外部的东西吸进内部，也可联想到注意力被完全吸进去" },
  { word: "overcome", meaning: "克服；战胜；解决", root: "over / come", rootMeaning: "越过；来到", rootHint: "over（越过）+ come（来到）→越过挡在前面的困难，继续来到目标这一边" },
  { word: "evaluate", meaning: "评价；评估；估计", root: "e- / valu", rootMeaning: "向外；价值", rootHint: "e-（向外）+ valu（价值）+ -ate（动词后缀）→把事物的价值、水平或效果衡量出来" },
  { word: "available", meaning: "可获得的；可利用的；有空的", root: "avail / -able", rootMeaning: "有用、可利用；能够……的", rootHint: "avail（有用、可利用）+ -able（能够……的）→某样东西处在能够被取得或使用的状态" },
  { word: "valid", meaning: "有效的；有根据的；合法有效的", root: "val", rootMeaning: "强、有价值", rootHint: "val（强、有价值）+ -id（具有……性质）→理由或凭证足够“站得住”，能够继续生效" },
  { word: "insurance", meaning: "保险；保险业；保险费", root: "in- / sure", rootMeaning: "使处于；确定、安全", rootHint: "in-（使进入某状态）+ sure（确定、安全）+ -ance（制度、状态）→通过一种安排降低意外造成的不确定损失" },
  { word: "surrounding", meaning: "周围的；附近的；围绕；环境（常用复数 surroundings）", root: "surround / -ing", rootMeaning: "围绕；……的", rootHint: "surround（围在四周）+ -ing（……的）→位于某个中心四周、把它围起来的事物或环境" },
  { word: "survival", meaning: "生存；存活；幸存；幸存物", root: "sur- / viv", rootMeaning: "超过、继续；活", rootHint: "sur-（超过、继续）+ viv（活）+ -al（状态、结果）→经历危险之后生命仍然继续下去的状态" },
  { word: "district", meaning: "地区；区域；行政区", root: "dis- / strict", rootMeaning: "分开；拉紧、限制", rootHint: "dis-（分开）+ strict（拉紧、划定）→用明确边界把一个较大地方划分出来的一块区域" },
  { word: "string", meaning: "细绳；线；一串；字符串；给……装弦", root: "string", rootMeaning: "线；串", rootHint: "string（线、串）→很多东西沿着一条线连在一起，也可联想到程序里连续排列的一串字符" },
  { word: "chemical", meaning: "化学的；化学品；化学制品", root: "chem / -ical", rootMeaning: "化学；与……有关的", rootHint: "chem（化学）+ -ical（与……有关的）→与物质组成、性质和变化有关" },
  { word: "typical", meaning: "典型的；有代表性的；一贯的", root: "typ / -ical", rootMeaning: "类型、印记；具有……特征的", rootHint: "typ（类型、样式）+ -ical（具有……特征的）→很能代表某一类事物共同特征的" },
  { word: "practical", meaning: "实际的；实用的；实践的；可行的", root: "pract / -ical", rootMeaning: "做、实践；与……有关的", rootHint: "pract（做、实践）+ -ical（与……有关的）→能真正拿来做、在现实中用得上的" },
  { word: "innocent", meaning: "无辜的；清白的；天真的；无害的", root: "in- / noc", rootMeaning: "不；伤害", rootHint: "in-（不）+ noc（伤害）+ -ent（具有……性质的）→没有造成伤害或没有应承担的罪责" },
  { word: "injury", meaning: "伤害；损伤；受伤", root: "injur", rootMeaning: "伤害", rootHint: "injur（伤害）+ -y（结果、状态）→身体或其他方面受到伤害后形成的结果" },
  { word: "income", meaning: "收入；所得", root: "in / come", rootMeaning: "进入；来到", rootHint: "in（进入）+ come（来到）→钱从外部“来到”个人或家庭这一边" },
] as const;

export const V746_BUNDLED_LIBRARY_ID = V746_BUNDLED_LIBRARY.map((entry) => entry.word).join("|");

/** Exact ID of the 37-word built-in library shipped by 8.2.0. */
export const V820_BUNDLED_LIBRARY_ID = [
  "childhood", "ceremony", "routine", "endless", "scenery", "silvery", "kindness", "happiness", "darkness",
  "weakness", "racial", "official", "facial", "initial", "failure", "measure", "creature", "guilty", "steady",
  "tasty", "worthy", "muddy", "harmony", "energy", "novel", "previous", "graduate", "graduation", "gradual",
  "duty", "advocate", "microwave", "gentle", "engine", "comfort", "effort", "professor",
].join("|");

export function createBundledLegacyWord(entry: BundledLibraryEntry): LegacyWord {
  return {
    en: entry.word,
    zh: entry.meaning,
    right: 0,
    wrong: 0,
    mastery: 0,
    reverseRight: 0,
    reverseWrong: 0,
    reverseMastery: 0,
    reverseReviewWeight: 0,
    rareRight: 0,
    rareWrong: 0,
    rareMastery: 0,
  };
}

function localIsoDate(date: Date): string {
  const year = date.getFullYear();
  const month = String(date.getMonth() + 1).padStart(2, "0");
  const day = String(date.getDate()).padStart(2, "0");
  return `${year}-${month}-${day}`;
}

export function createV746ContentLibrary(now = new Date()): CustomLibrary {
  const timestamp = now.toISOString();
  const words: CustomLibraryWord[] = V746_BUNDLED_LIBRARY.map((entry) => ({
    word: entry.word,
    source: "user",
    legacyOverride: {
      meaning: entry.meaning,
      roots: [{ root: entry.root, meaning: entry.rootMeaning, note: entry.rootHint, source: "legacy" }],
    },
    legacyProgress: createBundledLegacyWord(entry),
  }));
  return {
    id: V746_BUNDLED_LIBRARY_ID,
    date: localIsoDate(now),
    words,
    createdAt: timestamp,
    modifiedAt: timestamp,
  };
}

/**
 * Replaces only the exact 8.2.0 built-in active library. User-created active
 * libraries are never selected by this migration. The previous library stays
 * in the snapshot as an archive and the operation is idempotent.
 */
export function migrateV820BundledLibrary(snapshot: ContentSnapshotV1, now = new Date()): boolean {
  if (snapshot.activeLibraryId !== V820_BUNDLED_LIBRARY_ID) return false;
  if (!snapshot.libraries.some((library) => library.id === V746_BUNDLED_LIBRARY_ID)) {
    snapshot.libraries.unshift(createV746ContentLibrary(now));
  }
  snapshot.activeLibraryId = V746_BUNDLED_LIBRARY_ID;
  return true;
}
