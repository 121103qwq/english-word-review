import { gzipSync } from "node:zlib";
import { mkdir, writeFile } from "node:fs/promises";
import { resolve } from "node:path";

const outputDir = resolve(import.meta.dirname, "../../src/dictionary/generated");
await mkdir(outputDir, { recursive: true });
const roots = [
  { form: "build", meaningZh: "建造；建立", meaningEn: "construct", kind: "root", position: "any" },
  { form: "count", meaningZh: "计算；数", meaningEn: "count", kind: "root", position: "any" },
];
const entries = [
  { word: "apple", translation: "n. 苹果；苹果树", phonetic: "'æpl", forms: { s: "apples" }, frequencyRank: 2000, roots: [] },
  { word: "built", translation: "adj. 建造的；身段优美的\nv. 建造（build 的过去式及过去分词）", phonetic: "bɪlt", forms: { 0: "build" }, frequencyRank: 1800, roots: [{ form: "build", meaningZh: "建造；建立", meaningEn: "construct", kind: "root", inferred: false, source: "engra" }] },
  { word: "counter", translation: "n. 柜台；计数器\nv. 反击；反驳", phonetic: "'kaʊntɚ", forms: { s: "counters" }, frequencyRank: 2200, roots: [{ form: "count", meaningZh: "计算；数", meaningEn: "count", kind: "root", inferred: false, source: "engra" }] },
  { word: "kick", translation: "v. 踢；反冲\nn. 踢；快感", phonetic: "kɪk", forms: { p: "kicked", i: "kicking" }, frequencyRank: 2400, roots: [] },
  { word: "long-term", translation: "adj. 长期的", phonetic: "", forms: {}, frequencyRank: 4000, roots: [] },
  { word: "don't", translation: "不要；不", phonetic: "dəʊnt", forms: {}, frequencyRank: 500, roots: [] },
];

const key = (value) => `${value[0] ?? "_"}${value[1] ?? "_"}`;
const deletes = (word) => new Set([word, ...Array.from(word, (_, index) => word.slice(0, index) + word.slice(index + 1))]);
const entryChunks = {};
const deleteChunks = {};
for (const entry of entries) {
  (entryChunks[key(entry.word)] ??= []).push(entry);
  for (const deleted of deletes(entry.word)) {
    (deleteChunks[key(deleted)] ??= {})[deleted] ??= [];
    deleteChunks[key(deleted)][deleted].push(entry.word);
  }
}
const encode = (value) => gzipSync(JSON.stringify(value), { level: 9, mtime: 0 }).toString("base64");
for (const chunk of Object.values(entryChunks)) chunk.sort((left, right) => left.word.localeCompare(right.word, "en"));
const source = {
  dictionaryVersion: "ecdict-bc015ed2e24a+engra-798d54beb0de-sample",
  ecdictCommit: "bc015ed2e24a7abef49fc6dbbb7fe32c1dadaf8b",
  engraCommit: "798d54beb0deae476b856719cb8d5ad33d0baab2",
  sourceHashes: {},
  entryCount: Object.values(entryChunks).reduce((total, values) => total + values.length, 0),
  entryChunks: Object.fromEntries(Object.entries(entryChunks).map(([chunk, value]) => [chunk, encode(value)])),
  deleteChunks: Object.fromEntries(Object.entries(deleteChunks).map(([chunk, value]) => [chunk, encode(value)])),
  roots: encode(roots),
};
await writeFile(outputDir + "/data.ts", `// Network-fallback seed; replace with build.mjs --refresh output.\nexport const GENERATED_DICTIONARY = ${JSON.stringify(source)} as const;\n`);
await writeFile(outputDir + "/metadata.json", `${JSON.stringify({ dictionaryVersion: source.dictionaryVersion, entryCount: entries.length, fallback: true }, null, 2)}\n`);
console.log(`已生成 ${entries.length} 个离线回退词条。`);
