package cn.wordwatch.app.core;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

final class TestFixtures {
    private static final String ZERO_HASH =
            "0000000000000000000000000000000000000000000000000000000000000000";

    private TestFixtures() {}

    static JSONObject packageWith(List<String> newIds, List<String> reviewIds) throws JSONException {
        JSONObject pack = new JSONObject()
                .put("schemaVersion", 1)
                .put("packageId", "daily-2026-01-01")
                .put("date", "2026-01-01")
                .put("timezone", "Asia/Shanghai")
                .put("contentMode", "ai-authored")
                .put("catalogSha256", ZERO_HASH)
                .put("newWordLimit", 15);
        JSONArray words = new JSONArray();
        JSONArray newWords = new JSONArray();
        JSONArray reviews = new JSONArray();
        int rank = 1;
        for (String id : newIds) {
            words.put(word(id, rank++));
            newWords.put(id);
        }
        for (String id : reviewIds) {
            words.put(word(id, rank++));
            reviews.put(id);
        }
        pack.put("words", words);
        pack.put("newWordIds", newWords);
        pack.put("reviewWordIds", reviews);
        return pack;
    }

    static JSONObject word(String id, int rank) throws JSONException {
        JSONArray examples = new JSONArray().put(
                new JSONObject().put("en", "A clear example.").put("zh", "一个清楚的例句。"));
        JSONArray senses = new JSONArray().put("含义");
        JSONArray questions = new JSONArray()
                .put(question("q-forward-" + id, "forward"))
                .put(question("q-reverse-" + id, "reverse"));
        return new JSONObject()
                .put("id", id)
                .put("word", "word" + rank)
                .put("rank", rank)
                .put("phonetic", "/wɜːd/")
                .put("meaning", "含义")
                .put("generatedBy", "AI")
                .put("examples", examples)
                .put("senses", senses)
                .put("questions", questions);
    }

    private static JSONObject question(String id, String mode) throws JSONException {
        return new JSONObject()
                .put("id", id)
                .put("mode", mode)
                .put("prompt", "Choose the answer.")
                .put("options", new JSONArray().put("正确").put("错误"))
                .put("correctIndex", 0)
                .put("explanation", "根据词义选择。");
    }

    static JSONObject answer(
            String id,
            long seq,
            long at,
            String wordId,
            String result,
            String mode,
            String attempt,
            Boolean isReview) throws JSONException {
        JSONObject event = new JSONObject()
                .put("id", id)
                .put("deviceId", "device-A")
                .put("seq", seq)
                .put("at", at)
                .put("wordId", wordId)
                .put("packageId", "daily-2026-01-01")
                .put("type", "answer")
                .put("mode", mode)
                .put("result", result)
                .put("attempt", attempt)
                .put("assisted", false);
        if (isReview != null) {
            event.put("isReview", isReview);
        }
        return event;
    }

    static JSONObject skip(String id, long seq, long at, String wordId) throws JSONException {
        return answer(id, seq, at, wordId, "known", "skip", "primary", false);
    }

    static JSONObject undo(String id, long seq, long at, String targetEventId) throws JSONException {
        return new JSONObject()
                .put("id", id)
                .put("deviceId", "device-A")
                .put("seq", seq)
                .put("at", at)
                .put("type", "undo")
                .put("targetEventId", targetEventId);
    }

    static long at(String day, int hour, int minute) {
        return LocalDate.parse(day)
                .atTime(hour, minute)
                .atZone(ZoneId.of("Asia/Shanghai"))
                .toInstant()
                .toEpochMilli();
    }

    static JSONObject eventBatch(String deviceId, JSONArray events) throws JSONException {
        return new JSONObject()
                .put("schemaVersion", 1)
                .put("deviceId", deviceId)
                .put("events", events);
    }

    static JSONObject githubFile(JSONObject content, String sha) throws JSONException {
        String encoded = java.util.Base64.getEncoder()
                .encodeToString(content.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
        return new JSONObject()
                .put("sha", sha)
                .put("encoding", "base64")
                .put("content", encoded);
    }
}
