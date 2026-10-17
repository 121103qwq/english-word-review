import csv
import copy
import contextlib
import datetime as dt
import hashlib
import importlib.util
import io
import json
import tempfile
import unittest
import uuid
from pathlib import Path

MODULE_PATH = Path(__file__).with_name("content_pipeline.py")
SPEC = importlib.util.spec_from_file_location("content_pipeline", MODULE_PATH)
pipeline = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(pipeline)
VECTOR_PATH = MODULE_PATH.parents[1] / "app" / "src" / "test" / "resources" / "package-contract-vectors.json"
CONTRACT_VECTORS = json.loads(VECTOR_PATH.read_text(encoding="utf-8"))


def sample_word(word_id="csv:0001", word="alpha", rank=1):
    return {
        "id": word_id, "word": word, "rank": rank, "phonetic": "/ˈælfə/",
        "meaning": "示例词", "generatedBy": "AI", "senses": ["示例义"], "collocations": ["an example"],
        "examples": [{"en": "Alpha is first.", "zh": "Alpha 排在第一。"}],
        "questions": [
            {"id": word_id + ":f", "mode": "forward", "prompt": "Alpha 的意思？",
             "options": ["乙", "示例义", "丙"], "correctIndex": 1, "explanation": "对应示例义。"},
            {"id": word_id + ":r", "mode": "reverse", "prompt": "“示例义”对应哪个词？",
             "options": ["beta", "alpha", "gamma"], "correctIndex": 1, "explanation": "答案是 alpha。"},
        ],
    }


def sample_package(words=None):
    words = words if words is not None else [sample_word()]
    return {
        "schemaVersion": 1, "packageId": "wordwatch-2026-10-13", "date": "2026-10-13",
        "timezone": "Asia/Shanghai", "contentMode": "ai-authored", "catalogSha256": "a" * 64,
        "newWordLimit": 30, "words": words, "newWordIds": [words[0]["id"]], "reviewWordIds": [],
    }


def epoch_ms(day, hour=12):
    moment = dt.datetime.fromisoformat(day).replace(hour=hour, tzinfo=pipeline.SHANGHAI)
    return int(moment.timestamp() * 1000)


def event(number, word_id, at, mode, result, *, seq=None, device="watch", attempt="primary",
          assisted=False, is_review=None, **extra):
    value = {
        "id": str(uuid.UUID(int=number)), "type": "answer", "wordId": word_id, "at": at,
        "deviceId": device, "seq": number if seq is None else seq, "packageId": "synthetic-daily",
        "mode": mode, "result": result, "attempt": attempt, "assisted": assisted,
    }
    if is_review is not None:
        value["isReview"] = is_review
    value.update(extra)
    return value


class CsvIntegrityTests(unittest.TestCase):
    def test_import_preserves_all_rows_hash_and_source_columns(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            source = root / "catalog.csv"
            text = "\ufeffrank,word,phonetic,meaning,zipf\n2,beta,/ˈbeɪtə/,乙,5.2\n1,Alpha,/ˈælfə/,甲,5.9\n3,cat,/kæt/,猫,5.1\n"
            raw = text.encode("utf-8")
            source.write_bytes(raw)
            output = root / "watch-words"
            result = pipeline.import_csv(source, output, expected_rows=3)
            catalog = json.loads((output / "catalog.json").read_text(encoding="utf-8"))
            self.assertEqual(result["sourceSha256"], hashlib.sha256(raw).hexdigest())
            self.assertEqual(result["sourceRows"], 3)
            self.assertEqual(result["usableRows"], 3)
            self.assertEqual((output / "source.csv").read_bytes(), raw)
            self.assertEqual([r["rank"] for r in catalog["rows"]], [2, 1, 3])
            self.assertEqual([r["word"] for r in catalog["rows"]], ["beta", "Alpha", "cat"])
            self.assertEqual([r["rawFields"]["zipf"] for r in catalog["rows"]], ["5.2", "5.9", "5.1"])
            self.assertEqual([r["id"] for r in catalog["rows"]], ["csv:0002", "csv:0001", "csv:0003"])

    def test_duplicate_rank_records_are_reported_without_dropping_rows(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            source = root / "bad.csv"
            source.write_text(
                "rank,word,phonetic,meaning,zipf\n1,alpha,/a/,甲,5\n1,beta,/b/,乙,4\n",
                encoding="utf-8",
            )
            output = root / "private"
            result = pipeline.import_csv(source, output, expected_rows=2)
            catalog = json.loads((output / "catalog.json").read_text(encoding="utf-8"))
            exceptions = json.loads((output / "exceptions.json").read_text(encoding="utf-8"))
            self.assertEqual(result["sourceRows"], 2)
            self.assertEqual(len(catalog["rows"]), 2)
            self.assertEqual(result["exceptionRows"], 2)
            self.assertTrue(all(row["status"] == "待核对" for row in exceptions["rows"]))

    def test_export_identifiers_are_preserved_and_require_manual_mapping(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            source = root / "catalog.csv"
            source.write_text(
                "rank,word,phonetic,meaning,zipf\n"
                "1,ordinary,/a/,普通,5\n"
                "2,sample_token_1,/a/,示例,0\n"
                "3,export_pair_marker,/b/,示例,0\n"
                "4,synthetic_2,/c/,示例,0\n",
                encoding="utf-8",
            )
            output = root / "private"
            result = pipeline.import_csv(source, output, expected_rows=4)
            catalog = json.loads((output / "catalog.json").read_text(encoding="utf-8"))
            exceptions = json.loads((output / "exceptions.json").read_text(encoding="utf-8"))
            flagged = [row for row in catalog["rows"] if row["status"] == "待核对"]
            self.assertEqual(result["sourceRows"], 4)
            self.assertEqual(result["usableRows"], 1)
            self.assertEqual(result["exportTokenRows"], 3)
            self.assertEqual([row["id"] for row in flagged], ["csv:0002", "csv:0003", "csv:0004"])
            self.assertEqual([row["word"] for row in flagged],
                             ["sample_token_1", "export_pair_marker", "synthetic_2"])
            self.assertEqual([row["rawFields"]["word"] for row in flagged],
                             ["sample_token_1", "export_pair_marker", "synthetic_2"])
            self.assertTrue(all(row["exceptionType"] == "export_token_suspect" for row in flagged))
            self.assertTrue(all(row["rank"] == int(row["id"].split(":")[1]) and
                                row["word"] == row["rawFields"]["word"] and
                                row["mapping"] is None and row["mappingStatus"] == "待人工 mapping；不得猜测"
                                for row in exceptions["rows"]))


class PackageValidationTests(unittest.TestCase):
    def test_legacy_limits_are_compatible_and_new_template_policy_is_separate(self):
        pipeline.validate_package(sample_package())
        for limit in (0, 15, 30, 10000):
            pack = sample_package()
            pack["newWordLimit"] = limit
            pipeline.validate_package(pack)
        for limit in (-1, 10001):
            pack = sample_package()
            pack["newWordLimit"] = limit
            with self.assertRaisesRegex(pipeline.ContentError, "newWordLimit"):
                pipeline.validate_package(pack)
        pipeline.validate_package(sample_package(), require_generated_limit=True)
        with self.assertRaisesRegex(pipeline.ContentError, "newWordLimit"):
            pipeline.validate_package({**sample_package(), "newWordLimit": 29},
                                      require_generated_limit=True)

    def test_duplicate_word_ids_are_rejected(self):
        duplicate = sample_word()
        with self.assertRaisesRegex(pipeline.ContentError, "重复 word id"):
            pipeline.validate_package(sample_package([sample_word(), duplicate]))

    def test_wrong_answer_index_is_rejected(self):
        pack = sample_package()
        pack["words"][0]["questions"][0]["correctIndex"] = 3
        with self.assertRaisesRegex(pipeline.ContentError, "correctIndex"):
            pipeline.validate_package(pack)

    def test_shared_v2_contract_full_and_delta_vectors(self):
        full = copy.deepcopy(CONTRACT_VECTORS["full"])
        delta = copy.deepcopy(CONTRACT_VECTORS["delta"])
        self.assertEqual(pipeline.validate_package(full)["words"], 2)
        self.assertEqual(pipeline.revision(full), 1)
        applied = pipeline.apply_package(full, delta)
        self.assertEqual(pipeline.revision(applied), 2)
        self.assertEqual(applied["kind"], "full")
        self.assertEqual([word["id"] for word in applied["words"]], ["csv:0001"])
        self.assertEqual(applied["withdrawnWordIds"], ["csv:0002"])
        self.assertEqual(applied["x-package-optional"], {"retained": True})
        self.assertEqual(applied["x-delta-optional"], {"retained": True})
        self.assertEqual(applied["words"][0]["x-word-optional"], {"retained": True})
        self.assertEqual(pipeline.apply_package(applied, delta), applied)

    def test_v2_rejects_unknown_required_feature_and_bad_revision_base(self):
        full = copy.deepcopy(CONTRACT_VECTORS["full"])
        full["requiredCapabilities"].append("future-feature-v9")
        with self.assertRaisesRegex(pipeline.ContentError, "unsupported required capability"):
            pipeline.validate_package(full)
        delta = copy.deepcopy(CONTRACT_VECTORS["delta"])
        delta["baseRevision"] = 0
        with self.assertRaisesRegex(pipeline.ContentError, "baseRevision"):
            pipeline.validate_package(delta)

    def test_v2_revision_cannot_rebind_or_silently_remove_a_word(self):
        base = copy.deepcopy(CONTRACT_VECTORS["full"])
        delta = copy.deepcopy(CONTRACT_VECTORS["delta"])
        delta["words"][0]["word"] = "gamma"
        with self.assertRaisesRegex(pipeline.ContentError, "identity"):
            pipeline.apply_package(base, delta)
        replacement = copy.deepcopy(base)
        replacement["revision"] = 2
        replacement["words"] = replacement["words"][:1]
        replacement["newWordIds"] = ["csv:0001"]
        with self.assertRaisesRegex(pipeline.ContentError, "withdrawn explicitly"):
            pipeline.apply_package(base, replacement)

    def test_dynamic_package_requires_matching_ai_evidence_manifest(self):
        package = sample_package([sample_word("dyn:synthetic-1", "alpha", 1)])
        package["newWordIds"] = ["dyn:synthetic-1"]
        with self.assertRaisesRegex(pipeline.ContentError, "--ai-input"):
            pipeline.validate_package(package)
        ai_input = {
            "packageId": package["packageId"], "date": package["date"],
            "catalogSha256": package["catalogSha256"],
            "dynamicCandidates": [{"id": "dyn:synthetic-1", "word": "alpha",
                                   "evidence": {"kind": "synthetic-fixture"}}],
            "reviewsDue": [{"id": "csv:0009", "word": "synthetic-review", "source": "csv"}],
            "dynamicEvidenceManifest": [{"id": "dyn:synthetic-1", "word": "alpha",
                                          "evidence": {"kind": "synthetic-fixture"}}],
        }
        pipeline.validate_package(package, ai_input=ai_input)
        ai_input["dynamicEvidenceManifest"][0]["word"] = "beta"
        with self.assertRaisesRegex(pipeline.ContentError, "ID 或词形"):
            pipeline.validate_package(package, ai_input=ai_input)

    def test_dynamic_new_word_must_be_an_exact_evidenced_candidate(self):
        package = sample_package([sample_word("dyn:synthetic-1", "alpha", 1)])
        package["newWordIds"] = ["dyn:synthetic-1"]
        ai_input = {
            "packageId": package["packageId"], "date": package["date"],
            "catalogSha256": package["catalogSha256"],
            "dynamicCandidates": [{"id": "dyn:synthetic-2", "word": "alpha",
                                   "evidence": {"kind": "synthetic-fixture"}}],
            "reviewsDue": [],
            "dynamicEvidenceManifest": [{"id": "dyn:synthetic-1", "word": "alpha",
                                          "evidence": {"kind": "synthetic-fixture"}}],
        }
        with self.assertRaisesRegex(pipeline.ContentError, "候选"):
            pipeline.validate_package(package, ai_input=ai_input)
        ai_input["dynamicCandidates"] = [{"id": "dyn:synthetic-1", "word": "alpha",
                                          "evidence": {"kind": "different-source"}}]
        with self.assertRaisesRegex(pipeline.ContentError, "候选"):
            pipeline.validate_package(package, ai_input=ai_input)


class DailyRulesTests(unittest.TestCase):
    def test_due_then_dynamic_then_csv_and_known_or_learned_are_skipped(self):
        rows = [
            {"id": f"csv:{rank:04d}", "rank": rank, "word": f"word{rank}", "zipf": 5.0,
             "phonetic": "/wɜːd/", "meaning": "词", "status": "ok"}
            for rank in range(1, 20)
        ]
        catalog = {"catalogSha256": "b" * 64, "rows": rows}
        events = [
            event(1, "csv:0001", epoch_ms("2026-10-12"), "skip", "known"),
            event(2, "csv:0002", epoch_ms("2026-10-12"), "forward", "correct"),
            event(3, "csv:0003", epoch_ms("2026-10-13"), "forward", "correct"),
        ]
        dynamic = [{"id": "dyn:user-123", "word": "userword",
                    "evidence": {"source": "user-added", "at": "2026-10-13T10:00:00+08:00"}}]
        result = pipeline.build_ai_input(
            catalog, events, dynamic, {}, True, dt.date(2026, 10, 13),
            dt.datetime(2026, 10, 13, 23, 59, tzinfo=pipeline.SHANGHAI),
        )
        self.assertEqual([item["id"] for item in result["reviewsDue"]], ["csv:0002"])
        self.assertEqual(result["newWordIds"][0], "dyn:user-123")
        self.assertEqual(result["csvCandidates"][0]["id"], "csv:0004")
        self.assertEqual(result["newWordIds"][1:4], ["csv:0004", "csv:0005", "csv:0006"])
        self.assertNotIn("csv:0001", result["newWordIds"])
        self.assertNotIn("csv:0002", result["newWordIds"])
        self.assertNotIn("csv:0003", result["newWordIds"])
        self.assertEqual(result["newWordLimit"], 30)
        self.assertLessEqual(len(result["newWordIds"]), pipeline.MAX_WORDS)
        self.assertEqual(result["packageSchemaVersion"], 2)
        self.assertEqual(result["dynamicEvidenceManifest"][0]["id"], "dyn:user-123")

    def test_dynamic_word_without_user_evidence_is_rejected(self):
        with self.assertRaisesRegex(pipeline.ContentError, "用户添加证据"):
            pipeline.load_progress_value({
                "dynamicWords": [{"id": "dyn:record-123", "word": "invented"}]
            })

    def test_missing_progress_stays_unknown_and_does_not_create_dynamic_words(self):
        rows = [{"id": "csv:0001", "rank": 1, "word": "the", "zipf": 7.7,
                 "phonetic": "/ðə/", "meaning": "特指", "status": "ok"}]
        result = pipeline.build_ai_input(
            {"catalogSha256": "c" * 64, "rows": rows}, [], [], {}, False, dt.date(2026, 10, 13)
        )
        self.assertFalse(result["progressAvailable"])
        self.assertEqual(result["dynamicCandidates"], [])
        self.assertEqual(result["newWordIds"], ["csv:0001"])
        self.assertTrue(any("状态未知" in item for item in result["instructions"]))

    def test_rows_waiting_for_review_are_excluded_from_csv_candidates(self):
        rows = [
            {"id": "csv:0001", "rank": 1, "word": "first", "zipf": 5.0,
             "phonetic": "/f/", "meaning": "一", "status": "ok"},
            {"id": "csv:0002", "rank": 2, "word": "export_token", "zipf": 0.0,
             "phonetic": "/x/", "meaning": "待核对", "status": "待核对"},
            {"id": "csv:0003", "rank": 3, "word": "third", "zipf": 4.0,
             "phonetic": "/θ/", "meaning": "三", "status": "ok"},
        ]
        result = pipeline.build_ai_input(
            {"catalogSha256": "d" * 64, "rows": rows}, [], [], {}, False, dt.date(2026, 10, 13)
        )
        self.assertEqual([item["id"] for item in result["csvCandidates"]], ["csv:0001", "csv:0003"])
        self.assertNotIn("csv:0002", result["newWordIds"])

    def test_candidate_limit_is_shared_by_dynamic_and_csv_words(self):
        rows = [
            {"id": f"csv:{rank:04d}", "rank": rank, "word": f"word{rank}", "zipf": 5.0,
             "phonetic": "/w/", "meaning": "词", "status": "ok"}
            for rank in range(1, 51)
        ]
        dynamics = [
            {"id": f"dyn:user-{i}", "word": f"dynword{i}",
             "evidence": {"source": "user-added", "at": "2026-10-12T10:00:00+08:00"}}
            for i in range(3)
        ]
        result = pipeline.build_ai_input(
            {"catalogSha256": "e" * 64, "rows": rows}, [], dynamics, {}, True,
            dt.date(2026, 10, 13), dt.datetime(2026, 10, 12, 22, tzinfo=pipeline.SHANGHAI)
        )
        self.assertEqual(len(result["dynamicCandidates"]), 3)
        self.assertEqual(len(result["csvCandidates"]), 50)
        self.assertEqual(len(result["newWordIds"]), 53)
        self.assertEqual(result["newWordIds"][:3], ["dyn:user-0", "dyn:user-1", "dyn:user-2"])

    def test_assisted_correct_does_not_advance_review_and_future_events_are_ignored(self):
        events = [
            event(11, "csv:0001", epoch_ms("2026-10-01"), "forward", "correct"),
            event(12, "csv:0001", epoch_ms("2026-10-02"), "forward", "correct", assisted=True),
            event(13, "csv:0001", epoch_ms("2026-10-03"), "forward", "wrong"),
            event(14, "csv:0002", epoch_ms("2026-10-01"), "skip", "known"),
            event(15, "csv:0002", epoch_ms("2026-10-02"), "forward", "wrong"),
            event(16, "csv:0003", epoch_ms("2026-10-03"), "forward", "correct"),
        ]
        states = pipeline.project_events(events, epoch_ms("2026-10-02"))
        assisted = states["csv:0001"]
        self.assertEqual(assisted["objectiveRight"], 1)
        self.assertEqual(assisted["assistedRight"], 1)
        self.assertEqual(assisted["stage"], 0)
        self.assertEqual(assisted["dueAt"], "2026-10-02")
        self.assertEqual(assisted["lastAt"], epoch_ms("2026-10-02"))
        resumed = states["csv:0002"]
        self.assertFalse(resumed["known"])
        self.assertEqual(resumed["stage"], 0)
        self.assertEqual(resumed["dueAt"], "2026-10-03")
        self.assertNotIn("csv:0003", states)

    def test_event_merge_deduplicates_across_sessions_and_rejects_sequence_conflicts(self):
        first = event(21, "csv:0001", epoch_ms("2026-10-13", 8), "forward", "correct",
                      device="watch-a", is_review=False, sessionId="session-a")
        replay = copy.deepcopy(first)
        second = event(22, "csv:0001", epoch_ms("2026-10-13", 9), "custom-mode", "correct",
                       device="watch-b", is_review=True, sessionId="session-b")
        merged = pipeline.merge_events([first], [replay, second])
        self.assertEqual(len(merged), 2)
        conflict = event(23, "csv:0002", epoch_ms("2026-10-13", 10), "forward", "wrong",
                         device="watch-a", seq=21)
        with self.assertRaisesRegex(pipeline.ContentError, "seq"):
            pipeline.merge_events([first], [conflict])

    def test_schedule_days_and_self_report_evidence_are_projected(self):
        first = event(31, "csv:0001", epoch_ms("2026-10-01"), "custom-recall", "remember",
                      evidence="self-report", scheduleDays=4)
        due = pipeline.project_events([first], epoch_ms("2026-10-03"))
        self.assertEqual(due["csv:0001"]["selfRemember"], 1)
        self.assertEqual(due["csv:0001"]["dueAt"], "2026-10-05")

    def test_explicit_evidence_overrides_legacy_recall_mode_semantics(self):
        objective = event(32, "csv:0001", epoch_ms("2026-10-01"), "recall", "correct",
                          evidence="independent-choice")
        states = pipeline.project_events([objective], epoch_ms("2026-10-01"))
        self.assertEqual(states["csv:0001"]["objectiveRight"], 1)
        self.assertEqual(states["csv:0001"]["selfRemember"], 0)

        legacy = event(33, "csv:0002", epoch_ms("2026-10-01"), "recall", "remember")
        pipeline.validate_event(legacy)
        legacy_objective = event(34, "csv:0002", epoch_ms("2026-10-01"), "recall", "correct")
        with self.assertRaisesRegex(pipeline.ContentError, "legacy recall"):
            pipeline.validate_event(legacy_objective)
        contradictory_self_report = event(35, "csv:0002", epoch_ms("2026-10-01"), "recall", "remember",
                                          evidence="independent-choice")
        with self.assertRaisesRegex(pipeline.ContentError, "self-report evidence"):
            pipeline.validate_event(contradictory_self_report)

    def test_schedule_days_allow_progress_beyond_default_six_intervals(self):
        events = []
        day = dt.date(2030, 1, 1)
        for index in range(9):
            events.append(event(50 + index, "csv:0001", epoch_ms(day.isoformat()), "custom-mode", "remember",
                                evidence="self-report", scheduleDays=index + 1))
            day += dt.timedelta(days=index + 1)

        state = pipeline.project_events(events, epoch_ms("2030-02-06"))["csv:0001"]
        self.assertEqual(state["stage"], 8)
        self.assertEqual(state["dueAt"], "2030-02-15")

    def test_assisted_self_report_keeps_existing_interval_and_schedules_new_card(self):
        events = [
            event(41, "csv:0001", epoch_ms("2026-10-01"), "forward", "correct"),
            event(42, "csv:0001", epoch_ms("2026-10-02"), "reverse", "correct"),
            event(43, "csv:0001", epoch_ms("2026-10-06"), "input", "remember", assisted=True,
                  evidence="self-report", scheduleDays=4),
            event(44, "csv:0002", epoch_ms("2026-10-06"), "input", "remember", assisted=True,
                  evidence="self-report", scheduleDays=4),
        ]

        states = pipeline.project_events(events, epoch_ms("2026-10-06"))

        existing = states["csv:0001"]
        self.assertEqual(existing["stage"], 1)
        self.assertEqual(existing["dueAt"], "2026-10-05")
        self.assertEqual(existing["selfRemember"], 1)
        self.assertEqual(existing["objectiveRight"], 2)
        new_card = states["csv:0002"]
        self.assertEqual(new_card["stage"], 0)
        self.assertEqual(new_card["dueAt"], "2026-10-10")
        self.assertEqual(new_card["selfRemember"], 1)
        self.assertEqual(new_card["objectiveRight"], 0)


class PackageIndexTests(unittest.TestCase):
    def test_index_uses_exact_bytes_and_keeps_latest_fourteen_dates(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp) / "watch-words"
            daily = root / "daily"
            daily.mkdir(parents=True)
            payloads = {}
            for offset in range(1, 17):
                day = dt.date(2030, 1, 1) + dt.timedelta(days=offset)
                raw = (json.dumps({"date": day.isoformat(), "synthetic": offset}, separators=(",", ":"))
                       + "\n").encode("utf-8")
                (daily / f"{day.isoformat()}.json").write_bytes(raw)
                payloads[day.isoformat()] = raw
            index = pipeline.build_package_index(root)
            self.assertEqual(index["schemaVersion"], 1)
            self.assertEqual(len(index["packages"]), 14)
            self.assertEqual(index["packages"][0]["path"], "daily/2030-01-04.json")
            for item in index["packages"]:
                day = Path(item["path"]).stem
                self.assertEqual(item["sha256"], hashlib.sha256(payloads[day]).hexdigest())


class PackagePublishTests(unittest.TestCase):
    def test_delta_validation_can_materialize_a_full_snapshot(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            base_path, delta_path, output_path = root / "base.json", root / "delta.json", root / "applied.json"
            pipeline.write_json(base_path, CONTRACT_VECTORS["full"])
            pipeline.write_json(delta_path, CONTRACT_VECTORS["delta"])
            with contextlib.redirect_stdout(io.StringIO()):
                result = pipeline.main([
                    "validate-package", str(delta_path), "--catalog", str(root / "no-catalog.json"),
                    "--base", str(base_path), "--materialized-output", str(output_path),
                ])

            self.assertEqual(result, 0)
            applied = pipeline.read_json(output_path)
            self.assertEqual(applied["kind"], "full")
            self.assertEqual(applied["revision"], 2)
            self.assertEqual(applied["withdrawnWordIds"], ["csv:0002"])

    def test_publish_snapshots_require_full_bootstrap_packages(self):
        with tempfile.TemporaryDirectory() as temp:
            watch_words = Path(temp) / "watch-words"
            daily = watch_words / "daily"
            daily.mkdir(parents=True)
            full = CONTRACT_VECTORS["full"]
            catalog = {"schemaVersion": 1, "catalogSha256": full["catalogSha256"], "rows": [
                {"id": "csv:0001", "rank": 1, "word": "alpha", "status": "ok"},
                {"id": "csv:0002", "rank": 2, "word": "beta", "status": "ok"},
            ]}
            pipeline.write_json(watch_words / "catalog.json", catalog)
            pipeline.write_json(watch_words / "current.json", full)
            pipeline.write_json(daily / f"{full['date']}.json", full)
            pipeline._validate_publish_snapshots(watch_words)

            pipeline.write_json(watch_words / "current.json", CONTRACT_VECTORS["delta"])
            with self.assertRaisesRegex(pipeline.ContentError, "完整包"):
                pipeline._validate_publish_snapshots(watch_words)
            pipeline.write_json(watch_words / "current.json", full)
            pipeline.write_json(daily / f"{full['date']}.json", CONTRACT_VECTORS["delta"])
            with self.assertRaisesRegex(pipeline.ContentError, "完整包"):
                pipeline._validate_publish_snapshots(watch_words)


if __name__ == "__main__":
    unittest.main()
