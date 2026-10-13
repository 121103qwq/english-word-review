#!/usr/bin/env python3
"""Standard-library WordWatch content pipeline. No external AI calls or credentials."""
from __future__ import annotations

import argparse
import copy
import csv
import hashlib
import json
import math
import os
import re
import shutil
import subprocess
import sys
import uuid
from datetime import date, datetime, timedelta, timezone
from pathlib import Path
from typing import Any
from urllib.parse import urlsplit

COLUMNS = ("rank", "word", "phonetic", "meaning", "zipf")
LIMIT = 30
MAX_WORDS = 500
MAX_NEW_CANDIDATES = MAX_WORDS
MAX_WITHDRAWN_IDS = 5000
SUPPORTED_CAPABILITIES = frozenset({
    "plain-text", "choice-v1", "word-notes-v1", "package-delta-v1", "word-withdrawal-v1",
    "input-v1", "recall-v1", "review-policy-v1",
})
SHANGHAI = timezone(timedelta(hours=8), "Asia/Shanghai")
INTERVALS = (1, 3, 7, 14, 30, 60)
HERE = Path(__file__).resolve()
PRIVATE = HERE.parents[3] / "data" / "watch-words"
BAD_CONTROL = re.compile(r"[\x00-\x08\x0b\x0c\x0e-\x1f\x7f]")
CSV_ID = re.compile(r"^csv:(\d{4,12})$")
DYN_ID = re.compile(r"^dyn:[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$")


class ContentError(ValueError):
    pass


def sha256(path: Path) -> str:
    h = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            h.update(block)
    return h.hexdigest()


def write_json(path: Path, value: Any) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    temp = path.with_name(path.name + ".tmp")
    temp.write_text(json.dumps(value, ensure_ascii=False, indent=2) + "\n", encoding="utf-8", newline="\n")
    os.replace(temp, path)


def read_json(path: Path) -> Any:
    try:
        return json.loads(path.read_text(encoding="utf-8-sig"))
    except (OSError, json.JSONDecodeError) as exc:
        raise ContentError(f"无法读取 JSON {path.name}: {exc}") from exc


def export_token_issues(word: str) -> list[str]:
    """Conservatively flag known export-style identifiers without guessing mappings."""
    if "_" in word:
        return ["疑似导出标识（word 含下划线）；保留原行与 ID，需人工 mapping/待核对，不得直接教学"]
    return []


def import_csv(source: Path, output: Path, expected_rows: int = 4237) -> dict[str, Any]:
    source, output = source.resolve(), output.resolve()
    if not source.is_file():
        raise ContentError(f"找不到 CSV：{source}")
    digest = sha256(source)
    copied, catalog_path = output / "source.csv", output / "catalog.json"
    if copied.exists() and sha256(copied) != digest:
        raise ContentError("source.csv 已有不同哈希；为保护唯一底库，拒绝覆盖")
    if catalog_path.exists() and read_json(catalog_path).get("sourceSha256") != digest:
        raise ContentError("catalog.json 已关联另一底库；为保护唯一底库，拒绝覆盖")
    with source.open("r", encoding="utf-8-sig", newline="") as stream:
        reader = csv.reader(stream, strict=True)
        headers = next(reader, None)
        if headers is None:
            raise ContentError("CSV 为空")
        positions: dict[str, list[int]] = {}
        for i, name in enumerate(headers):
            positions.setdefault(name.strip(), []).append(i)
        missing = [key for key in COLUMNS if len(positions.get(key, [])) != 1]
        rows: list[dict[str, Any]] = []
        global_issues: list[dict[str, Any]] = []
        if missing:
            global_issues.append({"type": "header_mapping", "status": "待核对", "columns": missing,
                                  "message": "必需列缺失或重复；源记录完整保存在 rawValues"})
        for line, values in enumerate(reader, start=2):
            raw = {name: values[i] for i, name in enumerate(headers) if i < len(values)}
            issues: list[str] = []
            if len(values) != len(headers):
                issues.append(f"列数异常：表头 {len(headers)}，记录 {len(values)}")
            if missing:
                issues.append("必需列无法唯一映射")
            mapped = {}
            for key in COLUMNS:
                indexes = positions.get(key, [])
                mapped[key] = values[indexes[0]] if len(indexes) == 1 and indexes[0] < len(values) else ""
            rank_text = mapped["rank"].strip()
            rank = int(rank_text) if rank_text.isdecimal() and int(rank_text) > 0 else None
            if rank is None:
                issues.append("rank 非正整数，无法生成稳定 ID")
            word = mapped["word"].strip()
            if not word:
                issues.append("word 为空")
            issues.extend(export_token_issues(word))
            if not mapped["phonetic"].strip():
                issues.append("phonetic 为空")
            if not mapped["meaning"].strip():
                issues.append("meaning 为空")
            try:
                zipf = float(mapped["zipf"].strip())
                if not math.isfinite(zipf):
                    raise ValueError
            except ValueError:
                zipf = None
                issues.append("zipf 不是有限数值")
            rows.append({
                "id": f"csv:{rank:04d}" if rank else None, "rank": rank, "word": word,
                "phonetic": mapped["phonetic"].strip(), "meaning": mapped["meaning"].strip(),
                "zipf": zipf, "rawFields": raw, "rawValues": list(values), "sourceLine": line,
                "status": "待核对" if issues else "ok", "issues": issues,
                "exceptionType": "export_token_suspect" if "_" in word else None,
            })
    ranks: dict[int, list[dict[str, Any]]] = {}
    words: dict[str, list[dict[str, Any]]] = {}
    for row in rows:
        if row["rank"] is not None:
            ranks.setdefault(row["rank"], []).append(row)
        if row["word"]:
            words.setdefault(row["word"].casefold(), []).append(row)
    for rank, group in ranks.items():
        if len(group) > 1:
            for row in group:
                row["issues"].append(f"rank {rank} 重复，需人工 mapping/待核对")
                row["status"] = "待核对"
    for word_key, group in words.items():
        if len(group) > 1:
            for row in group:
                row["issues"].append(f"word {word_key!r} 重复，需人工 mapping/待核对")
                row["status"] = "待核对"
    if len(rows) != expected_rows:
        global_issues.append({"type": "row_count", "status": "待核对", "expectedRows": expected_rows,
                              "actualRows": len(rows), "message": "行数不符；未丢弃任何源记录"})
    missing_ranks = sorted(set(range(1, expected_rows + 1)) - {r["rank"] for r in rows if r["rank"] is not None})
    if missing_ranks:
        global_issues.append({"type": "rank_gaps", "status": "待核对", "missingRanks": missing_ranks[:100],
                              "missingRankCount": len(missing_ranks), "message": "不得猜测或自动补号"})
    exceptions = {
        "schemaVersion": 1, "sourceSha256": digest, "sourceRows": len(rows),
        "expectedRows": expected_rows, "global": global_issues,
        "rows": [{"sourceLine": r["sourceLine"], "id": r["id"], "rank": r["rank"],
                  "word": r["word"], "status": "待核对",
                  "exceptionType": r.get("exceptionType") or "catalog_integrity",
                  "mapping": None,
                  "mappingStatus": ("待人工 mapping；不得猜测" if r.get("exceptionType") == "export_token_suspect"
                                    else "不适用；原字段待核对"),
                  "issues": r["issues"], "rawFields": r["rawFields"], "rawValues": r["rawValues"]}
                 for r in rows if r["status"] != "ok"],
    }
    catalog = {
        "schemaVersion": 1, "catalogSha256": digest, "sourceSha256": digest,
        "sourceFileName": source.name, "sourceRows": len(rows), "expectedRows": expected_rows,
        "columns": headers, "rows": rows, "importedRows": len(rows),
        "usableRows": sum(r["status"] == "ok" for r in rows), "exceptionRows": len(exceptions["rows"]),
        "exportTokenRows": sum(r.get("exceptionType") == "export_token_suspect" for r in rows),
    }
    output.mkdir(parents=True, exist_ok=True)
    if not copied.exists():
        shutil.copyfile(source, copied)
    write_json(catalog_path, catalog)
    write_json(output / "exceptions.json", exceptions)
    return {"sourceSha256": digest, "sourceRows": len(rows), "usableRows": catalog["usableRows"],
            "exceptionRows": len(exceptions["rows"]), "exportTokenRows": catalog["exportTokenRows"],
            "globalExceptions": len(global_issues)}


def check_text(value: Any, label: str, maximum: int = 500, *, allow_empty: bool = False) -> str:
    if not isinstance(value, str) or (not allow_empty and not value.strip()):
        raise ContentError(f"{label} 必须是非空字符串")
    if len(value) > maximum or BAD_CONTROL.search(value):
        raise ContentError(f"{label} 过长或含控制字符")
    return value


def _capabilities(value: Any) -> set[str]:
    if not isinstance(value, list) or any(not isinstance(item, str) or not item.strip() for item in value):
        raise ContentError("requiredCapabilities 必须是字符串数组")
    for item in value:
        check_text(item, "requiredCapabilities", 80)
    if len(value) != len(set(value)):
        raise ContentError("requiredCapabilities 有重复项")
    unsupported = set(value) - SUPPORTED_CAPABILITIES
    if unsupported:
        raise ContentError("unsupported required capability: " + sorted(unsupported)[0])
    return set(value)


def _string_array(value: Any, label: str, minimum: int, maximum: int, item_max: int) -> None:
    if not isinstance(value, list) or not minimum <= len(value) <= maximum:
        raise ContentError(f"{label} 项数无效")
    for item in value:
        check_text(item, label, item_max)


def _validate_word(word: Any, index: int, schema: int, capabilities: set[str]) -> str:
    where = f"words[{index}]"
    if not isinstance(word, dict):
        raise ContentError(f"{where} 必须是对象")
    wid = check_text(word.get("id"), where + ".id", 160)
    match = CSV_ID.fullmatch(wid)
    if not (match or DYN_ID.fullmatch(wid)):
        raise ContentError(f"{where}.id 格式无效")
    check_text(word.get("word"), where + ".word", 100)
    check_text(word.get("phonetic"), where + ".phonetic", 120, allow_empty=True)
    check_text(word.get("meaning"), where + ".meaning", 1200)
    rank = word.get("rank")
    if type(rank) is not int or rank < 1:
        raise ContentError(f"{where}.rank 必须是正整数")
    if match and int(match.group(1)) != rank:
        raise ContentError(f"{where}.id 与 rank 不匹配")
    if word.get("generatedBy") != "AI":
        raise ContentError(f"{where}.generatedBy 必须是 AI")
    _string_array(word.get("senses"), where + ".senses", 1, 16, 500)
    if "collocations" in word:
        _string_array(word["collocations"], where + ".collocations", 1, 16, 500)
    examples = word.get("examples")
    if not isinstance(examples, list) or not 1 <= len(examples) <= 8:
        raise ContentError(f"{where}.examples 必须有 1-8 条")
    for example in examples:
        if not isinstance(example, dict):
            raise ContentError(f"{where}.example 必须是对象")
        check_text(example.get("en"), where + ".example.en", 500)
        check_text(example.get("zh"), where + ".example.zh", 700)
    if "roots" in word:
        roots = word["roots"]
        if isinstance(roots, str):
            check_text(roots, where + ".roots", 1200, allow_empty=True)
        else:
            _string_array(roots, where + ".roots", 0, 16, 500)
    if "notes" in word:
        notes = word["notes"]
        if not isinstance(notes, dict):
            raise ContentError(f"{where}.notes 必须是对象")
        for field in ("partOfSpeech", "forms", "chunks", "frames", "pitfalls", "retrievalTips"):
            if field in notes:
                _string_array(notes[field], where + ".notes." + field, 0, 24, 500)
        if schema == 2 and "word-notes-v1" not in capabilities:
            raise ContentError("notes 必须声明 word-notes-v1")
        if "sections" in notes:
            sections = notes["sections"]
            if not isinstance(sections, list) or len(sections) > 24:
                raise ContentError(f"{where}.notes.sections 项数无效")
            for section in sections:
                if not isinstance(section, dict):
                    raise ContentError(f"{where}.notes.sections 项必须是对象")
                check_text(section.get("title"), where + ".notes.sections.title", 100)
                if "body" not in section and "items" not in section:
                    raise ContentError("notes section needs body or items")
                if "body" in section:
                    check_text(section["body"], where + ".notes.sections.body", 2000)
                if "items" in section:
                    _string_array(section["items"], where + ".notes.sections.items", 0, 32, 1000)
    questions = word.get("questions")
    if not isinstance(questions, list) or not (2 <= len(questions) <= 16 if schema == 1
                                                 else 1 <= len(questions) <= 32):
        raise ContentError(f"{where}.questions 项数无效")
    question_ids: set[str] = set()
    modes: set[str] = set()
    for q in questions:
        if not isinstance(q, dict):
            raise ContentError(f"{where}.question 必须是对象")
        qid = check_text(q.get("id"), where + ".question.id", 160)
        if qid in question_ids:
            raise ContentError(f"{where} 有重复 question id")
        question_ids.add(qid)
        qtype = q.get("type", "choice" if schema == 1 else None)
        if qtype not in ("choice", "input", "recall"):
            raise ContentError(f"unsupported question type: {qtype}")
        capability = {"choice": "choice-v1", "input": "input-v1", "recall": "recall-v1"}[qtype]
        if qtype != "choice" and schema != 2:
            raise ContentError(f"{qtype} questions require schemaVersion 2")
        if schema == 2 and capability not in capabilities:
            raise ContentError(f"{qtype} questions must require {capability}")
        mode = q.get("mode")
        if not isinstance(mode, str) or not (re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9._:-]{0,63}", mode)
                                             if schema == 2 else mode in ("forward", "reverse", "rare")):
            raise ContentError(f"{where} question mode 无效")
        modes.add(mode)
        check_text(q.get("prompt"), where + ".prompt", 500)
        if qtype == "choice":
            options = q.get("options")
            if not isinstance(options, list) or not 2 <= len(options) <= 4:
                raise ContentError(f"{where}.options 必须有 2-4 项")
            clean = [check_text(item, where + ".option", 500) for item in options]
            if len({item.strip().lower() for item in clean}) != len(clean):
                raise ContentError(f"{where}.options 有重复项")
            ci = q.get("correctIndex")
            if type(ci) is not int or not 0 <= ci < len(options):
                raise ContentError(f"{where}.correctIndex 越界或非整数")
        elif qtype == "input":
            _string_array(q.get("acceptedAnswers"), where + ".acceptedAnswers", 1, 32, 2000)
            _validate_match_options(q, where)
        else:
            check_text(q.get("answer"), where + ".answer", 2000)
            if "acceptedAnswers" in q:
                _string_array(q["acceptedAnswers"], where + ".acceptedAnswers", 1, 32, 2000)
            _validate_match_options(q, where)
        check_text(q.get("explanation"), where + ".explanation", 1200)
    if schema == 1 and not {"forward", "reverse"}.issubset(modes):
        raise ContentError(f"{where} 必须有 forward 和 reverse 题")
    return wid


def _validate_match_options(question: dict[str, Any], label: str) -> None:
    for field in ("caseSensitive", "ignorePunctuation"):
        if field in question and type(question[field]) is not bool:
            raise ContentError(f"{label}.{field} 必须是布尔值")


def _validate_review_policy(policy: Any) -> None:
    if not isinstance(policy, dict):
        raise ContentError("reviewPolicy 必须是对象")
    intervals = policy.get("intervalDays")
    if not isinstance(intervals, list) or not 1 <= len(intervals) <= 32:
        raise ContentError("reviewPolicy.intervalDays 项数无效")
    previous = 0
    for value in intervals:
        if type(value) is not int or value <= previous or value > 3650:
            raise ContentError("review intervals must increase within 3650 days")
        previous = value
    for field in ("wrongAfterDays", "hardAfterDays"):
        value = policy.get(field)
        if type(value) is not int or not 1 <= value <= 3650:
            raise ContentError(f"reviewPolicy.{field} 无效")


def _id_list(value: Any, label: str, maximum: int) -> list[str]:
    if not isinstance(value, list) or len(value) > maximum or any(not isinstance(x, str) for x in value):
        raise ContentError(f"{label} 必须是字符串 ID 数组")
    if len(value) != len(set(value)):
        raise ContentError(f"{label} 有重复 ID")
    for item in value:
        if not (CSV_ID.fullmatch(item) or DYN_ID.fullmatch(item)):
            raise ContentError(f"{label} ID 格式无效")
    return value


def validate_package(pack: Any, catalog: dict[str, Any] | None = None,
                     ai_input: dict[str, Any] | None = None,
                     *, require_generated_limit: bool = False,
                     require_dynamic_evidence: bool = True) -> dict[str, int]:
    if not isinstance(pack, dict) or type(pack.get("schemaVersion")) is not int:
        raise ContentError("日报包 schemaVersion 必须是整数")
    schema = pack["schemaVersion"]
    if schema not in (1, 2):
        raise ContentError("unsupported package schemaVersion")
    for key in ("packageId", "date", "timezone", "contentMode", "catalogSha256"):
        check_text(pack.get(key), key, 160)
    try:
        if not re.fullmatch(r"\d{4}-\d{2}-\d{2}", pack["date"]):
            raise ValueError
        date.fromisoformat(pack["date"])
    except ValueError as exc:
        raise ContentError("date 必须为 YYYY-MM-DD") from exc
    if pack["timezone"] != "Asia/Shanghai" or pack["contentMode"] != "ai-authored":
        raise ContentError("timezone/contentMode 不符合契约")
    if not re.fullmatch(r"[0-9a-fA-F]{64}", pack["catalogSha256"]):
        raise ContentError("catalogSha256 必须是 SHA-256")
    limit = pack.get("newWordLimit")
    min_limit = -1 if schema == 2 else 0
    if type(limit) is not int or not min_limit <= limit <= 10000:
        raise ContentError("newWordLimit 超出支持范围")
    if require_generated_limit and limit != LIMIT:
        raise ContentError(f"新生成包 newWordLimit 必须为 {LIMIT}")

    if schema == 2:
        capabilities = _capabilities(pack.get("requiredCapabilities"))
        if "plain-text" not in capabilities:
            raise ContentError("v2 包必须声明 plain-text")
        revision = pack.get("revision")
        if type(revision) is not int or revision < 1:
            raise ContentError("revision 必须是正整数")
        kind = pack.get("kind")
        if kind not in ("full", "delta"):
            raise ContentError("kind 必须是 full 或 delta")
        if kind == "delta":
            base_revision = pack.get("baseRevision")
            if type(base_revision) is not int or not 1 <= base_revision < revision:
                raise ContentError("baseRevision 必须为小于 revision 的正整数")
            if "package-delta-v1" not in capabilities:
                raise ContentError("delta 包必须声明 package-delta-v1")
        elif "baseRevision" in pack:
            raise ContentError("full 包不能包含 baseRevision")
        if "reviewPolicy" in pack:
            if "review-policy-v1" not in capabilities:
                raise ContentError("reviewPolicy 必须声明 review-policy-v1")
            _validate_review_policy(pack["reviewPolicy"])
        if "reviewMode" in pack:
            if "review-policy-v1" not in capabilities:
                raise ContentError("reviewMode 必须声明 review-policy-v1")
            if pack["reviewMode"] not in ("question", "self-recall"):
                raise ContentError("reviewMode 必须为 question 或 self-recall")
    else:
        capabilities = _capabilities(pack["requiredCapabilities"]) if "requiredCapabilities" in pack else set()
        kind = "full"
        if "reviewPolicy" in pack or "reviewMode" in pack:
            raise ContentError("review settings require schemaVersion 2")

    words = pack.get("words")
    if not isinstance(words, list) or len(words) > MAX_WORDS:
        raise ContentError(f"words 必须是最多 {MAX_WORDS} 项的数组")
    by_id: dict[str, dict[str, Any]] = {}
    for index, word in enumerate(words):
        wid = _validate_word(word, index, schema, capabilities)
        if wid in by_id:
            raise ContentError(f"重复 word id：{wid}")
        by_id[wid] = word

    new_ids = _id_list(pack.get("newWordIds"), "newWordIds", MAX_NEW_CANDIDATES)
    review_ids = _id_list(pack.get("reviewWordIds"), "reviewWordIds", MAX_WORDS)
    if set(new_ids) & set(review_ids):
        raise ContentError("新词与复习词 ID 重叠")
    if schema == 2:
        withdrawn = _id_list(pack.get("withdrawnWordIds"), "withdrawnWordIds", MAX_WITHDRAWN_IDS)
        if withdrawn and "word-withdrawal-v1" not in capabilities:
            raise ContentError("withdrawnWordIds 必须声明 word-withdrawal-v1")
        if set(withdrawn) & set(by_id):
            raise ContentError("active word is also withdrawn")
        if set(withdrawn) & (set(new_ids) | set(review_ids)):
            raise ContentError("new/review list references a withdrawn word")
    else:
        withdrawn = []

    if kind == "delta":
        upsert_ids = set(by_id)
        if upsert_ids & set(withdrawn):
            raise ContentError("delta cannot upsert a withdrawn word")
    else:
        if any(wid not in by_id for wid in new_ids + review_ids):
            raise ContentError("new/review list references a missing word")
        assigned = set(new_ids) | set(review_ids)
        if assigned != set(by_id):
            raise ContentError("every full-package word must be new or review")

    if catalog is not None:
        if pack["catalogSha256"].lower() != str(catalog.get("catalogSha256", "")).lower():
            raise ContentError("日报包 catalogSha256 与当前私有底库不一致")
        lookup = {r["id"]: r for r in catalog.get("rows", [])
                  if r.get("status") == "ok" and isinstance(r.get("id"), str)}
        for wid, word in by_id.items():
            if wid.startswith("csv:"):
                source = lookup.get(wid)
                if source is None or source.get("word") != word["word"] or source.get("rank") != word["rank"]:
                    raise ContentError(f"{wid} 与 CSV 唯一底库不匹配或待核对")

    dynamic_references = set(by_id) | set(new_ids) | set(review_ids)
    if require_dynamic_evidence and any(wid.startswith("dyn:") for wid in dynamic_references):
        if ai_input is None:
            raise ContentError("dyn: 词包必须提供 --ai-input 以核验真实 evidence")
        validate_dynamic_words(pack, by_id, ai_input)

    return {"words": len(words), "newCandidates": len(new_ids), "reviews": len(review_ids)}


def validate_dynamic_words(pack: dict[str, Any], words: dict[str, dict[str, Any]],
                           ai_input: dict[str, Any]) -> None:
    if ai_input.get("packageId") != pack.get("packageId"):
        raise ContentError("AI input packageId 与日报包不一致")
    if ai_input.get("date") != pack.get("date"):
        raise ContentError("AI input date 与日报包不一致")
    if str(ai_input.get("catalogSha256", "")).lower() != str(pack.get("catalogSha256", "")).lower():
        raise ContentError("AI input catalogSha256 与日报包不一致")
    manifest = ai_input.get("dynamicEvidenceManifest")
    if not isinstance(manifest, list):
        raise ContentError("AI input 缺少 dynamicEvidenceManifest")
    by_id: dict[str, dict[str, Any]] = {}
    for item in manifest:
        if not isinstance(item, dict):
            raise ContentError("dynamicEvidenceManifest 项必须是对象")
        wid = item.get("id")
        if not isinstance(wid, str) or not DYN_ID.fullmatch(wid) or wid in by_id:
            raise ContentError("dynamicEvidenceManifest ID 无效或重复")
        word = check_text(item.get("word"), "dynamicEvidenceManifest.word", 100)
        evidence = item.get("evidence")
        if not isinstance(evidence, (str, dict)) or not evidence:
            raise ContentError(f"{wid} 缺少真实 evidence")
        if isinstance(evidence, str):
            check_text(evidence, "dynamicEvidenceManifest.evidence", 2000)
        by_id[wid] = {"word": word, "evidence": evidence}

    def input_rows(field: str) -> dict[str, dict[str, Any]]:
        rows = ai_input.get(field)
        if not isinstance(rows, list):
            raise ContentError(f"AI input 缺少 {field}")
        result: dict[str, dict[str, Any]] = {}
        seen_ids: set[str] = set()
        for row in rows:
            if not isinstance(row, dict):
                raise ContentError(f"AI input {field} 项必须是对象")
            wid = row.get("id")
            if (not isinstance(wid, str) or not (DYN_ID.fullmatch(wid) or CSV_ID.fullmatch(wid))
                    or wid in seen_ids):
                raise ContentError(f"AI input {field} 中的词 ID 无效或重复")
            seen_ids.add(wid)
            word = check_text(row.get("word"), f"{field}.word", 100)
            if DYN_ID.fullmatch(wid):
                result[wid] = {"word": word, "evidence": row.get("evidence")}
        return result

    candidates = input_rows("dynamicCandidates")
    due_reviews = input_rows("reviewsDue")
    new_ids = set(pack.get("newWordIds", []))
    review_ids = set(pack.get("reviewWordIds", []))

    for wid, word in words.items():
        if wid.startswith("dyn:"):
            source = by_id.get(wid)
            if source is None or source["word"] != word.get("word"):
                raise ContentError(f"{wid} 与 AI input/evidence manifest 的 ID 或词形不一致")

    referenced_dynamic_ids = {wid for wid in new_ids | review_ids if wid.startswith("dyn:")}
    for wid in referenced_dynamic_ids:
        source = by_id.get(wid)
        if source is None:
            raise ContentError(f"{wid} 不在 AI input/evidence manifest 中")
        if wid in new_ids:
            candidate = candidates.get(wid)
            if (candidate is None or candidate["word"] != source["word"]
                    or candidate.get("evidence") != source["evidence"]):
                raise ContentError(f"{wid} 不是 AI input 中有匹配 evidence 的动态新词候选")
        if wid in review_ids:
            due = due_reviews.get(wid)
            if due is None or due["word"] != source["word"]:
                raise ContentError(f"{wid} 不是 AI input 中词形匹配的到期动态复习词")


def revision(pack: dict[str, Any]) -> int:
    if pack.get("schemaVersion") == 1:
        return 0
    if pack.get("schemaVersion") != 2 or type(pack.get("revision")) is not int or pack["revision"] < 1:
        raise ContentError("unsupported package revision")
    return pack["revision"]


def _canonical(value: Any) -> str:
    return json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(",", ":"))


def apply_package(existing: dict[str, Any] | None, incoming: dict[str, Any],
                  catalog: dict[str, Any] | None = None,
                  ai_input: dict[str, Any] | None = None) -> dict[str, Any]:
    validate_package(incoming, catalog, ai_input)
    incoming_schema = incoming["schemaVersion"]
    incoming_kind = incoming.get("kind", "full")
    if incoming_kind == "delta" and existing is None:
        raise ContentError("delta package requires a base package")
    if existing is None:
        return copy.deepcopy(incoming)

    validate_package(existing, catalog, require_dynamic_evidence=False)
    existing_schema = existing["schemaVersion"]
    incoming_date, existing_date = incoming["date"], existing["date"]
    if incoming_schema == 1:
        if existing_schema == 2:
            if incoming_date < existing_date:
                return copy.deepcopy(existing)
            if incoming_date > existing_date and incoming["packageId"] != existing["packageId"]:
                return copy.deepcopy(incoming)
            raise ContentError("cannot replace a v2 package for the same date or packageId")
        if incoming_date < existing_date:
            return copy.deepcopy(existing)
        if incoming_date == existing_date and _canonical(existing) == _canonical(incoming):
            return copy.deepcopy(existing)
        return copy.deepcopy(incoming)

    if incoming["packageId"] != existing["packageId"]:
        if incoming_kind != "full":
            raise ContentError("delta packageId must match its base")
        if incoming_date < existing_date:
            return copy.deepcopy(existing)
        if incoming_date == existing_date:
            raise ContentError("different packageId for the same date")
        return copy.deepcopy(incoming)

    if incoming_date != existing_date:
        raise ContentError("package date cannot change across revisions")
    if existing.get("catalogSha256") != incoming.get("catalogSha256"):
        raise ContentError("catalogSha256 cannot change across package revisions")
    current_revision, next_revision = revision(existing), revision(incoming)
    if next_revision < current_revision:
        raise ContentError("stale package revision")
    if next_revision == current_revision:
        if incoming_kind == "full" and _canonical(existing) == _canonical(incoming):
            return copy.deepcopy(existing)
        if incoming_kind == "delta" and _same_applied_delta(existing, incoming):
            return copy.deepcopy(existing)
        raise ContentError("package revision has conflicting contents")

    if incoming_kind == "full":
        _require_full_revision_identity(existing, incoming)
        return copy.deepcopy(incoming)

    if existing_schema != 2 or existing.get("kind") != "full":
        raise ContentError("delta base must be a v2 full package")
    if incoming.get("baseRevision") != current_revision:
        raise ContentError("delta baseRevision does not match the current package")
    if incoming["catalogSha256"] != existing["catalogSha256"]:
        raise ContentError("delta catalogSha256 does not match the base package")

    old_withdrawn = set(existing["withdrawnWordIds"])
    active = {word["id"]: copy.deepcopy(word) for word in existing["words"]}
    delta_withdrawn = set(incoming["withdrawnWordIds"])
    if not delta_withdrawn.issubset(set(active) | old_withdrawn):
        raise ContentError("delta may withdraw only words in its base or prior tombstones")
    for word in incoming["words"]:
        wid = word["id"]
        if wid in old_withdrawn:
            raise ContentError("withdrawn word id cannot be restored")
        if wid in active and active[wid]["word"] != word["word"]:
            raise ContentError("word identity cannot change across revisions")
        active[wid] = copy.deepcopy(word)
    withdrawn = old_withdrawn | set(incoming["withdrawnWordIds"])
    result = copy.deepcopy(existing)
    delta_only = {"words", "withdrawnWordIds", "newWordIds", "reviewWordIds", "kind", "baseRevision"}
    for key, value in incoming.items():
        if key not in delta_only and key != "requiredCapabilities":
            result[key] = copy.deepcopy(value)
    result.update({
        "schemaVersion": 2,
        "packageId": incoming["packageId"],
        "date": incoming["date"],
        "catalogSha256": incoming["catalogSha256"],
        "revision": next_revision,
        "kind": "full",
        "newWordLimit": incoming["newWordLimit"],
        "words": [word for wid, word in active.items() if wid not in withdrawn],
        "withdrawnWordIds": sorted(withdrawn),
        "newWordIds": copy.deepcopy(incoming["newWordIds"]),
        "reviewWordIds": copy.deepcopy(incoming["reviewWordIds"]),
    })
    capabilities = set(existing["requiredCapabilities"]) | set(incoming["requiredCapabilities"])
    capabilities.discard("package-delta-v1")
    if withdrawn:
        capabilities.add("word-withdrawal-v1")
    result["requiredCapabilities"] = sorted(capabilities)
    validate_package(result, catalog, require_dynamic_evidence=False)
    return result


def _require_full_revision_identity(existing: dict[str, Any], incoming: dict[str, Any]) -> None:
    old_tombstones = set(existing.get("withdrawnWordIds", [])) if existing.get("schemaVersion") == 2 else set()
    new_tombstones = set(incoming["withdrawnWordIds"])
    if not old_tombstones.issubset(new_tombstones):
        raise ContentError("full package must retain prior tombstones")
    new_forms = {word["id"]: word["word"] for word in incoming["words"]}
    for word in existing["words"]:
        wid = word["id"]
        if wid in new_forms and new_forms[wid] != word["word"]:
            raise ContentError("word identity cannot change across revisions")
        if wid not in new_forms and wid not in new_tombstones:
            raise ContentError("removed words must be withdrawn explicitly")


def _same_applied_delta(existing: dict[str, Any], delta: dict[str, Any]) -> bool:
    if (existing.get("schemaVersion") != 2 or existing.get("packageId") != delta.get("packageId")
            or existing.get("date") != delta.get("date")
            or existing.get("catalogSha256") != delta.get("catalogSha256")
            or revision(existing) != revision(delta)
            or delta.get("baseRevision", revision(existing)) >= revision(existing)
            or existing.get("newWordLimit") != delta.get("newWordLimit")
            or existing.get("newWordIds") != delta.get("newWordIds")
            or existing.get("reviewWordIds") != delta.get("reviewWordIds")):
        return False
    active = {word["id"]: word for word in existing.get("words", [])}
    if any(wid not in active or _canonical(active[wid]) != _canonical(word)
           for wid, word in ((word["id"], word) for word in delta.get("words", []))):
        return False
    if not set(delta.get("withdrawnWordIds", [])).issubset(set(existing.get("withdrawnWordIds", []))):
        return False
    skip = {"words", "withdrawnWordIds", "newWordIds", "reviewWordIds", "kind", "baseRevision",
            "requiredCapabilities"}
    return all(key in existing and _canonical(existing[key]) == _canonical(value)
               for key, value in delta.items() if key not in skip)


def event_day(event: dict[str, Any]) -> date:
    at = event.get("at")
    if type(at) is not int or at < 0:
        raise ContentError(f"事件 {event.get('id', '?')} 的 at 不是 epoch 毫秒")
    try:
        return datetime.fromtimestamp(at / 1000, SHANGHAI).date()
    except (OverflowError, OSError, ValueError) as exc:
        raise ContentError(f"事件 {event.get('id', '?')} 的 at 无效") from exc


def validate_event(event: dict[str, Any]) -> None:
    eid = event.get("id")
    if not isinstance(eid, str):
        raise ContentError("事件缺少 id")
    try:
        if str(uuid.UUID(eid)) != eid.lower():
            raise ValueError
    except (ValueError, AttributeError) as exc:
        raise ContentError("事件 id 必须是标准 UUID") from exc
    device = event.get("deviceId")
    if not isinstance(device, str) or not re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9._-]{0,127}", device):
        raise ContentError("事件 deviceId 无效")
    seq = event.get("seq")
    if type(seq) is not int or seq < 0:
        raise ContentError("事件 seq 必须是非负整数")
    event_day(event)
    kind = event.get("type")
    if event.get("requiredCapabilities") is not None:
        _capabilities(event["requiredCapabilities"])
    for field, maximum in (("questionId", 160), ("sessionId", 128), ("packageId", 128)):
        if field in event:
            check_text(event[field], "event." + field, maximum)
    if "packageRevision" in event:
        value = event["packageRevision"]
        if type(value) is not int or value < 0:
            raise ContentError("packageRevision must be a non-negative integer")
    if "scheduleDays" in event:
        value = event["scheduleDays"]
        if type(value) is not int or not 1 <= value <= 3650:
            raise ContentError("scheduleDays must be between 1 and 3650")
    if "evidence" in event:
        evidence = event["evidence"]
        if isinstance(evidence, str):
            check_text(evidence, "event.evidence", 2000, allow_empty=True)
        elif not isinstance(evidence, dict):
            raise ContentError("event.evidence must be a string or object")
    if kind == "undo":
        target = event.get("targetEventId")
        try:
            if not isinstance(target, str) or str(uuid.UUID(target)) != target.lower() or target == eid:
                raise ValueError
        except (ValueError, AttributeError) as exc:
            raise ContentError("undo target must be another standard UUID") from exc
        if "wordId" in event and not (CSV_ID.fullmatch(event["wordId"]) or DYN_ID.fullmatch(event["wordId"])):
            raise ContentError("undo wordId is invalid")
        return
    if kind != "answer":
        raise ContentError("event type must be answer or undo")
    wid = event.get("wordId")
    if not isinstance(wid, str) or not (CSV_ID.fullmatch(wid) or DYN_ID.fullmatch(wid)):
        raise ContentError("answer wordId is invalid")
    mode = event.get("mode")
    if not isinstance(mode, str) or not re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9._:-]{0,63}", mode):
        raise ContentError("event mode is invalid")
    result = event.get("result")
    if not isinstance(result, str) or result not in {"correct", "wrong", "remember", "hard", "forgot", "known"}:
        raise ContentError("event result is invalid")
    attempt = event.get("attempt")
    if not isinstance(attempt, str) or attempt not in {"primary", "retry"}:
        raise ContentError("event attempt is invalid")
    if type(event.get("assisted")) is not bool:
        raise ContentError("event assisted must be boolean")
    if "isReview" in event and type(event["isReview"]) is not bool:
        raise ContentError("event isReview must be boolean")
    if result == "known" and (mode != "skip" or attempt != "primary"):
        raise ContentError("known events must be primary skip answers")
    legacy_recall = "evidence" not in event and mode == "recall"
    self_report = event.get("evidence") == "self-report" or legacy_recall
    if result in {"remember", "hard", "forgot"} and not self_report:
        raise ContentError("self-assessed results require recall mode or self-report evidence")
    if result in {"correct", "wrong"} and legacy_recall:
        raise ContentError("legacy recall mode must use self-assessed results")


def merge_events(*sources: list[dict[str, Any]]) -> list[dict[str, Any]]:
    by_id: dict[str, dict[str, Any]] = {}
    event_by_sequence: dict[tuple[str, int], str] = {}
    for source in sources:
        for event in source:
            validate_event(event)
            eid = event["id"]
            prior = by_id.get(eid)
            if prior is not None and prior != event:
                raise ContentError("事件 ID 内容冲突")
            sequence_key = (event["deviceId"], event["seq"])
            prior_id = event_by_sequence.get(sequence_key)
            if prior_id is not None and prior_id != eid:
                raise ContentError("同设备 seq 对应多个事件 ID")
            by_id[eid] = event
            event_by_sequence[sequence_key] = eid
    return sorted(by_id.values(), key=lambda e: (e["at"], e["deviceId"], e["seq"], e["id"]))


def load_progress_value(value: Any) -> tuple[list[dict[str, Any]], list[dict[str, Any]], dict[str, Any]]:
    root = {"events": value} if isinstance(value, list) else value
    if not isinstance(root, dict):
        raise ContentError("progress 必须是对象或事件数组")
    if isinstance(root.get("progress"), dict):
        nested = dict(root["progress"])
        nested.update({k: v for k, v in root.items() if k != "progress"})
        root = nested
    events = root.get("events", [])
    if not isinstance(events, list) or any(not isinstance(x, dict) for x in events):
        raise ContentError("progress.events 必须是事件对象数组")
    raw_dyn = root.get("dynamicWords", [])
    if isinstance(raw_dyn, dict):
        dynamic = []
        for key, value in raw_dyn.items():
            if not isinstance(value, dict):
                raise ContentError(f"dynamicWords[{key}] 必须是对象")
            item = dict(value)
            item.setdefault("id", key)
            dynamic.append(item)
    elif isinstance(raw_dyn, list) and all(isinstance(x, dict) for x in raw_dyn):
        dynamic = raw_dyn
    else:
        raise ContentError("dynamicWords 必须是对象数组或 ID 映射")
    seen: set[str] = set()
    for item in dynamic:
        wid = item.get("id")
        if not isinstance(wid, str) or not DYN_ID.fullmatch(wid):
            raise ContentError("动态词必须有 dyn: ID")
        if wid in seen:
            raise ContentError(f"动态词 ID 重复：{wid}")
        seen.add(wid)
        if not isinstance(item.get("word"), str) or not item["word"].strip():
            raise ContentError(f"{wid} 缺少 word")
        evidence = item.get("evidence")
        if not isinstance(evidence, (str, dict)) or not evidence:
            raise ContentError(f"{wid} 缺少用户添加证据；不得编造动态词")
    cards = root.get("cards", {})
    if not isinstance(cards, dict):
        raise ContentError("progress.cards 必须是对象映射")
    return events, dynamic, cards


def load_progress(path: Path | None) -> tuple[list[dict[str, Any]], list[dict[str, Any]], dict[str, Any], bool]:
    if path is None or not path.exists():
        return [], [], {}, False
    if path.is_file():
        events, dynamic, cards = load_progress_value(read_json(path))
        return events, dynamic, cards, True
    if not path.is_dir():
        raise ContentError("progress 路径无效")
    events: list[dict[str, Any]] = []
    dynamic: list[dict[str, Any]] = []
    cards: dict[str, Any] = {}
    available = False
    main = path / "progress.json"
    if main.is_file():
        part_events, part_dyn, part_cards = load_progress_value(read_json(main))
        events.extend(part_events)
        dynamic.extend(part_dyn)
        cards.update(part_cards)
        available = True
    batches = path / "events"
    if batches.is_dir():
        for file in sorted(batches.glob("*.json")):
            value = read_json(file)
            if not isinstance(value, dict) or not isinstance(value.get("events"), list):
                raise ContentError(f"事件文件格式无效：{file.name}")
            if any(not isinstance(x, dict) for x in value["events"]):
                raise ContentError(f"事件文件含非对象事件：{file.name}")
            events.extend(value["events"])
            available = True
    if not available:
        return [], [], {}, False
    return merge_events(events), dynamic, cards, True


def project_events(events: list[dict[str, Any]], as_of_ms: int | None = None) -> dict[str, dict[str, Any]]:
    if as_of_ms is None:
        as_of_ms = int(datetime.now(SHANGHAI).timestamp() * 1000)
    ordered = merge_events(events)
    by_id = {event["id"]: event for event in ordered}
    active_ids = set(by_id)
    for event in reversed(ordered):
        if event.get("type") != "undo" or event.get("at", 0) > as_of_ms or event["id"] not in active_ids:
            continue
        target = event.get("targetEventId")
        if isinstance(target, str) and target in active_ids:
            active_ids.remove(target)
    active = [e for e in ordered if e.get("type") == "answer" and e.get("at", 0) <= as_of_ms
              and e["id"] in active_ids]
    states: dict[str, dict[str, Any]] = {}
    for event in active:
        wid = event.get("wordId")
        if not isinstance(wid, str) or not wid:
            raise ContentError(f"回答事件 {event['id']} 缺少 wordId")
        state = states.setdefault(wid, {"wordId": wid, "stage": None, "dueAt": None, "known": False,
                                        "lastAt": None, "objectiveRight": 0, "objectiveWrong": 0,
                                        "assistedRight": 0, "selfRemember": 0, "selfForgot": 0})
        state["lastAt"] = event["at"]
        result, mode, attempt = event.get("result"), event.get("mode"), event.get("attempt", "primary")
        assisted = event.get("assisted") is True
        self_report = (event.get("evidence") == "self-report"
                       or ("evidence" not in event and mode == "recall"))
        scheduled_days = event.get("scheduleDays")
        if attempt == "primary" and result != "known":
            state["known"] = False
        if result == "correct":
            if self_report:
                state["selfRemember"] += 1
            elif assisted:
                state["assistedRight"] += 1
            else:
                state["objectiveRight"] += 1
        elif result == "wrong":
            if self_report:
                state["selfForgot"] += 1
            else:
                state["objectiveWrong"] += 1
        elif result == "remember":
            state["selfRemember"] += 1
        elif result == "forgot":
            state["selfForgot"] += 1
        elif result == "known":
            state["known"] = True
            state["stage"], state["dueAt"] = None, None
        if attempt != "primary":
            continue
        day = event_day(event)
        due = date.fromisoformat(state["dueAt"]) if state["dueAt"] else None
        if result in ("correct", "remember") and assisted:
            if state["dueAt"] is None:
                days = scheduled_days or INTERVALS[0]
                state["stage"], state["dueAt"] = 0, (day + timedelta(days=days)).isoformat()
            continue
        if result in ("wrong", "forgot", "hard"):
            days = scheduled_days or INTERVALS[0]
            state["stage"], state["dueAt"] = 0, (day + timedelta(days=days)).isoformat()
        elif result in ("correct", "remember"):
            if state["dueAt"] is None:
                days = scheduled_days or INTERVALS[0]
                state["stage"], state["dueAt"] = 0, (day + timedelta(days=days)).isoformat()
            elif due is not None and day >= due:
                max_stage = 31 if scheduled_days is not None else len(INTERVALS) - 1
                state["stage"] = min(state["stage"] + 1, max_stage)
                days = scheduled_days or INTERVALS[state["stage"]]
                state["dueAt"] = (day + timedelta(days=days)).isoformat()
            # 提前答对不推进间隔。
    return states


def build_ai_input(catalog: dict[str, Any], events: list[dict[str, Any]], dynamics: list[dict[str, Any]],
                   cards: dict[str, Any], progress_available: bool, target: date,
                   cutoff_at: datetime | None = None) -> dict[str, Any]:
    cutoff = cutoff_at or datetime.now(SHANGHAI)
    if cutoff.tzinfo is None or cutoff.utcoffset() is None:
        raise ContentError("progressCutoffAt 必须带时区")
    cutoff = cutoff.astimezone(SHANGHAI)
    catalog_rows = {r["id"]: r for r in catalog.get("rows", [])
                    if r.get("status") == "ok" and isinstance(r.get("id"), str)}
    states = project_events(events, int(cutoff.timestamp() * 1000))
    if not states:
        for wid, raw in cards.items():
            if not isinstance(raw, dict):
                raise ContentError(f"progress.cards[{wid}] 必须是对象")
            state = states.setdefault(wid, {"wordId": wid, "stage": None, "dueAt": None, "known": False,
                                            "lastAt": None})
            stage, due = raw.get("stage", state["stage"]), raw.get("dueAt", state["dueAt"])
            if stage is not None and (type(stage) is not int or not 0 <= stage < 32):
                raise ContentError(f"{wid}.stage 无效")
            if due is not None:
                try:
                    date.fromisoformat(due)
                except (TypeError, ValueError) as exc:
                    raise ContentError(f"{wid}.dueAt 无效") from exc
            known = raw.get("known", False)
            if type(known) is not bool:
                raise ContentError(f"{wid}.known 必须是布尔值")
            state.update(stage=stage, dueAt=due, known=known, lastAt=raw.get("lastAt"))
    known = {wid for wid, state in states.items() if state.get("known")}
    dynamic_by_id = {x["id"]: x for x in dynamics}
    due_rows = []
    for wid, state in states.items():
        due = state.get("dueAt")
        if state.get("known") or not isinstance(due, str) or date.fromisoformat(due) > target:
            continue
        row, dyn = catalog_rows.get(wid), dynamic_by_id.get(wid)
        word = row.get("word") if row else dyn.get("word") if dyn else None
        if not word:
            raise ContentError(f"到期词 {wid} 不在 CSV 或有证据的动态词中")
        due_rows.append({"id": wid, "word": word, "dueAt": due, "stage": state.get("stage"),
                         "lastAt": state.get("lastAt"), "source": "csv" if row else "dynamic"})
    due_rows.sort(key=lambda x: (x["dueAt"], x["lastAt"] or 0, x["id"]))
    dynamic_candidates = []
    for item in dynamics:
        state = states.get(item["id"], {})
        if not state.get("known") and state.get("stage") is None:
            dynamic_candidates.append({"id": item["id"], "word": item["word"],
                                       "evidence": item["evidence"], "phonetic": item.get("phonetic"),
                                       "meaning": item.get("meaning")})
    dynamic_candidates = dynamic_candidates[:MAX_NEW_CANDIDATES]
    csv_candidates = []
    for row in sorted(catalog_rows.values(), key=lambda x: x["rank"]):
        state = states.get(row["id"], {})
        if not state.get("known") and state.get("stage") is None:
            if len(csv_candidates) < MAX_NEW_CANDIDATES - len(dynamic_candidates):
                csv_candidates.append({"id": row["id"], "word": row["word"], "rank": row["rank"],
                                       "zipf": row["zipf"], "sourcePhonetic": row["phonetic"],
                                       "sourceMeaning": row["meaning"]})
    new_ids = [x["id"] for x in dynamic_candidates] + [x["id"] for x in csv_candidates]
    evidence_manifest = [
        {"id": item["id"], "word": item["word"], "evidence": item["evidence"]}
        for item in dynamics
    ]
    return {
        "schemaVersion": 1, "packageId": f"wordwatch-{target.isoformat()}",
        "packageSchemaVersion": 2,
        "date": target.isoformat(), "timezone": "Asia/Shanghai", "contentMode": "ai-authored",
        "catalogSha256": catalog.get("catalogSha256"), "newWordLimit": LIMIT,
        "progressAvailable": progress_available,
        "progressCutoffAt": cutoff.isoformat(timespec="seconds"),
        "candidateLimit": MAX_NEW_CANDIDATES,
        "knownSkippedIds": sorted(known), "reviewsDue": due_rows,
        "dynamicCandidates": dynamic_candidates, "csvCandidates": csv_candidates,
        "newWordIds": new_ids, "dynamicEvidenceManifest": evidence_manifest,
        "instructions": [
            "输出日报包 schemaVersion=2、revision=1、kind=full、requiredCapabilities 至少包含 plain-text；choice/input/recall、notes、reviewPolicy/reviewMode 按实际内容分别声明对应能力。",
            "复习词按 reviewsDue 次序原样引用 ID，不得推测、改写学习状态。",
            f"新词先 dynamicCandidates，再按 rank 升序使用 csvCandidates；两类候选合计最多 {MAX_WORDS} 个，newWordLimit 默认 {LIMIT}，可按内容把包额度设为其他非负整数或 -1 表示全部候选。",
            "progressAvailable=false 表示状态未知，不表示候选词都不认识；候选仅供选择，已认识可跳过。",
            "动态词只能使用 dynamicEvidenceManifest 中完全一致的 dyn: ID、word 和 evidence；无证据不得生成动态词。",
            "每词写简洁中文义、音标、例句和主动提取题；默认用 choice 与 recall，不要求手表打字；仅在合适时可选 input 并提供 acceptedAnswers，recall 用 answer，短同义答案可放入 acceptedAnswers。",
            "可选 notes 用 word-notes-v1；可选 reviewPolicy/reviewMode 用 review-policy-v1；reviewMode 仅 question 或 self-recall。",
            "干扰项合理且互不重复，correctIndex 对应真实答案；不编造词根或词源。",
        ],
    }


def load_catalog(path: Path) -> dict[str, Any]:
    value = read_json(path)
    if not isinstance(value, dict) or value.get("schemaVersion") != 1:
        raise ContentError("catalog.json 无效")
    if not re.fullmatch(r"[0-9a-f]{64}", str(value.get("catalogSha256", ""))):
        raise ContentError("catalog.json 缺少哈希")
    return value


def _validate_publish_snapshots(watch_words: Path) -> None:
    """Require bootstrap-safe full snapshots before the private repository can be pushed."""
    catalog_path = watch_words / "catalog.json"
    if catalog_path.is_symlink() or not catalog_path.is_file():
        raise ContentError("发布前必须有普通文件 catalog.json")
    catalog = load_catalog(catalog_path)
    ai_dir = watch_words / "ai-input"
    if ai_dir.is_symlink() or (ai_dir.exists() and not ai_dir.is_dir()):
        raise ContentError("ai-input 必须是仓库内普通目录")
    daily = watch_words / "daily"
    if daily.is_symlink():
        raise ContentError("daily 目录不能是符号链接")
    candidates = [(watch_words / "current.json", None)]
    if daily.is_dir():
        for path in sorted(daily.glob("*.json")):
            if path.is_symlink() or not path.is_file():
                raise ContentError("daily 包只能是普通文件")
            name = path.stem
            try:
                package_day = date.fromisoformat(name)
            except ValueError as exc:
                raise ContentError(f"daily 包文件名日期无效：{path.name}") from exc
            if package_day.isoformat() != name:
                raise ContentError(f"daily 包文件名日期无效：{path.name}")
            candidates.append((path, name))

    for path, expected_date in candidates:
        if path.is_symlink() or not path.is_file():
            raise ContentError(f"发布快照不存在或不是普通文件：{path.name}")
        package = read_json(path)
        if not isinstance(package, dict):
            raise ContentError(f"发布快照必须是对象：{path.name}")
        if expected_date is not None and package.get("date") != expected_date:
            raise ContentError(f"daily 包日期与文件名不一致：{path.name}")
        if package.get("schemaVersion") == 2 and package.get("kind") != "full":
            raise ContentError(f"发布快照必须是完整包；先用基线物化 delta：{path.name}")
        # Validate the date before using it to construct an evidence-manifest path.
        validate_package(package, catalog, require_dynamic_evidence=False)
        ai_path = ai_dir / f"{package['date']}.json"
        if ai_path.is_symlink():
            raise ContentError(f"AI input 不能是符号链接：{ai_path.name}")
        ai_input = read_json(ai_path) if ai_path.is_file() else None
        validate_package(package, catalog, ai_input)


def git(args: list[str], cwd: Path) -> subprocess.CompletedProcess[str]:
    return subprocess.run(["git", *args], cwd=str(cwd), capture_output=True, text=True,
                          encoding="utf-8", errors="replace", check=False, shell=False)


def build_package_index(watch_words: Path) -> dict[str, Any]:
    """Build an index for the latest 14 synthetic-or-published daily package files."""
    root = watch_words.resolve()
    daily = root / "daily"
    if daily.is_symlink():
        raise ContentError("daily 目录不能是符号链接")
    entries: list[tuple[date, dict[str, str]]] = []
    if daily.is_dir():
        for path in daily.glob("????-??-??.json"):
            if path.is_symlink() or not path.is_file():
                raise ContentError("daily 包只能是普通文件")
            name = path.name[:-5]
            try:
                package_day = date.fromisoformat(name)
            except ValueError as exc:
                raise ContentError(f"daily 包文件名日期无效：{path.name}") from exc
            if package_day.isoformat() != name:
                raise ContentError(f"daily 包文件名日期无效：{path.name}")
            try:
                payload = json.loads(path.read_bytes())
            except (OSError, json.JSONDecodeError) as exc:
                raise ContentError(f"daily 包无法解析：{path.name}") from exc
            if not isinstance(payload, dict) or payload.get("date") != name:
                raise ContentError(f"daily 包日期与文件名不一致：{path.name}")
            digest = hashlib.sha256(path.read_bytes()).hexdigest()
            entries.append((package_day, {"path": f"daily/{path.name}", "sha256": digest}))
    latest = sorted(entries, key=lambda item: item[0], reverse=True)[:14]
    return {"schemaVersion": 1,
            "packages": [entry for _, entry in sorted(latest, key=lambda item: item[0])]}


def _validated_origin_push_url(root: Path) -> str:
    result = git(["remote", "get-url", "--push", "--all", "origin"], root)
    urls = [line.strip() for line in result.stdout.splitlines() if line.strip()]
    if result.returncode or len(urls) != 1:
        raise ContentError("origin 必须配置唯一 push URL")
    value = urls[0]
    if BAD_CONTROL.search(value) or any(ch.isspace() for ch in value):
        raise ContentError("origin push URL 格式无效")
    if value.startswith("https://") or value.startswith("ssh://"):
        try:
            parsed = urlsplit(value)
            hostname = parsed.hostname
            # SSH's conventional `git@host` user is not a credential; passwords are forbidden.
            valid_user = parsed.username is None if parsed.scheme == "https" else parsed.password is None
            valid_scheme = parsed.scheme == "https" or parsed.scheme == "ssh"
            if (not valid_scheme or not hostname or not parsed.path or parsed.query or parsed.fragment
                    or parsed.password is not None or not valid_user):
                raise ValueError
            _ = parsed.port
        except ValueError as exc:
            raise ContentError("origin push URL 格式无效或含凭据") from exc
    elif not re.fullmatch(r"(?:[A-Za-z0-9._-]+@)?[A-Za-z0-9.-]+:[A-Za-z0-9_./-]+", value):
        raise ContentError("origin push URL 必须是 HTTPS 或 SSH URL")
    return value


def publish(data_root: Path, message: str | None) -> None:
    root = data_root.resolve()
    watch_words = root / "watch-words"
    if not watch_words.is_dir():
        raise ContentError("私有 watch-words 目录不存在")
    _validate_publish_snapshots(watch_words)
    top = git(["rev-parse", "--show-toplevel"], root)
    if top.returncode or Path(top.stdout.strip()).resolve() != root:
        raise ContentError("data-root 必须是私有数据仓库根目录")
    branch = git(["symbolic-ref", "--quiet", "--short", "HEAD"], root)
    branch_name = branch.stdout.strip()
    if branch.returncode or not branch_name:
        raise ContentError("当前 HEAD 不是可发布的分支")
    if git(["check-ref-format", "--branch", branch_name], root).returncode:
        raise ContentError("当前分支名无效")
    upstream = git(["rev-parse", "--abbrev-ref", "--symbolic-full-name", "@{u}"], root)
    if upstream.returncode or upstream.stdout.strip() != f"origin/{branch_name}":
        raise ContentError("当前分支 upstream 必须是 origin 的同名分支")
    ahead = git(["rev-list", "--count", f"origin/{branch_name}..HEAD"], root)
    if ahead.returncode or ahead.stdout.strip() != "0":
        raise ContentError("本地有未发布提交或无法确认远端基线；拒绝扩大推送范围")
    push_url = _validated_origin_push_url(root)
    staged = git(["diff", "--cached", "--name-only", "-z"], root)
    if staged.returncode or staged.stdout:
        raise ContentError("暂存区已有变更；为保护其他工作，本命令不继续")
    write_json(watch_words / "index.json", build_package_index(watch_words))
    if git(["add", "--", "watch-words"], root).returncode:
        raise ContentError("git add 失败；原始错误已省略以保护凭据")
    staged = git(["diff", "--cached", "--name-only", "-z"], root)
    paths = [p.replace("\\", "/") for p in staged.stdout.split("\0") if p]
    if staged.returncode or any(p != "watch-words" and not p.startswith("watch-words/") for p in paths):
        raise ContentError("暂存范围检查失败；拒绝提交")
    if not paths:
        print("watch-words 无变更；未创建空提交。")
        return
    commit_message = message or f"Update watch-words content {date.today().isoformat()}"
    if len(commit_message) > 160 or BAD_CONTROL.search(commit_message):
        raise ContentError("提交说明过长或含控制字符")
    if git(["commit", "-m", commit_message, "--only", "--", "watch-words"], root).returncode:
        raise ContentError("git commit 失败；原始错误已省略以保护凭据")
    names = git(["show", "--format=", "--name-only", "-z", "HEAD"], root)
    committed_paths = [p.replace("\\", "/") for p in names.stdout.split("\0") if p]
    if names.returncode or any(p != "watch-words" and not p.startswith("watch-words/") for p in committed_paths):
        raise ContentError("最近提交范围核验失败；未执行推送")
    if git(["push", "--", push_url, f"HEAD:refs/heads/{branch_name}"], root).returncode:
        raise ContentError("本地提交已完成，但 push 失败；检查 GCM 登录和网络，不要复制令牌到命令行")
    print(f"已发布 watch-words 范围，共 {len(committed_paths)} 个文件；未读取、保存或打印令牌。")


def parser() -> argparse.ArgumentParser:
    root = argparse.ArgumentParser(description="WordWatch 内容导入、生成输入、校验和私有发布")
    sub = root.add_subparsers(dest="command", required=True)
    imp = sub.add_parser("import-csv", help="完整导入唯一 CSV 并显式记录异常")
    imp.add_argument("csv", type=Path)
    imp.add_argument("--output-root", type=Path, default=PRIVATE)
    imp.add_argument("--expected-rows", type=int, default=4237)
    check = sub.add_parser("validate-package", help="验证日报包和 CSV 对应关系")
    check.add_argument("package", type=Path)
    check.add_argument("--catalog", type=Path, default=PRIVATE / "catalog.json")
    check.add_argument("--ai-input", type=Path, help="核验 dyn: ID、词形和 evidence manifest")
    check.add_argument("--base", type=Path, help="校验 delta/full revision 与现有包的衔接")
    check.add_argument("--materialized-output", type=Path,
                       help="把已校验的完整结果写为 full 快照；delta 必须同时传 --base")
    check.add_argument("--require-generated-limit", action="store_true",
                       help=f"仅用于新生成包的验收，要求 newWordLimit={LIMIT}")
    prep = sub.add_parser("prepare-ai-input", help="依据真实进度生成私有 AI 输入")
    prep.add_argument("--catalog", type=Path, default=PRIVATE / "catalog.json")
    prep.add_argument("--progress", type=Path, default=PRIVATE)
    prep.add_argument("--date", type=date.fromisoformat, default=datetime.now(SHANGHAI).date())
    prep.add_argument("--cutoff-at", type=parse_cutoff_at,
                      help="成功同步完成时间，带时区的 ISO-8601；缺省记录本次生成时间")
    prep.add_argument("--output", type=Path)
    pub = sub.add_parser("publish", help="只发布 watch-words；使用已登录 GCM")
    pub.add_argument("--data-root", type=Path, default=PRIVATE.parent)
    pub.add_argument("--message")
    return root


def parse_cutoff_at(value: str) -> datetime:
    try:
        parsed = datetime.fromisoformat(value)
    except ValueError as exc:
        raise argparse.ArgumentTypeError("cutoff-at 必须是带时区的 ISO-8601 时间") from exc
    if parsed.tzinfo is None or parsed.utcoffset() is None:
        raise argparse.ArgumentTypeError("cutoff-at 必须包含时区偏移")
    return parsed.astimezone(SHANGHAI)


def main(argv: list[str] | None = None) -> int:
    args = parser().parse_args(argv)
    try:
        if args.command == "import-csv":
            result = import_csv(args.csv, args.output_root, args.expected_rows)
            print(json.dumps(result, ensure_ascii=False, indent=2))
            return 0 if result["exceptionRows"] == 0 and result["globalExceptions"] == 0 else 2
        if args.command == "validate-package":
            pack = read_json(args.package)
            catalog = load_catalog(args.catalog) if args.catalog.exists() else None
            ai_input = read_json(args.ai_input) if args.ai_input else None
            result = validate_package(pack, catalog, ai_input,
                                      require_generated_limit=args.require_generated_limit)
            applied = None
            if pack.get("schemaVersion") == 2 and pack.get("kind") == "delta":
                if args.base is None:
                    raise ContentError("delta package validation requires --base")
                applied = apply_package(read_json(args.base), pack, catalog, ai_input)
                result["appliedRevision"] = revision(applied)
            elif args.base is not None:
                applied = apply_package(read_json(args.base), pack, catalog, ai_input)
                result["appliedRevision"] = revision(applied)
            if args.materialized_output is not None:
                output_path = args.materialized_output.resolve()
                protected_paths = {args.package.resolve()}
                if args.base is not None:
                    protected_paths.add(args.base.resolve())
                if output_path in protected_paths:
                    raise ContentError("materialized-output 必须独立于输入包和基线")
                write_json(output_path, applied if applied is not None else pack)
                result["materializedOutput"] = str(output_path)
            print(json.dumps({"valid": True, **result}, ensure_ascii=False))
            return 0
        if args.command == "prepare-ai-input":
            catalog = load_catalog(args.catalog)
            progress = args.progress if args.progress.exists() else None
            events, dynamic, cards, available = load_progress(progress)
            result = build_ai_input(catalog, events, dynamic, cards, available, args.date, args.cutoff_at)
            output = args.output or PRIVATE / "ai-input" / f"{args.date.isoformat()}.json"
            write_json(output, result)
            print(json.dumps({"output": str(output), "progressAvailable": available,
                              "progressCutoffAt": result["progressCutoffAt"],
                              "reviewsDue": len(result["reviewsDue"]),
                              "dynamicCandidates": len(result["dynamicCandidates"]),
                              "csvCandidates": len(result["csvCandidates"]),
                              "catalogSha256": result["catalogSha256"]}, ensure_ascii=False, indent=2))
            return 0
        if args.command == "publish":
            publish(args.data_root, args.message)
            return 0
    except (ContentError, OSError, csv.Error, json.JSONDecodeError) as exc:
        print(f"失败：{exc}", file=sys.stderr)
        return 2
    return 2


if __name__ == "__main__":
    raise SystemExit(main())
