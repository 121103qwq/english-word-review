import csv
import datetime as dt
import hashlib
import importlib.util
import io
import json
import tempfile
import unittest
from pathlib import Path

MODULE_PATH = Path(__file__).with_name("content_pipeline.py")
SPEC = importlib.util.spec_from_file_location("content_pipeline", MODULE_PATH)
pipeline = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(pipeline)


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
        "newWordLimit": 15, "words": words, "newWordIds": [words[0]["id"]], "reviewWordIds": [],
    }


def epoch_ms(day, hour=12):
    moment = dt.datetime.fromisoformat(day).replace(hour=hour, tzinfo=pipeline.SHANGHAI)
    return int(moment.timestamp() * 1000)


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
    def test_duplicate_word_ids_are_rejected(self):
        duplicate = sample_word()
        with self.assertRaisesRegex(pipeline.ContentError, "重复 word id"):
            pipeline.validate_package(sample_package([sample_word(), duplicate]))

    def test_wrong_answer_index_is_rejected(self):
        pack = sample_package()
        pack["words"][0]["questions"][0]["correctIndex"] = 3
        with self.assertRaisesRegex(pipeline.ContentError, "correctIndex"):
            pipeline.validate_package(pack)


class DailyRulesTests(unittest.TestCase):
    def test_due_then_dynamic_then_csv_and_known_or_learned_are_skipped(self):
        rows = [
            {"id": f"csv:{rank:04d}", "rank": rank, "word": f"word{rank}", "zipf": 5.0,
             "phonetic": "/wɜːd/", "meaning": "词", "status": "ok"}
            for rank in range(1, 20)
        ]
        catalog = {"catalogSha256": "b" * 64, "rows": rows}
        events = [
            {"id": "e-known", "type": "answer", "wordId": "csv:0001", "at": epoch_ms("2026-10-12"),
             "deviceId": "watch", "seq": 1, "mode": "skip", "result": "known", "attempt": "primary"},
            {"id": "e-due", "type": "answer", "wordId": "csv:0002", "at": epoch_ms("2026-10-12"),
             "deviceId": "watch", "seq": 2, "mode": "forward", "result": "correct", "attempt": "primary"},
            {"id": "e-learned", "type": "answer", "wordId": "csv:0003", "at": epoch_ms("2026-10-13"),
             "deviceId": "watch", "seq": 3, "mode": "forward", "result": "correct", "attempt": "primary"},
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
        self.assertEqual(result["newWordLimit"], 15)
        self.assertLessEqual(len(result["newWordIds"]), 45)

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
        self.assertEqual(len(result["csvCandidates"]), 42)
        self.assertEqual(len(result["newWordIds"]), 45)
        self.assertEqual(result["newWordIds"][:3], ["dyn:user-0", "dyn:user-1", "dyn:user-2"])

    def test_assisted_correct_does_not_advance_review_and_future_events_are_ignored(self):
        events = [
            {"id": "e-base", "type": "answer", "wordId": "csv:0001", "at": epoch_ms("2026-10-01"),
             "deviceId": "watch", "seq": 1, "mode": "forward", "result": "correct",
             "attempt": "primary", "assisted": False},
            {"id": "e-assisted", "type": "answer", "wordId": "csv:0001", "at": epoch_ms("2026-10-02"),
             "deviceId": "watch", "seq": 2, "mode": "forward", "result": "correct",
             "attempt": "primary", "assisted": True},
            {"id": "e-future", "type": "answer", "wordId": "csv:0001", "at": epoch_ms("2026-10-03"),
             "deviceId": "watch", "seq": 3, "mode": "forward", "result": "wrong",
             "attempt": "primary", "assisted": False},
            {"id": "e-known", "type": "answer", "wordId": "csv:0002", "at": epoch_ms("2026-10-01"),
             "deviceId": "watch", "seq": 4, "mode": "skip", "result": "known",
             "attempt": "primary", "assisted": False},
            {"id": "e-resume", "type": "answer", "wordId": "csv:0002", "at": epoch_ms("2026-10-02"),
             "deviceId": "watch", "seq": 5, "mode": "forward", "result": "wrong",
             "attempt": "primary", "assisted": False},
            {"id": "e-only-future", "type": "answer", "wordId": "csv:0003", "at": epoch_ms("2026-10-03"),
             "deviceId": "watch", "seq": 6, "mode": "forward", "result": "correct",
             "attempt": "primary", "assisted": False},
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


if __name__ == "__main__":
    unittest.main()
