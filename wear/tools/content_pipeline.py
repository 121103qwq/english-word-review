#!/usr/bin/env python3
"""Standard-library WordWatch content pipeline. No external AI calls or credentials."""
from __future__ import annotations

import argparse
import csv
import hashlib
import json
import math
import os
import re
import shutil
import subprocess
import sys
from datetime import date, datetime, timedelta, timezone
from pathlib import Path
from typing import Any

COLUMNS = ("rank", "word", "phonetic", "meaning", "zipf")
LIMIT = 15
MAX_NEW_CANDIDATES = 45
SHANGHAI = timezone(timedelta(hours=8), "Asia/Shanghai")
INTERVALS = (1, 3, 7, 14, 30, 60)
HERE = Path(__file__).resolve()
PRIVATE = HERE.parents[3] / "data" / "watch-words"
BAD_CONTROL = re.compile(r"[\x00-\x08\x0b\x0c\x0e-\x1f\x7f]")
CSV_ID = re.compile(r"^csv:(\d{4,})$")
DYN_ID = re.compile(r"^dyn:[A-Za-z0-9._:-]{1,120}$")


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


def check_text(value: Any, label: str, maximum: int = 500) -> str:
    if not isinstance(value, str) or not value.strip():
        raise ContentError(f"{label} 必须是非空字符串")
    if len(value) > maximum or BAD_CONTROL.search(value):
        raise ContentError(f"{label} 过长或含控制字符")
    return value


def validate_package(pack: Any, catalog: dict[str, Any] | None = None) -> dict[str, int]:
    if not isinstance(pack, dict) or pack.get("schemaVersion") != 1:
        raise ContentError("日报包必须是 schemaVersion 1 对象")
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
    if not re.fullmatch(r"[0-9a-f]{64}", pack["catalogSha256"]):
        raise ContentError("catalogSha256 必须是 SHA-256")
    if pack.get("newWordLimit") != LIMIT:
        raise ContentError(f"newWordLimit 必须为 {LIMIT}")
    words = pack.get("words")
    if not isinstance(words, list):
        raise ContentError("words 必须是数组")
    by_id: dict[str, dict[str, Any]] = {}
    question_ids: set[str] = set()
    for i, word in enumerate(words):
        where = f"words[{i}]"
        if not isinstance(word, dict):
            raise ContentError(f"{where} 必须是对象")
        wid = check_text(word.get("id"), where + ".id", 140)
        match = CSV_ID.fullmatch(wid)
        if not (match or DYN_ID.fullmatch(wid)):
            raise ContentError(f"{where}.id 格式无效")
        if wid in by_id:
            raise ContentError(f"重复 word id：{wid}")
        by_id[wid] = word
        check_text(word.get("word"), where + ".word", 100)
        check_text(word.get("phonetic"), where + ".phonetic", 160)
        check_text(word.get("meaning"), where + ".meaning", 240)
        if type(word.get("rank")) is not int or word["rank"] < 1:
            raise ContentError(f"{where}.rank 必须是正整数")
        if match and int(match.group(1)) != word["rank"]:
            raise ContentError(f"{where}.id 与 rank 不匹配")
        if word.get("generatedBy") != "AI":
            raise ContentError(f"{where}.generatedBy 必须是 AI")
        for field in ("senses", "collocations"):
            values = word.get(field)
            if not isinstance(values, list) or not values:
                raise ContentError(f"{where}.{field} 至少一项")
            for item in values:
                check_text(item, where + "." + field, 240)
        examples = word.get("examples")
        if not isinstance(examples, list) or not examples:
            raise ContentError(f"{where}.examples 至少一条")
        for example in examples:
            if not isinstance(example, dict):
                raise ContentError(f"{where}.example 必须是对象")
            check_text(example.get("en"), where + ".example.en", 300)
            check_text(example.get("zh"), where + ".example.zh", 300)
        if "roots" in word:
            if not isinstance(word["roots"], list):
                raise ContentError(f"{where}.roots 必须是数组")
            for root in word["roots"]:
                check_text(root, where + ".root", 240)
        questions = word.get("questions")
        if not isinstance(questions, list):
            raise ContentError(f"{where}.questions 必须是数组")
        modes: set[str] = set()
        for q in questions:
            if not isinstance(q, dict):
                raise ContentError(f"{where}.question 必须是对象")
            qid = check_text(q.get("id"), where + ".question.id", 160)
            if qid in question_ids:
                raise ContentError(f"重复 question id：{qid}")
            question_ids.add(qid)
            mode = q.get("mode")
            if mode not in ("forward", "reverse", "rare") or mode in modes:
                raise ContentError(f"{where} question mode 无效或重复")
            modes.add(mode)
            check_text(q.get("prompt"), where + ".prompt", 300)
            options = q.get("options")
            if not isinstance(options, list) or not 2 <= len(options) <= 4:
                raise ContentError(f"{where}.options 必须有 2-4 项")
            clean = [check_text(item, where + ".option", 160) for item in options]
            if len({item.strip().casefold() for item in clean}) != len(clean):
                raise ContentError(f"{where}.options 有重复项")
            ci = q.get("correctIndex")
            if type(ci) is not int or not 0 <= ci < len(options):
                raise ContentError(f"{where}.correctIndex 越界或非整数")
            check_text(q.get("explanation"), where + ".explanation", 300)
        if not {"forward", "reverse"}.issubset(modes):
            raise ContentError(f"{where} 必须有 forward 和 reverse 题")
    new_ids, review_ids = pack.get("newWordIds"), pack.get("reviewWordIds")
    for label, ids in (("newWordIds", new_ids), ("reviewWordIds", review_ids)):
        if not isinstance(ids, list) or any(not isinstance(x, str) for x in ids):
            raise ContentError(f"{label} 必须是字符串 ID 数组")
        if len(ids) != len(set(ids)):
            raise ContentError(f"{label} 有重复 ID")
        if any(x not in by_id for x in ids):
            raise ContentError(f"{label} 引用了无内容的 ID")
    if set(new_ids) & set(review_ids):
        raise ContentError("新词与复习词 ID 重叠")
    if catalog is not None:
        if pack["catalogSha256"] != catalog.get("catalogSha256"):
            raise ContentError("日报包 catalogSha256 与当前私有底库不一致")
        lookup = {r["id"]: r for r in catalog.get("rows", [])
                  if r.get("status") == "ok" and isinstance(r.get("id"), str)}
        for wid, word in by_id.items():
            if wid.startswith("csv:"):
                source = lookup.get(wid)
                if source is None or source.get("word") != word["word"] or source.get("rank") != word["rank"]:
                    raise ContentError(f"{wid} 与 CSV 唯一底库不匹配或待核对")
    return {"words": len(words), "newCandidates": len(new_ids), "reviews": len(review_ids)}


def event_day(event: dict[str, Any]) -> date:
    at = event.get("at")
    if type(at) not in (int, float) or not math.isfinite(at):
        raise ContentError(f"事件 {event.get('id', '?')} 的 at 不是 epoch 毫秒")
    try:
        return datetime.fromtimestamp(at / 1000, SHANGHAI).date()
    except (OverflowError, OSError, ValueError) as exc:
        raise ContentError(f"事件 {event.get('id', '?')} 的 at 无效") from exc


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
    unique: dict[str, dict[str, Any]] = {}
    for item in events:
        eid = item.get("id")
        if not isinstance(eid, str) or not eid:
            raise ContentError("事件缺少 id")
        if eid in unique and unique[eid] != item:
            raise ContentError(f"事件 ID 内容冲突：{eid}")
        unique[eid] = item
    return list(unique.values()), dynamic, cards, True


def project_events(events: list[dict[str, Any]], as_of_ms: int | None = None) -> dict[str, dict[str, Any]]:
    if as_of_ms is None:
        as_of_ms = int(datetime.now(SHANGHAI).timestamp() * 1000)
    by_id: dict[str, dict[str, Any]] = {}
    for event in events:
        eid = event.get("id")
        if not isinstance(eid, str) or not eid:
            raise ContentError("事件缺少 id")
        if eid in by_id and by_id[eid] != event:
            raise ContentError(f"事件 ID 内容冲突：{eid}")
        by_id[eid] = event
    for event in by_id.values():
        event_day(event)
    ordered = sorted(by_id.values(), key=lambda e: (e.get("at", 0), str(e.get("deviceId", "")),
                                                    int(e.get("seq", 0)) if str(e.get("seq", 0)).lstrip("-").isdigit() else 0,
                                                    str(e["id"])))
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
        if attempt == "primary" and result != "known":
            state["known"] = False
        if result == "correct":
            if mode == "recall":
                state["selfRemember"] += 1
            elif assisted:
                state["assistedRight"] += 1
            else:
                state["objectiveRight"] += 1
        elif result == "wrong":
            if mode == "recall":
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
        if result == "correct" and assisted:
            if state["dueAt"] is None:
                state["stage"], state["dueAt"] = 0, (day + timedelta(days=1)).isoformat()
            continue
        if result in ("wrong", "forgot", "hard"):
            state["stage"], state["dueAt"] = 0, (day + timedelta(days=1)).isoformat()
        elif result in ("correct", "remember"):
            if state["dueAt"] is None:
                state["stage"], state["dueAt"] = 0, (day + timedelta(days=1)).isoformat()
            elif due is not None and day >= due:
                state["stage"] = min(state["stage"] + 1, len(INTERVALS) - 1)
                state["dueAt"] = (day + timedelta(days=INTERVALS[state["stage"]])).isoformat()
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
            if stage is not None and (type(stage) is not int or not 0 <= stage < len(INTERVALS)):
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
    return {
        "schemaVersion": 1, "packageId": f"wordwatch-{target.isoformat()}",
        "date": target.isoformat(), "timezone": "Asia/Shanghai", "contentMode": "ai-authored",
        "catalogSha256": catalog.get("catalogSha256"), "newWordLimit": LIMIT,
        "progressAvailable": progress_available,
        "progressCutoffAt": cutoff.isoformat(timespec="seconds"),
        "candidateLimit": MAX_NEW_CANDIDATES,
        "knownSkippedIds": sorted(known), "reviewsDue": due_rows,
        "dynamicCandidates": dynamic_candidates, "csvCandidates": csv_candidates,
        "newWordIds": new_ids,
        "instructions": [
            "复习词按 reviewsDue 次序原样引用 ID，不得推测、改写学习状态。",
            "新词先 dynamicCandidates，再按 rank 升序使用 csvCandidates；两类候选合计最多 45 个，每日最多计入 15 个新词。",
            "progressAvailable=false 表示状态未知，不表示候选词都不认识；候选仅供选择，已认识可跳过。",
            "动态词只能使用输入里有用户 evidence 的 dyn: ID 与 word；无材料时不得生成动态词。",
            "每词写简洁中文义、可靠音标、至少一个常用搭配和一条英中例句、forward/reverse 各一题；有价值时加 rare 题。",
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


def git(args: list[str], cwd: Path) -> subprocess.CompletedProcess[str]:
    return subprocess.run(["git", *args], cwd=str(cwd), capture_output=True, text=True,
                          encoding="utf-8", errors="replace", check=False, shell=False)


def publish(data_root: Path, message: str | None) -> None:
    root = data_root.resolve()
    if not (root / "watch-words").is_dir():
        raise ContentError("私有 watch-words 目录不存在")
    top = git(["rev-parse", "--show-toplevel"], root)
    if top.returncode or Path(top.stdout.strip()).resolve() != root:
        raise ContentError("data-root 必须是私有数据仓库根目录")
    upstream = git(["rev-parse", "--abbrev-ref", "--symbolic-full-name", "@{u}"], root)
    if upstream.returncode or not upstream.stdout.strip():
        raise ContentError("当前分支没有 upstream")
    ahead = git(["rev-list", "--count", f"{upstream.stdout.strip()}..HEAD"], root)
    if ahead.returncode or ahead.stdout.strip() != "0":
        raise ContentError("本地有未发布提交或无法确认远端基线；拒绝扩大推送范围")
    remote = git(["remote", "get-url", "--push", "origin"], root)
    if remote.returncode or re.search(r"://[^/@\s]+:[^/@\s]+@", remote.stdout.strip()):
        raise ContentError("origin push 配置无效或含内嵌凭据；请使用已登录 GCM")
    staged = git(["diff", "--cached", "--name-only", "-z"], root)
    if staged.returncode or staged.stdout:
        raise ContentError("暂存区已有变更；为保护其他工作，本命令不继续")
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
    if git(["push"], root).returncode:
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
            print(json.dumps({"valid": True, **validate_package(pack, catalog)}, ensure_ascii=False))
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
