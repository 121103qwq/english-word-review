import { createHash } from "node:crypto";
import { spawn } from "node:child_process";
import { appendFile, mkdir, readFile, rename, rm, stat, writeFile } from "node:fs/promises";
import { dirname, join, resolve } from "node:path";
import { gzipSync } from "node:zlib";

const ECDICT_COMMIT = "bc015ed2e24a7abef49fc6dbbb7fe32c1dadaf8b";
const ENGRA_COMMIT = "798d54beb0deae476b856719cb8d5ad33d0baab2";
const projectRoot = resolve(import.meta.dirname, "../..");
const cacheDir = join(import.meta.dirname, ".cache");
const outputDir = join(projectRoot, "src/dictionary/generated");
const refresh = process.argv.includes("--refresh");
const rootGlossZh = JSON.parse(await readFile(join(projectRoot, "src/dictionary/root-gloss-zh.json"), "utf8"));

const sources = [
  {
    key: "ecdict",
    file: "ecdict.csv",
    rawUrl: `https://raw.githubusercontent.com/skywind3000/ECDICT/${ECDICT_COMMIT}/ecdict.csv`,
    mirrorUrl: `https://ghproxy.net/https://raw.githubusercontent.com/skywind3000/ECDICT/${ECDICT_COMMIT}/ecdict.csv`,
    size: 65_933_428,
    blobSha: "c4ade63ea08cf39d9c3475e96929036d64d94c94",
    segmented: true,
  },
  {
    key: "ecdictRoots",
    file: "wordroot.txt",
    rawUrl: `https://raw.githubusercontent.com/skywind3000/ECDICT/${ECDICT_COMMIT}/wordroot.txt`,
    mirrorUrl: `https://cdn.jsdelivr.net/gh/skywind3000/ECDICT@${ECDICT_COMMIT}/wordroot.txt`,
    size: 370_702,
    blobSha: "ff10bd2dfc83cf5f0d9527662291307c796b5fc7",
  },
  {
    key: "engra",
    file: "engra-words.csv",
    rawUrl: `https://raw.githubusercontent.com/eslsoft/engra/${ENGRA_COMMIT}/dict/words.csv`,
    mirrorUrl: `https://cdn.jsdelivr.net/gh/eslsoft/engra@${ENGRA_COMMIT}/dict/words.csv`,
    size: 13_535_669,
    blobSha: "510c455750d310a685339a3f528d76786f88fa77",
  },
];

await mkdir(cacheDir, { recursive: true });
await mkdir(outputDir, { recursive: true });

function gitBlobSha(content) {
  return createHash("sha1").update(`blob ${content.length}\0`).update(content).digest("hex");
}

async function validateSource(path, source) {
  const content = await readFile(path);
  const actualSha = gitBlobSha(content);
  if (content.length !== source.size || actualSha !== source.blobSha) {
    throw new Error(`${source.key} 校验失败：期望 ${source.size}/${source.blobSha}，实际 ${content.length}/${actualSha}`);
  }
}

function run(command, args, options = {}) {
  return new Promise((resolvePromise, reject) => {
    const child = spawn(command, args, { stdio: "inherit", ...options });
    child.once("error", reject);
    child.once("exit", (code) => code === 0 ? resolvePromise() : reject(new Error(`${command} 退出码 ${code}`)));
  });
}

async function downloadSegmented(source, destination) {
  const partSize = 1024 * 1024;
  const parts = [];
  for (let start = 0, index = 0; start < source.size; start += partSize, index += 1) {
    const end = Math.min(source.size - 1, start + partSize - 1);
    const path = `${destination}.part-1m-${String(index).padStart(2, "0")}`;
    parts.push({ start, end, path });
  }
  const downloadPart = async ({ start, end, path }) => {
    const expected = end - start + 1;
    const headersPath = `${path}.headers`;
    const validPart = async () => {
      if ((await stat(path)).size !== expected) return false;
      const headers = await readFile(headersPath, "utf8");
      const escapedRange = `bytes ${start}-${end}/${source.size}`.replace(/[.*+?^${}()|[\]\\]/gu, "\\$&");
      return /(?:^|\n)HTTP\/\S+ 206\b/iu.test(headers) && new RegExp(`content-range:\\s*${escapedRange}`, "iu").test(headers);
    };
    try {
      if (await validPart()) return;
    } catch { /* download below */ }
    let lastError;
    for (let attempt = 1; attempt <= 8; attempt += 1) {
      await rm(path, { force: true });
      await rm(headersPath, { force: true });
      try {
        await run("curl.exe", [
          "--location", "--fail", "--retry", "5", "--retry-all-errors", "--remove-on-error",
          "--silent", "--show-error", "--range", `${start}-${end}`, "--dump-header", headersPath,
          "--output", path, source.mirrorUrl,
        ], { stdio: ["ignore", "ignore", "inherit"] });
        if (!(await validPart())) throw new Error(`分段校验错误，期望 ${start}-${end}/${source.size} 与 ${expected} 字节`);
        return;
      } catch (error) {
        lastError = error;
        process.stderr.write(`${source.key} 分段 ${start}-${end} 第 ${attempt} 次失败，准备重试。\n`);
      }
    }
    throw lastError;
  };
  let nextPart = 0;
  const workers = Array.from({ length: Math.min(8, parts.length) }, async () => {
    for (;;) {
      const index = nextPart;
      nextPart += 1;
      if (index >= parts.length) return;
      await downloadPart(parts[index]);
    }
  });
  await Promise.all(workers);
  const temporary = `${destination}.download`;
  await writeFile(temporary, Buffer.alloc(0));
  for (const part of parts) await appendFile(temporary, await readFile(part.path));
  await validateSource(temporary, source);
  await rename(temporary, destination);
  await Promise.all(parts.flatMap(({ path }) => [rm(path, { force: true }), rm(`${path}.headers`, { force: true })]));
}

async function download(source, destination) {
  await mkdir(dirname(destination), { recursive: true });
  const temporary = `${destination}.${process.pid}.download`;
  await rm(temporary, { force: true });
  try {
    await run("curl.exe", [
      "--location", "--fail", "--connect-timeout", "20", "--max-time", "90", "--remove-on-error",
      "--silent", "--show-error", "--output", temporary, source.rawUrl,
    ]);
    await validateSource(temporary, source);
    await rename(temporary, destination);
    return;
  } catch {
    await rm(temporary, { force: true });
    process.stderr.write(`${source.key} 的 GitHub Raw 下载不可用，切换固定提交镜像。\n`);
  }
  if (source.segmented) {
    await downloadSegmented(source, destination);
    return;
  }
  await run("curl.exe", [
    "--location", "--fail", "--retry", "8", "--retry-all-errors", "--remove-on-error",
    "--silent", "--show-error", "--output", temporary, source.mirrorUrl,
  ]);
  try {
    await validateSource(temporary, source);
    await rename(temporary, destination);
  } finally {
    await rm(temporary, { force: true });
  }
}

for (const source of sources) {
  const target = join(cacheDir, source.file);
  if (refresh) {
    process.stdout.write(`刷新 ${source.key} (${source.rawUrl})\n`);
    await download(source, target);
  } else {
    try {
      await validateSource(target, source);
    } catch {
      throw new Error(`缺少 ${target}。只有显式刷新才联网，请运行: node scripts/dictionary/build.mjs --refresh`);
    }
  }
}

function parseCsvLine(line) {
  const fields = [];
  let value = "";
  let quoted = false;
  for (let i = 0; i < line.length; i += 1) {
    const char = line[i];
    if (char === '"') {
      if (quoted && line[i + 1] === '"') {
        value += '"';
        i += 1;
      } else {
        quoted = !quoted;
      }
    } else if (char === "," && !quoted) {
      fields.push(value);
      value = "";
    } else {
      value += char;
    }
  }
  fields.push(value);
  return fields;
}

async function readCsv(path, onRow) {
  const content = (await readFile(path, "utf8")).replace(/^\uFEFF/, "");
  let headers;
  let record = [];
  let value = "";
  let quoted = false;
  const finishRecord = () => {
    record.push(value);
    value = "";
    if (!headers) headers = record;
    else if (record.some(Boolean)) {
      onRow(Object.fromEntries(headers.map((header, index) => [header, record[index] ?? ""])));
    }
    record = [];
  };
  for (let index = 0; index < content.length; index += 1) {
    const char = content[index];
    if (char === '"') {
      if (quoted && content[index + 1] === '"') {
        value += '"';
        index += 1;
      } else {
        quoted = !quoted;
      }
    } else if (char === "," && !quoted) {
      record.push(value);
      value = "";
    } else if (char === "\n" && !quoted) {
      finishRecord();
    } else if (char !== "\r" || quoted) {
      value += char;
    }
  }
  if (value || record.length) finishRecord();
}

function normalizeWord(value) {
  return value.trim().replaceAll("’", "'").toLocaleLowerCase("en-US");
}

const VALID_WORD = /^[a-z]+(?:['-][a-z]+)*$/;
const engraRoots = new Map();
await readCsv(join(cacheDir, "engra-words.csv"), (row) => {
  const word = normalizeWord(row.name ?? "");
  const roots = (row.roots ?? "").trim();
  if (!word || !roots) return;
  engraRoots.set(word, roots);
  for (const form of Object.values(parseForms(row.exchange ?? ""))) {
    for (const inflection of form.split(/[,;\s]+/u).map(normalizeWord).filter(Boolean)) {
      if (VALID_WORD.test(inflection) && !engraRoots.has(inflection)) engraRoots.set(inflection, roots);
    }
  }
});

const rootSource = JSON.parse(await readFile(join(cacheDir, "wordroot.txt"), "utf8"));

function normalizeRoot(value) {
  return value
    .toLocaleLowerCase("en-US")
    .replace(/-\d+$/u, "")
    .replace(/^[-\s]+|[-\s]+$/gu, "")
    .replace(/\([^)]*\)/gu, "")
    .replace(/[^a-z]/gu, "");
}

function rootParts(value) {
  return [...new Set(value.split(/[,;/=\s]+/u).map(normalizeRoot).filter((root) => root.length >= 2))];
}

function parseForms(exchange) {
  const forms = {};
  for (const item of exchange.split("/")) {
    const separator = item.indexOf(":");
    if (separator < 1) continue;
    const kind = item.slice(0, separator);
    const value = item.slice(separator + 1).trim();
    if (value) forms[kind] = value;
  }
  return forms;
}

function rank(row) {
  const values = [Number(row.bnc), Number(row.frq)].filter((value) => Number.isFinite(value) && value > 0);
  return values.length ? Math.min(...values) : 9_999_999;
}

const entries = new Map();
await readCsv(join(cacheDir, "ecdict.csv"), (row) => {
  const word = normalizeWord(row.word ?? "");
  const translation = (row.translation ?? "").replaceAll("\\n", "\n").trim();
  if (!VALID_WORD.test(word) || !translation) return;
  const candidate = {
    word,
    translation,
    phonetic: (row.phonetic ?? "").trim(),
    forms: parseForms(row.exchange ?? ""),
    frequencyRank: rank(row),
    roots: rootParts(engraRoots.get(word) ?? ""),
  };
  const existing = entries.get(word);
  if (!existing || candidate.translation.length > existing.translation.length) entries.set(word, candidate);
});

function chineseRootMeaning(meaningEn) {
  const meaningZh = rootGlossZh[meaningEn];
  if (typeof meaningZh !== "string" || !/[\u3400-\u9fff]/u.test(meaningZh)) {
    throw new Error(`词根构词义缺失：${JSON.stringify(meaningEn)}`);
  }
  return meaningZh.trim();
}

const rootRecords = new Map();
for (const [rawRoot, metadata] of Object.entries(rootSource)) {
  const variants = rootParts(rawRoot);
  for (const form of variants) {
    if (form.length < 3 || rootRecords.has(form)) continue;
    const meaningEn = String(metadata.meaning ?? "").trim();
    rootRecords.set(form, {
      form,
      meaningZh: chineseRootMeaning(meaningEn),
      meaningEn,
      kind: String(metadata.class ?? "root").trim(),
      position: rawRoot.startsWith("-") ? "suffix" : rawRoot.endsWith("-") ? "prefix" : "any",
    });
  }
}

for (const entry of entries.values()) {
  entry.roots = entry.roots.map((form) => {
    const metadata = rootRecords.get(form);
    return {
      form,
      meaningZh: metadata?.meaningZh ?? "",
      meaningEn: metadata?.meaningEn ?? "",
      kind: metadata?.kind ?? "root",
      inferred: false,
      source: "engra",
    };
  });
}

function chunkKey(value) {
  return `${value[0] ?? "_"}${value[1] ?? "_"}`;
}

function deleteVariants(word) {
  const variants = new Set([word]);
  for (let index = 0; index < word.length; index += 1) {
    variants.add(word.slice(0, index) + word.slice(index + 1));
  }
  return variants;
}

const entryChunks = new Map();
const deleteChunks = new Map();
for (const entry of entries.values()) {
  const key = chunkKey(entry.word);
  if (!entryChunks.has(key)) entryChunks.set(key, []);
  entryChunks.get(key).push(entry);

  if (entry.word.length >= 2 && entry.word.length <= 48) {
    for (const deleted of deleteVariants(entry.word)) {
      const deleteKey = chunkKey(deleted);
      if (!deleteChunks.has(deleteKey)) deleteChunks.set(deleteKey, new Map());
      const index = deleteChunks.get(deleteKey);
      if (!index.has(deleted)) index.set(deleted, []);
      index.get(deleted).push(entry.word);
    }
  }
}

for (const values of entryChunks.values()) {
  values.sort((left, right) => left.word.localeCompare(right.word, "en"));
}
for (const index of deleteChunks.values()) {
  for (const candidates of index.values()) {
    candidates.sort((left, right) => {
      const rankDifference = entries.get(left).frequencyRank - entries.get(right).frequencyRank;
      return rankDifference || left.localeCompare(right, "en");
    });
  }
}

function encode(value) {
  return gzipSync(JSON.stringify(value), { level: 9, mtime: 0 }).toString("base64");
}

async function writeAtomically(path, content) {
  const temporary = `${path}.${process.pid}.tmp`;
  await writeFile(temporary, content);
  await rename(temporary, path);
}

const encodedEntries = Object.fromEntries([...entryChunks].sort().map(([key, value]) => [key, encode(value)]));
const entryCount = [...entryChunks.values()].reduce((total, values) => total + values.length, 0);
if (entryCount !== entries.size) {
  throw new Error(`词条块计数不一致：${entryCount} != ${entries.size}`);
}
const encodedDeletes = Object.fromEntries(
  [...deleteChunks].sort().map(([key, value]) => [key, encode(Object.fromEntries(value))]),
);
const encodedRoots = encode([...rootRecords.values()].sort((a, b) => b.form.length - a.form.length || a.form.localeCompare(b.form)));

const sourceHashes = {};
for (const source of sources) {
  const content = await readFile(join(cacheDir, source.file));
  sourceHashes[source.key] = createHash("sha256").update(content).digest("hex");
}

const dictionaryVersion = `ecdict-${ECDICT_COMMIT.slice(0, 12)}+engra-${ENGRA_COMMIT.slice(0, 12)}`;
const generated = `// Generated by scripts/dictionary/build.mjs. Do not edit.\n` +
  `export const GENERATED_DICTIONARY = ${JSON.stringify({
    dictionaryVersion,
    ecdictCommit: ECDICT_COMMIT,
    engraCommit: ENGRA_COMMIT,
    sourceHashes,
    entryCount,
    entryChunks: encodedEntries,
    deleteChunks: encodedDeletes,
    roots: encodedRoots,
  })} as const;\n`;
await writeAtomically(join(outputDir, "data.ts"), generated);
await writeAtomically(
  join(outputDir, "metadata.json"),
  `${JSON.stringify({ dictionaryVersion, entryCount, sourceHashes }, null, 2)}\n`,
);
process.stdout.write(`完成：${entries.size} 个词条，${entryChunks.size} 个词条块，${deleteChunks.size} 个纠错块。\n`);
