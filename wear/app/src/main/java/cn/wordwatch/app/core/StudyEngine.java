package cn.wordwatch.app.core;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.math.BigDecimal;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.ResolverStyle;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Pure-Java validation, event projection, and daily queue rules for WordWatch. */
public final class StudyEngine {
    public static final ZoneId STUDY_ZONE = ZoneId.of("Asia/Shanghai");
    private static final DateTimeFormatter PACKAGE_DATE =
            DateTimeFormatter.ofPattern("uuuu-MM-dd").withResolverStyle(ResolverStyle.STRICT);
    private static final int[] INTERVAL_DAYS = {1, 3, 7, 14, 30, 60};
    private static final int MAX_WORDS = 500;
    private static final int MAX_NEW_CANDIDATES = 45;

    private StudyEngine() {}

    /** Validates a complete v1 daily package. Displayed content remains plain text. */
    public static void validatePackage(JSONObject pack) {
        if (pack == null) {
            throw invalid("package is required");
        }

        if (requiredInt(pack, "schemaVersion") != 1) {
            throw invalid("unsupported package schemaVersion");
        }
        requiredText(pack, "packageId", 128);
        String date = requiredText(pack, "date", 10);
        try {
            if (!LocalDate.parse(date, PACKAGE_DATE).toString().equals(date)) {
                throw invalid("date must use YYYY-MM-DD");
            }
        } catch (DateTimeException e) {
            throw invalid("date must use YYYY-MM-DD");
        }
        if (!"Asia/Shanghai".equals(requiredText(pack, "timezone", 64))) {
            throw invalid("timezone must be Asia/Shanghai");
        }
        if (!"ai-authored".equals(requiredText(pack, "contentMode", 32))) {
            throw invalid("unsupported contentMode");
        }
        String digest = requiredText(pack, "catalogSha256", 64);
        if (!digest.matches("[0-9a-fA-F]{64}")) {
            throw invalid("catalogSha256 must be a SHA-256 hex digest");
        }
        if (requiredInt(pack, "newWordLimit") != 15) {
            throw invalid("newWordLimit must be 15");
        }

        JSONArray words = requiredArray(pack, "words");
        if (words.length() > MAX_WORDS) {
            throw invalid("too many words");
        }
        JSONArray newWordIds = requiredArray(pack, "newWordIds");
        JSONArray reviewWordIds = requiredArray(pack, "reviewWordIds");
        if (newWordIds.length() > MAX_NEW_CANDIDATES) {
            throw invalid("too many new word candidates");
        }
        if (reviewWordIds.length() > MAX_WORDS) {
            throw invalid("too many review word ids");
        }

        Map<String, JSONObject> byId = new HashMap<>();
        for (int i = 0; i < words.length(); i++) {
            JSONObject word = objectAt(words, i, "words");
            validateWord(word);
            String id = requiredText(word, "id", 160);
            if (byId.put(id, word) != null) {
                throw invalid("duplicate word id");
            }
        }

        Set<String> assigned = new HashSet<>();
        validateIdList(newWordIds, byId, assigned, "newWordIds");
        validateIdList(reviewWordIds, byId, assigned, "reviewWordIds");
        if (assigned.size() != byId.size()) {
            throw invalid("every word must be listed as new or review");
        }
    }

    private static void validateWord(JSONObject word) {
        String id = requiredText(word, "id", 160);
        validateWordId(id);
        requiredText(word, "word", 100);
        if (requiredInt(word, "rank") < 1) {
            throw invalid("rank must be positive");
        }
        requiredTextAllowEmpty(word, "phonetic", 120);
        requiredText(word, "meaning", 1200);
        if (word.has("roots")) {
            requiredTextAllowEmpty(word, "roots", 1200);
        }
        if (!"AI".equals(requiredText(word, "generatedBy", 16))) {
            throw invalid("generatedBy must be AI");
        }

        JSONArray examples = requiredArray(word, "examples");
        if (examples.length() < 1 || examples.length() > 8) {
            throw invalid("examples must contain 1 to 8 items");
        }
        for (int i = 0; i < examples.length(); i++) {
            JSONObject example = objectAt(examples, i, "examples");
            requiredText(example, "en", 500);
            requiredText(example, "zh", 700);
        }

        JSONArray senses = requiredArray(word, "senses");
        if (senses.length() < 1 || senses.length() > 16) {
            throw invalid("senses must contain 1 to 16 items");
        }
        for (int i = 0; i < senses.length(); i++) {
            textValue(senses, i, "sense", 500, false);
        }

        JSONArray questions = requiredArray(word, "questions");
        if (questions.length() < 2 || questions.length() > 16) {
            throw invalid("questions must contain 2 to 16 items");
        }
        Set<String> questionIds = new HashSet<>();
        boolean forward = false;
        boolean reverse = false;
        for (int i = 0; i < questions.length(); i++) {
            JSONObject question = objectAt(questions, i, "questions");
            String questionId = requiredText(question, "id", 160);
            if (!questionIds.add(questionId)) {
                throw invalid("duplicate question id");
            }
            String mode = requiredText(question, "mode", 16);
            if ("forward".equals(mode)) {
                forward = true;
            } else if ("reverse".equals(mode)) {
                reverse = true;
            } else if (!"rare".equals(mode)) {
                throw invalid("question mode must be forward, reverse, or rare");
            }
            requiredText(question, "prompt", 500);
            JSONArray options = requiredArray(question, "options");
            if (options.length() < 2 || options.length() > 4) {
                throw invalid("question options must contain 2 to 4 items");
            }
            Set<String> optionValues = new HashSet<>();
            for (int j = 0; j < options.length(); j++) {
                String option = textValue(options, j, "option", 500, false);
                if (!optionValues.add(option)) {
                    throw invalid("question options must be distinct");
                }
            }
            int correctIndex = requiredInt(question, "correctIndex");
            if (correctIndex < 0 || correctIndex >= options.length()) {
                throw invalid("correctIndex is out of range");
            }
            requiredText(question, "explanation", 1200);
        }
        if (!forward || !reverse) {
            throw invalid("each word requires forward and reverse questions");
        }
    }

    private static void validateIdList(
            JSONArray ids, Map<String, JSONObject> words, Set<String> assigned, String field) {
        for (int i = 0; i < ids.length(); i++) {
            String id = textValue(ids, i, field, 160, false);
            validateWordId(id);
            if (!words.containsKey(id)) {
                throw invalid(field + " references a missing word");
            }
            if (!assigned.add(id)) {
                throw invalid("word ids must be unique across newWordIds and reviewWordIds");
            }
        }
    }

    private static void validateWordId(String id) {
        if (!id.matches("(?:csv:[0-9]{4,12}|dyn:[A-Za-z0-9][A-Za-z0-9._:-]{0,127})")) {
            throw invalid("invalid word id");
        }
    }

    /**
     * Returns the immutable-id union sorted by event time, device, sequence, then id.
     * Identical duplicate ids collapse; a duplicate id with different contents is a conflict.
     */
    public static JSONArray mergeEvents(JSONArray left, JSONArray right) {
        if (left == null || right == null) {
            throw invalid("event arrays are required");
        }
        Map<String, JSONObject> byId = new HashMap<>();
        Map<String, String> canonicalById = new HashMap<>();
        appendEvents(left, byId, canonicalById);
        appendEvents(right, byId, canonicalById);

        List<JSONObject> ordered = new ArrayList<>(byId.values());
        ordered.sort(EVENT_ORDER);
        JSONArray result = new JSONArray();
        for (JSONObject event : ordered) {
            result.put(copyObject(event));
        }
        return result;
    }

    private static void appendEvents(
            JSONArray source, Map<String, JSONObject> byId, Map<String, String> canonicalById) {
        for (int i = 0; i < source.length(); i++) {
            JSONObject event = objectAt(source, i, "events");
            validateEvent(event);
            String id = requiredText(event, "id", 64);
            String canonical = canonicalJson(event);
            String prior = canonicalById.get(id);
            if (prior != null && !prior.equals(canonical)) {
                throw invalid("event id has conflicting contents");
            }
            if (prior == null) {
                canonicalById.put(id, canonical);
                byId.put(id, copyObject(event));
            }
        }
    }

    private static void validateEvent(JSONObject event) {
        String id = requiredText(event, "id", 64);
        requireUuid(id, "event id");
        String deviceId = requiredText(event, "deviceId", 128);
        validateDeviceId(deviceId);
        if (requiredLong(event, "seq") < 0) {
            throw invalid("event seq must be non-negative");
        }
        if (requiredLong(event, "at") < 0) {
            throw invalid("event at must be non-negative");
        }
        String type = requiredText(event, "type", 16);
        if ("undo".equals(type)) {
            requireAllowedKeys(event, "undo event", "id", "deviceId", "seq", "at", "type",
                    "targetEventId", "wordId", "packageId");
            String target = requiredText(event, "targetEventId", 64);
            requireUuid(target, "targetEventId");
            if (id.equals(target)) {
                throw invalid("undo event cannot target itself");
            }
            if (event.has("wordId")) {
                validateWordId(requiredText(event, "wordId", 160));
            }
            if (event.has("packageId")) {
                requiredText(event, "packageId", 128);
            }
            return;
        }
        if (!"answer".equals(type)) {
            throw invalid("event type must be answer or undo");
        }
        requireAllowedKeys(event, "answer event", "id", "deviceId", "seq", "at", "wordId",
                "packageId", "type", "mode", "result", "attempt", "assisted", "isReview");

        String wordId = requiredText(event, "wordId", 160);
        validateWordId(wordId);
        requiredText(event, "packageId", 128);
        String mode = requiredText(event, "mode", 16);
        if (!("forward".equals(mode) || "reverse".equals(mode) || "rare".equals(mode)
                || "recall".equals(mode) || "skip".equals(mode))) {
            throw invalid("invalid answer mode");
        }
        String result = requiredText(event, "result", 16);
        if (!("correct".equals(result) || "wrong".equals(result) || "remember".equals(result)
                || "hard".equals(result) || "forgot".equals(result) || "known".equals(result))) {
            throw invalid("invalid answer result");
        }
        String attempt = requiredText(event, "attempt", 16);
        if (!"primary".equals(attempt) && !"retry".equals(attempt)) {
            throw invalid("attempt must be primary or retry");
        }
        if (!(event.opt("assisted") instanceof Boolean)) {
            throw invalid("assisted must be boolean");
        }
        if (event.has("isReview") && !(event.opt("isReview") instanceof Boolean)) {
            throw invalid("isReview must be boolean");
        }
        if ("recall".equals(mode)) {
            if (!("remember".equals(result) || "hard".equals(result) || "forgot".equals(result))) {
                throw invalid("recall events must use remember, hard, or forgot");
            }
        } else if ("skip".equals(mode)) {
            if (!"known".equals(result) || !"primary".equals(attempt)) {
                throw invalid("skip events must be primary known answers");
            }
        } else if (!("correct".equals(result) || "wrong".equals(result))) {
            throw invalid("choice events must use correct or wrong");
        }
    }

    private static void validateDeviceId(String deviceId) {
        if (!deviceId.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,127}")) {
            throw invalid("invalid deviceId");
        }
    }

    private static void requireAllowedKeys(JSONObject object, String field, String... allowedNames) {
        Set<String> allowed = new HashSet<>(Arrays.asList(allowedNames));
        for (java.util.Iterator<String> it = object.keys(); it.hasNext();) {
            if (!allowed.contains(it.next())) {
                throw invalid(field + " contains an unsupported field");
            }
        }
    }

    private static void requireUuid(String value, String field) {
        try {
            UUID parsed = UUID.fromString(value);
            if (!parsed.toString().equalsIgnoreCase(value)) {
                throw new IllegalArgumentException(field + " must be a canonical UUID");
            }
        } catch (IllegalArgumentException e) {
            throw invalid(field + " must be a canonical UUID");
        }
    }

    private static final Comparator<JSONObject> EVENT_ORDER = (a, b) -> {
        int byAt = Long.compare(longUnchecked(a, "at"), longUnchecked(b, "at"));
        if (byAt != 0) return byAt;
        int byDevice = stringUnchecked(a, "deviceId").compareTo(stringUnchecked(b, "deviceId"));
        if (byDevice != 0) return byDevice;
        int bySequence = Long.compare(longUnchecked(a, "seq"), longUnchecked(b, "seq"));
        if (bySequence != 0) return bySequence;
        return stringUnchecked(a, "id").compareTo(stringUnchecked(b, "id"));
    };

    /** Reprojects active answer events from scratch, so undo events naturally reverse prior answers. */
    public static Map<String, Card> project(JSONArray events, long now) {
        if (events == null || now < 0) {
            throw invalid("events and a non-negative now are required");
        }
        List<JSONObject> active = activeEvents(events, now);
        Map<String, Card> cards = new HashMap<>();
        for (JSONObject event : active) {
            if (!"answer".equals(stringUnchecked(event, "type"))) {
                continue;
            }
            long at = longUnchecked(event, "at");
            if (at > now) {
                continue;
            }
            String wordId = stringUnchecked(event, "wordId");
            Card card = cards.computeIfAbsent(wordId, Card::new);
            card.lastAt = Math.max(card.lastAt, at);

            String result = stringUnchecked(event, "result");
            String mode = stringUnchecked(event, "mode");
            boolean primary = "primary".equals(stringUnchecked(event, "attempt"));
            boolean assisted = Boolean.TRUE.equals(event.opt("assisted"));
            if (primary && !"known".equals(result)) {
                // A later real answer opts a previously skipped card back into normal study.
                card.known = false;
            }
            switch (result) {
                case "correct" -> {
                    if ("recall".equals(mode)) card.selfRemember++;
                    else if (assisted) card.assistedRight++;
                    else card.objectiveRight++;
                }
                case "wrong" -> {
                    if ("recall".equals(mode)) card.selfForgot++;
                    else card.objectiveWrong++;
                }
                case "remember" -> card.selfRemember++;
                case "forgot" -> card.selfForgot++;
                case "known" -> {
                    card.known = true;
                    card.stage = -1;
                    card.dueAt = 0L;
                }
                default -> { /* hard changes the schedule but is not a success counter. */ }
            }

            if (!primary) {
                continue;
            }
            if ("correct".equals(result) && assisted) {
                // Seeing the explanation is useful practice, but does not advance mastery.
                // Give a new card its first revisit while leaving an existing due state intact.
                if (card.dueAt == 0L) {
                    card.stage = 0;
                    card.dueAt = dueAtCalendarDaysLater(at, INTERVAL_DAYS[0]);
                }
                continue;
            }
            if ("correct".equals(result) || "remember".equals(result)) {
                if (card.dueAt == 0L) {
                    card.stage = 0;
                    card.dueAt = dueAtCalendarDaysLater(at, INTERVAL_DAYS[0]);
                } else if (at >= card.dueAt) {
                    card.stage = Math.min(card.stage + 1, INTERVAL_DAYS.length - 1);
                    card.dueAt = dueAtCalendarDaysLater(at, INTERVAL_DAYS[card.stage]);
                }
            } else if ("wrong".equals(result) || "forgot".equals(result) || "hard".equals(result)) {
                card.stage = 0;
                card.dueAt = dueAtCalendarDaysLater(at, INTERVAL_DAYS[0]);
            }
        }

        List<String> ids = new ArrayList<>(cards.keySet());
        Collections.sort(ids);
        Map<String, Card> ordered = new LinkedHashMap<>();
        for (String id : ids) {
            ordered.put(id, cards.get(id));
        }
        return ordered;
    }

    private static List<JSONObject> activeEvents(JSONArray events, long asOf) {
        JSONArray merged = mergeEvents(events, new JSONArray());
        List<JSONObject> ordered = new ArrayList<>(merged.length());
        Map<String, JSONObject> byId = new HashMap<>();
        Map<String, Boolean> active = new HashMap<>();
        for (int i = 0; i < merged.length(); i++) {
            JSONObject event = objectAt(merged, i, "events");
            ordered.add(event);
            byId.put(stringUnchecked(event, "id"), event);
            active.put(stringUnchecked(event, "id"), true);
        }

        // Reverse order lets a later undo cancel an earlier undo before that earlier undo is applied.
        for (int i = ordered.size() - 1; i >= 0; i--) {
            JSONObject event = ordered.get(i);
            if (!"undo".equals(stringUnchecked(event, "type"))
                    || longUnchecked(event, "at") > asOf
                    || !Boolean.TRUE.equals(active.get(stringUnchecked(event, "id")))) {
                continue;
            }
            String target = stringUnchecked(event, "targetEventId");
            if (byId.containsKey(target)) {
                active.put(target, false);
            }
        }

        List<JSONObject> result = new ArrayList<>();
        for (JSONObject event : ordered) {
            if (longUnchecked(event, "at") <= asOf
                    && Boolean.TRUE.equals(active.get(stringUnchecked(event, "id")))) {
                result.add(event);
            }
        }
        return result;
    }

    /**
     * Builds today's initial queue: due reviews first, package-selected reviews next,
     * then unattempted new candidates in their authored order.
     */
    public static List<Task> queue(
            JSONObject pack, JSONObject cachedWordsById, JSONArray events, long now, int newLimit) {
        validatePackage(pack);
        if (events == null || now < 0) {
            throw invalid("events and a non-negative now are required");
        }
        if (cachedWordsById == null) {
            cachedWordsById = new JSONObject();
        }

        Map<String, Card> cards = project(events, now);
        LocalDate today = Instant.ofEpochMilli(now).atZone(STUDY_ZONE).toLocalDate();
        Set<String> primaryToday = new HashSet<>();
        for (JSONObject event : activeEvents(events, now)) {
            if (!"answer".equals(stringUnchecked(event, "type"))
                    || !"primary".equals(stringUnchecked(event, "attempt"))) {
                continue;
            }
            long at = longUnchecked(event, "at");
            LocalDate eventDay = Instant.ofEpochMilli(at).atZone(STUDY_ZONE).toLocalDate();
            if (today.equals(eventDay)) {
                primaryToday.add(stringUnchecked(event, "wordId"));
            }
        }

        Set<String> packageWords = new HashSet<>();
        JSONArray words = requiredArray(pack, "words");
        for (int i = 0; i < words.length(); i++) {
            packageWords.add(stringUnchecked(objectAt(words, i, "words"), "id"));
        }
        List<Task> tasks = new ArrayList<>();
        Set<String> queued = new HashSet<>();

        List<Card> overdue = new ArrayList<>();
        for (Card card : cards.values()) {
            if (!card.known && card.dueAt > 0L && card.dueAt <= now
                    && !primaryToday.contains(card.wordId)
                    && hasDefinition(card.wordId, packageWords, cachedWordsById)) {
                overdue.add(card);
            }
        }
        overdue.sort(Comparator.comparingLong((Card card) -> card.dueAt).thenComparing(card -> card.wordId));
        for (Card card : overdue) {
            tasks.add(new Task(card.wordId, true, "primary"));
            queued.add(card.wordId);
        }

        JSONArray reviewIds = requiredArray(pack, "reviewWordIds");
        for (int i = 0; i < reviewIds.length(); i++) {
            String id = stringAt(reviewIds, i, "reviewWordIds");
            Card card = cards.get(id);
            boolean eligible = card == null || (!card.known && card.dueAt > 0L && card.dueAt <= now);
            if (eligible && !primaryToday.contains(id) && !queued.contains(id)
                    && hasDefinition(id, packageWords, cachedWordsById)) {
                tasks.add(new Task(id, true, "primary"));
                queued.add(id);
            }
        }

        int permittedNew = Math.max(0, Math.min(newLimit, requiredInt(pack, "newWordLimit")));
        JSONArray newIds = requiredArray(pack, "newWordIds");
        int addedNew = 0;
        for (int i = 0; i < newIds.length() && addedNew < permittedNew; i++) {
            String id = stringAt(newIds, i, "newWordIds");
            Card card = cards.get(id);
            if (card != null || primaryToday.contains(id) || queued.contains(id)) {
                continue;
            }
            tasks.add(new Task(id, false, "primary"));
            queued.add(id);
            addedNew++;
        }
        return Collections.unmodifiableList(tasks);
    }

    private static boolean hasDefinition(String id, Set<String> packageWords, JSONObject cache) {
        return packageWords.contains(id) || cache.has(id);
    }

    /**
     * Returns today's distinct primary answer counts: new, review, known skips, and assisted
     * correct answers. The fourth value keeps post-explanation results separately visible.
     */
    public static int[] todayCounts(JSONArray events, long now) {
        if (events == null || now < 0) {
            throw invalid("events and a non-negative now are required");
        }
        LocalDate today = Instant.ofEpochMilli(now).atZone(STUDY_ZONE).toLocalDate();
        Set<String> newWords = new HashSet<>();
        Set<String> reviewWords = new HashSet<>();
        Set<String> knownWords = new HashSet<>();
        Set<String> assistedRightWords = new HashSet<>();

        for (JSONObject event : activeEvents(events, now)) {
            if (!"answer".equals(stringUnchecked(event, "type"))) {
                continue;
            }
            long at = longUnchecked(event, "at");
            if (!today.equals(Instant.ofEpochMilli(at).atZone(STUDY_ZONE).toLocalDate())) {
                continue;
            }
            String wordId = stringUnchecked(event, "wordId");
            String result = stringUnchecked(event, "result");
            if ("primary".equals(stringUnchecked(event, "attempt"))
                    && "correct".equals(result) && Boolean.TRUE.equals(event.opt("assisted"))) {
                assistedRightWords.add(wordId);
            }
            if ("known".equals(result)) {
                knownWords.add(wordId);
            } else if ("primary".equals(stringUnchecked(event, "attempt"))) {
                if (Boolean.TRUE.equals(event.opt("isReview"))) {
                    reviewWords.add(wordId);
                } else {
                    newWords.add(wordId);
                }
            }
        }
        return new int[] {
                newWords.size(), reviewWords.size(), knownWords.size(), assistedRightWords.size()
        };
    }

    private static long dueAtCalendarDaysLater(long eventAt, int days) {
        LocalDate date = Instant.ofEpochMilli(eventAt).atZone(STUDY_ZONE).toLocalDate();
        return date.plusDays(days).atStartOfDay(STUDY_ZONE).toInstant().toEpochMilli();
    }

    private static JSONObject objectAt(JSONArray array, int index, String field) {
        Object value;
        try {
            value = array.get(index);
        } catch (JSONException e) {
            throw invalid("invalid " + field);
        }
        if (!(value instanceof JSONObject object)) {
            throw invalid(field + " items must be objects");
        }
        return object;
    }

    private static String stringAt(JSONArray array, int index, String field) {
        return textValue(array, index, field, 160, false);
    }

    private static String textValue(
            JSONArray array, int index, String field, int maxLength, boolean allowEmpty) {
        Object value;
        try {
            value = array.get(index);
        } catch (JSONException e) {
            throw invalid("invalid " + field);
        }
        if (!(value instanceof String text)) {
            throw invalid(field + " must be a string");
        }
        return validateText(text, field, maxLength, allowEmpty);
    }

    private static JSONArray requiredArray(JSONObject object, String field) {
        Object value = object.opt(field);
        if (!(value instanceof JSONArray array)) {
            throw invalid(field + " must be an array");
        }
        return array;
    }

    private static String requiredText(JSONObject object, String field, int maxLength) {
        Object value = object.opt(field);
        if (!(value instanceof String text)) {
            throw invalid(field + " must be a string");
        }
        return validateText(text, field, maxLength, false);
    }

    private static String requiredTextAllowEmpty(JSONObject object, String field, int maxLength) {
        Object value = object.opt(field);
        if (!(value instanceof String text)) {
            throw invalid(field + " must be a string");
        }
        return validateText(text, field, maxLength, true);
    }

    private static String validateText(String text, String field, int maxLength, boolean allowEmpty) {
        if ((!allowEmpty && text.trim().isEmpty()) || text.length() > maxLength) {
            throw invalid(field + " is empty or too long");
        }
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (Character.isISOControl(c) && c != '\n' && c != '\r' && c != '\t') {
                throw invalid(field + " contains a control character");
            }
        }
        return text;
    }

    private static int requiredInt(JSONObject object, String field) {
        long value = requiredLong(object, field);
        if (value < Integer.MIN_VALUE || value > Integer.MAX_VALUE) {
            throw invalid(field + " is out of range");
        }
        return (int) value;
    }

    private static long requiredLong(JSONObject object, String field) {
        Object value = object.opt(field);
        if (!(value instanceof Number number)) {
            throw invalid(field + " must be an integer");
        }
        try {
            return new BigDecimal(number.toString()).longValueExact();
        } catch (ArithmeticException e) {
            throw invalid(field + " is out of range");
        } catch (NumberFormatException e) {
            throw invalid(field + " must be an integer");
        }
    }

    private static long longUnchecked(JSONObject object, String field) {
        return ((Number) object.opt(field)).longValue();
    }

    private static String stringUnchecked(JSONObject object, String field) {
        return (String) object.opt(field);
    }

    private static String canonicalJson(Object value) {
        if (value == null || value == JSONObject.NULL) {
            return "null";
        }
        if (value instanceof JSONObject object) {
            List<String> keys = new ArrayList<>();
            for (java.util.Iterator<String> it = object.keys(); it.hasNext();) {
                keys.add(it.next());
            }
            Collections.sort(keys);
            StringBuilder out = new StringBuilder("{");
            for (int i = 0; i < keys.size(); i++) {
                if (i > 0) out.append(',');
                String key = keys.get(i);
                out.append(JSONObject.quote(key)).append(':').append(canonicalJson(object.opt(key)));
            }
            return out.append('}').toString();
        }
        if (value instanceof JSONArray array) {
            StringBuilder out = new StringBuilder("[");
            for (int i = 0; i < array.length(); i++) {
                if (i > 0) out.append(',');
                out.append(canonicalJson(array.opt(i)));
            }
            return out.append(']').toString();
        }
        if (value instanceof Number number) {
            try {
                return new BigDecimal(number.toString()).stripTrailingZeros().toPlainString();
            } catch (NumberFormatException ignored) {
                return number.toString();
            }
        }
        if (value instanceof String text) {
            return JSONObject.quote(text);
        }
        if (value instanceof Boolean bool) {
            return bool ? "true" : "false";
        }
        return JSONObject.quote(value.toString());
    }

    private static JSONObject copyObject(JSONObject object) {
        try {
            return new JSONObject(object.toString());
        } catch (JSONException e) {
            throw invalid("could not copy JSON object");
        }
    }

    private static IllegalArgumentException invalid(String message) {
        return new IllegalArgumentException(message);
    }

    /** Current projected state for one word. */
    public static final class Card {
        public final String wordId;
        public int stage = -1;
        public long dueAt;
        public boolean known;
        /** Unassisted objective correct answers only. */
        public int objectiveRight;
        public int objectiveWrong;
        /** Correct answers after the learner opened the explanation. */
        public int assistedRight;
        public int selfRemember;
        public int selfForgot;
        public long lastAt;

        private Card(String wordId) {
            this.wordId = wordId;
        }
    }

    /** A single item in today's initial queue. */
    public static final class Task {
        public final String wordId;
        public final boolean review;
        public final String attempt;

        public Task(String wordId, boolean review, String attempt) {
            this.wordId = wordId;
            this.review = review;
            this.attempt = attempt;
        }
    }
}
