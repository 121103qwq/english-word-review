package cn.wordwatch.app.core;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.math.BigDecimal;
import java.text.Normalizer;
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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/** Pure-Java validation, event projection, and daily queue rules for WordWatch. */
public final class StudyEngine {
    public static final int DEFAULT_NEW_WORD_LIMIT = 30;
    public static final ZoneId STUDY_ZONE = ZoneId.of("Asia/Shanghai");
    public static final Set<String> SUPPORTED_CAPABILITIES = Collections.unmodifiableSet(
            new HashSet<>(Arrays.asList("plain-text", "choice-v1", "word-notes-v1",
                    "package-delta-v1", "word-withdrawal-v1", "input-v1", "recall-v1",
                    "review-policy-v1")));
    private static final DateTimeFormatter PACKAGE_DATE =
            DateTimeFormatter.ofPattern("uuuu-MM-dd").withResolverStyle(ResolverStyle.STRICT);
    private static final int[] INTERVAL_DAYS = {1, 3, 7, 14, 30, 60};
    private static final int MAX_WORDS = 500;
    private static final int MAX_NEW_CANDIDATES = MAX_WORDS;
    private static final Pattern SHA256_PATTERN = Pattern.compile("[0-9a-fA-F]{64}");
    private static final Pattern MODE_PATTERN = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:-]{0,63}");
    private static final Pattern WORD_ID_PATTERN = Pattern.compile("(?:csv:[0-9]{4,12}|dyn:[A-Za-z0-9][A-Za-z0-9._:-]{0,127})");
    private static final Pattern DEVICE_ID_PATTERN = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,127}");

    private StudyEngine() {}

    /** Validates a v1 legacy package or a v2 full/delta package. */
    public static void validatePackage(JSONObject pack) {
        if (pack == null) {
            throw invalid("package is required");
        }
        int schema = requiredInt(pack, "schemaVersion");
        if (schema != 1 && schema != 2) throw invalid("unsupported package schemaVersion");
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
        if (!SHA256_PATTERN.matcher(digest).matches()) {
            throw invalid("catalogSha256 must be a SHA-256 hex digest");
        }
        int newWordLimit = requiredInt(pack, "newWordLimit");
        if (schema == 1 ? (newWordLimit < 0 || newWordLimit > 10000)
                : (newWordLimit < -1 || newWordLimit > 10000)) {
            throw invalid("newWordLimit is outside the supported range");
        }

        Set<String> capabilities = schema == 2
                ? validateCapabilities(requiredArray(pack, "requiredCapabilities"))
                : (pack.has("requiredCapabilities")
                        ? validateCapabilities(requiredArray(pack, "requiredCapabilities"))
                        : Collections.emptySet());
        if (schema == 2 && !capabilities.contains("plain-text")) {
            throw invalid("v2 packages must require plain-text");
        }
        if (pack.has("reviewPolicy")) {
            if (schema != 2 || !capabilities.contains("review-policy-v1")) {
                throw invalid("reviewPolicy must require review-policy-v1");
            }
            validateReviewPolicy(pack.optJSONObject("reviewPolicy"));
        }
        if (pack.has("reviewMode")) {
            if (schema != 2 || !capabilities.contains("review-policy-v1")) {
                throw invalid("reviewMode must require review-policy-v1");
            }
            String reviewMode = requiredText(pack, "reviewMode", 32);
            if (!"question".equals(reviewMode) && !"self-recall".equals(reviewMode)) {
                throw invalid("reviewMode must be question or self-recall");
            }
        }
        int revision = schema == 2 ? requiredInt(pack, "revision") : 0;
        if (schema == 2 && revision < 1) throw invalid("revision must be positive");
        String kind = schema == 2 ? requiredText(pack, "kind", 16) : "full";
        if (!("full".equals(kind) || "delta".equals(kind))) {
            throw invalid("kind must be full or delta");
        }
        if (schema == 2 && "delta".equals(kind)) {
            if (!capabilities.contains("package-delta-v1")) {
                throw invalid("delta packages must require package-delta-v1");
            }
            if (requiredInt(pack, "baseRevision") < 1 || requiredInt(pack, "baseRevision") >= revision) {
                throw invalid("baseRevision must be positive and older than revision");
            }
        } else if (schema == 2 && pack.has("baseRevision")) {
            throw invalid("full packages must not have baseRevision");
        }

        JSONArray words = requiredArray(pack, "words");
        if (words.length() > MAX_WORDS) {
            throw invalid("too many words");
        }
        JSONArray newWordIds = requiredArray(pack, "newWordIds");
        JSONArray reviewWordIds = requiredArray(pack, "reviewWordIds");
        if (newWordIds.length() > MAX_WORDS) {
            throw invalid("too many new word candidates");
        }
        if (reviewWordIds.length() > MAX_WORDS) {
            throw invalid("too many review word ids");
        }

        JSONArray withdrawnIds = schema == 2
                ? requiredArray(pack, "withdrawnWordIds") : new JSONArray();
        if (withdrawnIds.length() > 5000) throw invalid("too many withdrawn word ids");
        Set<String> withdrawn = new HashSet<>();
        for (int i = 0; i < withdrawnIds.length(); i++) {
            String id = textValue(withdrawnIds, i, "withdrawnWordIds", 160, false);
            validateWordId(id);
            if (!withdrawn.add(id)) throw invalid("duplicate withdrawn word id");
        }
        if (schema == 2 && !withdrawn.isEmpty()
                && !capabilities.contains("word-withdrawal-v1")) {
            throw invalid("withdrawnWordIds must require word-withdrawal-v1");
        }

        Map<String, JSONObject> byId = new HashMap<>();
        for (int i = 0; i < words.length(); i++) {
            JSONObject word = objectAt(words, i, "words");
            validateWord(word, schema, capabilities);
            String id = requiredText(word, "id", 160);
            if (byId.put(id, word) != null) {
                throw invalid("duplicate word id");
            }
            if (withdrawn.contains(id)) throw invalid("active word is also withdrawn");
        }

        Set<String> assigned = new HashSet<>();
        validateIdList(newWordIds, byId, assigned, withdrawn, "newWordIds", "delta".equals(kind));
        validateIdList(reviewWordIds, byId, assigned, withdrawn, "reviewWordIds", "delta".equals(kind));
        if (!"delta".equals(kind) && assigned.size() != byId.size()) {
            throw invalid("every word must be listed as new or review");
        }
    }

    private static Set<String> validateCapabilities(JSONArray values) {
        Set<String> result = new LinkedHashSet<>();
        for (int i = 0; i < values.length(); i++) {
            String value = textValue(values, i, "requiredCapabilities", 80, false);
            if (!result.add(value)) throw invalid("duplicate required capability");
            if (!SUPPORTED_CAPABILITIES.contains(value)) {
                throw invalid("unsupported required capability: " + value);
            }
        }
        return result;
    }

    private static void validateWord(JSONObject word, int schema, Set<String> capabilities) {
        String id = requiredText(word, "id", 160);
        validateWordId(id);
        requiredText(word, "word", 100);
        int rank = requiredInt(word, "rank");
        if (rank < 1) {
            throw invalid("rank must be positive");
        }
        if (id.startsWith("csv:") && Integer.parseInt(id.substring(4)) != rank) {
            throw invalid("csv word id must match rank");
        }
        requiredTextAllowEmpty(word, "phonetic", 120);
        requiredText(word, "meaning", 1200);
        if (word.has("roots")) {
            Object roots = word.opt("roots");
            if (roots instanceof String text) {
                validateText(text, "roots", 1200, true);
            } else if (roots instanceof JSONArray array) {
                validateStringArray(array, "roots", 0, 16, 500);
            } else {
                throw invalid("roots must be a string or string array");
            }
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
        if (word.has("collocations")) {
            validateStringArray(requiredArray(word, "collocations"), "collocations", 1, 16, 500);
        }
        if (word.has("notes")) {
            if (schema == 2 && !capabilities.contains("word-notes-v1")) {
                throw invalid("notes must require word-notes-v1");
            }
            validateNotes(word.optJSONObject("notes"));
        }

        JSONArray questions = requiredArray(word, "questions");
        if (questions.length() < (schema == 1 ? 2 : 1) || questions.length() > (schema == 1 ? 16 : 32)) {
            throw invalid("questions count is outside the supported range");
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
            String questionType = schema == 1 && !question.has("type")
                    ? "choice" : requiredText(question, "type", 40);
            if ("choice".equals(questionType)) {
                if (schema == 2 && !capabilities.contains("choice-v1")) {
                    throw invalid("choice questions must require choice-v1");
                }
            } else if ("input".equals(questionType)) {
                if (schema != 2 || !capabilities.contains("input-v1")) {
                    throw invalid("input questions must require input-v1");
                }
                validateAcceptedAnswers(question);
                validateMatchOptions(question);
            } else if ("recall".equals(questionType)) {
                if (schema != 2 || !capabilities.contains("recall-v1")) {
                    throw invalid("recall questions must require recall-v1");
                }
                requiredText(question, "answer", 2000);
                if (question.has("acceptedAnswers")) validateAcceptedAnswers(question);
                validateMatchOptions(question);
            } else {
                throw invalid("unsupported question type: " + questionType);
            }
            String mode = requiredText(question, "mode", 64);
            if (schema == 1) {
                if ("forward".equals(mode)) forward = true;
                else if ("reverse".equals(mode)) reverse = true;
                else if (!"rare".equals(mode)) throw invalid("question mode must be forward, reverse, or rare");
            } else if (!MODE_PATTERN.matcher(mode).matches()) {
                throw invalid("invalid question mode");
            }
            requiredText(question, "prompt", 500);
            if ("choice".equals(questionType)) {
                JSONArray options = requiredArray(question, "options");
                if (options.length() < 2 || options.length() > 4) {
                    throw invalid("question options must contain 2 to 4 items");
                }
                Set<String> optionValues = new HashSet<>();
                for (int j = 0; j < options.length(); j++) {
                    String option = textValue(options, j, "option", 500, false)
                            .trim().toLowerCase(Locale.ROOT);
                    if (!optionValues.add(option)) throw invalid("question options must be distinct");
                }
                int correctIndex = requiredInt(question, "correctIndex");
                if (correctIndex < 0 || correctIndex >= options.length()) {
                    throw invalid("correctIndex is out of range");
                }
            }
            requiredText(question, "explanation", 1200);
        }
        if (schema == 1 && (!forward || !reverse)) {
            throw invalid("each word requires forward and reverse questions");
        }
    }

    private static void validateAcceptedAnswers(JSONObject question) {
        validateStringArray(requiredArray(question, "acceptedAnswers"), "acceptedAnswers", 1, 32, 2000);
    }

    private static void validateMatchOptions(JSONObject question) {
        for (String field : new String[] {"caseSensitive", "ignorePunctuation"}) {
            if (question.has(field) && !(question.opt(field) instanceof Boolean)) {
                throw invalid(field + " must be boolean");
            }
        }
    }

    private static void validateReviewPolicy(JSONObject policy) {
        if (policy == null) throw invalid("reviewPolicy must be an object");
        JSONArray intervals = requiredArray(policy, "intervalDays");
        if (intervals.length() < 1 || intervals.length() > 32) {
            throw invalid("reviewPolicy.intervalDays count is invalid");
        }
        int previous = 0;
        for (int i = 0; i < intervals.length(); i++) {
            int days = requiredIntValue(intervals, i, "reviewPolicy.intervalDays");
            if (days <= previous || days > 3650) throw invalid("review intervals must increase within 3650 days");
            previous = days;
        }
        for (String field : new String[] {"wrongAfterDays", "hardAfterDays"}) {
            int days = requiredInt(policy, field);
            if (days < 1 || days > 3650) throw invalid("reviewPolicy." + field + " is invalid");
        }
    }

    private static void validateNotes(JSONObject notes) {
        if (notes == null) throw invalid("notes must be an object");
        String[] fields = {"partOfSpeech", "forms", "chunks", "frames", "pitfalls", "retrievalTips"};
        for (String field : fields) {
            if (notes.has(field)) validateStringArray(requiredArray(notes, field), "notes." + field, 0, 24, 500);
        }
        if (notes.has("sections")) {
            JSONArray sections = requiredArray(notes, "sections");
            if (sections.length() > 24) throw invalid("notes.sections has too many items");
            for (int i = 0; i < sections.length(); i++) {
                JSONObject section = objectAt(sections, i, "notes.sections");
                requiredText(section, "title", 100);
                boolean hasBody = section.has("body");
                boolean hasItems = section.has("items");
                if (!hasBody && !hasItems) throw invalid("notes section needs body or items");
                if (hasBody) requiredText(section, "body", 2000);
                if (hasItems) validateStringArray(requiredArray(section, "items"), "notes.sections.items", 0, 32, 1000);
            }
        }
    }

    private static void validateStringArray(
            JSONArray values, String field, int minimum, int maximum, int maxItemLength) {
        if (values.length() < minimum || values.length() > maximum) {
            throw invalid(field + " has an invalid item count");
        }
        for (int i = 0; i < values.length(); i++) textValue(values, i, field, maxItemLength, false);
    }

    private static void validateIdList(
            JSONArray ids, Map<String, JSONObject> words, Set<String> assigned, Set<String> withdrawn,
            String field, boolean allowBaseReferences) {
        for (int i = 0; i < ids.length(); i++) {
            String id = textValue(ids, i, field, 160, false);
            validateWordId(id);
            if (!allowBaseReferences && !words.containsKey(id)) {
                throw invalid(field + " references a missing word");
            }
            if (withdrawn.contains(id)) throw invalid(field + " references a withdrawn word");
            if (!assigned.add(id)) {
                throw invalid("word ids must be unique across newWordIds and reviewWordIds");
            }
        }
    }

    private static void validateWordId(String id) {
        if (!WORD_ID_PATTERN.matcher(id).matches()) {
            throw invalid("invalid word id");
        }
    }

    /** Returns v2 revision, or zero for a validated legacy v1 package. */
    public static int revision(JSONObject pack) {
        if (pack == null) throw invalid("package is required");
        int schema = requiredInt(pack, "schemaVersion");
        if (schema == 1) return 0;
        if (schema != 2) throw invalid("unsupported package schemaVersion");
        int value = requiredInt(pack, "revision");
        if (value < 1) throw invalid("revision must be positive");
        return value;
    }

    /** Compares typed input/recall text using only declared conservative normalization rules. */
    public static boolean matchesAnswer(JSONObject question, String answer) {
        if (question == null || answer == null) throw invalid("question and answer are required");
        String type = requiredText(question, "type", 40);
        if (!"input".equals(type) && !"recall".equals(type)) {
            throw invalid("text matching is only supported for input and recall questions");
        }
        boolean caseSensitive = !question.has("caseSensitive") || Boolean.TRUE.equals(question.opt("caseSensitive"));
        if (question.has("caseSensitive") && !(question.opt("caseSensitive") instanceof Boolean)) {
            throw invalid("caseSensitive must be boolean");
        }
        boolean ignorePunctuation = Boolean.TRUE.equals(question.opt("ignorePunctuation"));
        if (question.has("ignorePunctuation") && !(question.opt("ignorePunctuation") instanceof Boolean)) {
            throw invalid("ignorePunctuation must be boolean");
        }
        JSONArray accepted;
        if (question.has("acceptedAnswers")) {
            accepted = requiredArray(question, "acceptedAnswers");
        } else if ("recall".equals(type)) {
            accepted = new JSONArray().put(requiredText(question, "answer", 2000));
        } else {
            throw invalid("input question must have acceptedAnswers");
        }
        String normalized = normalizeAnswer(answer, caseSensitive, ignorePunctuation);
        for (int i = 0; i < accepted.length(); i++) {
            String candidate = textValue(accepted, i, "acceptedAnswers", 2000, false);
            if (normalized.equals(normalizeAnswer(candidate, caseSensitive, ignorePunctuation))) return true;
        }
        return false;
    }

    private static String normalizeAnswer(String value, boolean caseSensitive, boolean ignorePunctuation) {
        String normalized = Normalizer.normalize(value.trim(), Normalizer.Form.NFC);
        if (!caseSensitive) normalized = normalized.toLowerCase(Locale.ROOT);
        if (!ignorePunctuation) return normalized;
        StringBuilder result = new StringBuilder(normalized.length());
        normalized.codePoints().forEach(codePoint -> {
            int type = Character.getType(codePoint);
            boolean punctuation = type == Character.CONNECTOR_PUNCTUATION
                    || type == Character.DASH_PUNCTUATION || type == Character.START_PUNCTUATION
                    || type == Character.END_PUNCTUATION || type == Character.INITIAL_QUOTE_PUNCTUATION
                    || type == Character.FINAL_QUOTE_PUNCTUATION || type == Character.OTHER_PUNCTUATION;
            if (!punctuation) result.appendCodePoint(codePoint);
        });
        return result.toString();
    }

    /** Applies a full package or one delta to an immutable current package snapshot. */
    public static JSONObject applyPackage(JSONObject existing, JSONObject incoming) {
        validatePackage(incoming);
        int incomingSchema = requiredInt(incoming, "schemaVersion");
        String incomingDate = stringUnchecked(incoming, "date");
        String incomingId = stringUnchecked(incoming, "packageId");
        String incomingKind = incomingSchema == 2 ? stringUnchecked(incoming, "kind") : "full";
        if ("delta".equals(incomingKind) && existing == null) {
            throw invalid("delta package requires a base package");
        }
        if (existing == null) return copyObject(incoming);

        validatePackage(existing);
        int existingSchema = requiredInt(existing, "schemaVersion");
        String existingDate = stringUnchecked(existing, "date");
        String existingId = stringUnchecked(existing, "packageId");

        if (incomingSchema == 1) {
            if (existingSchema == 2) {
                if (incomingDate.compareTo(existingDate) < 0) return copyObject(existing);
                if (incomingDate.compareTo(existingDate) > 0
                        && !incomingId.equals(existingId)) return copyObject(incoming);
                throw invalid("cannot replace a v2 package for the same date or packageId");
            }
            if (incomingDate.compareTo(existingDate) < 0) return copyObject(existing);
            if (incomingDate.equals(existingDate)
                    && canonicalJson(existing).equals(canonicalJson(incoming))) return copyObject(existing);
            return copyObject(incoming);
        }

        if (!incomingId.equals(existingId)) {
            if (!"full".equals(incomingKind)) throw invalid("delta packageId must match its base");
            if (incomingDate.compareTo(existingDate) < 0) return copyObject(existing);
            if (incomingDate.equals(existingDate)) throw invalid("different packageId for the same date");
            return copyObject(incoming);
        }

        if (!incomingDate.equals(existingDate)) {
            throw invalid("package date cannot change across revisions");
        }
        if (existing.has("catalogSha256")
                && !stringUnchecked(existing, "catalogSha256").equals(stringUnchecked(incoming, "catalogSha256"))) {
            throw invalid("catalogSha256 cannot change across package revisions");
        }

        int currentRevision = revision(existing);
        int nextRevision = revision(incoming);
        if (nextRevision < currentRevision) throw invalid("stale package revision");
        if (nextRevision == currentRevision) {
            if ("full".equals(incomingKind)
                    && canonicalJson(existing).equals(canonicalJson(incoming))) return copyObject(existing);
            if ("delta".equals(incomingKind) && sameAppliedDelta(existing, incoming)) return copyObject(existing);
            throw invalid("package revision has conflicting contents");
        }

        if ("full".equals(incomingKind)) {
            requireFullRevisionIdentity(existing, incoming);
            return copyObject(incoming);
        }

        if (existingSchema != 2 || !"full".equals(stringUnchecked(existing, "kind"))) {
            throw invalid("delta base must be a v2 full package");
        }
        if (requiredInt(incoming, "baseRevision") != currentRevision) {
            throw invalid("delta baseRevision does not match the current package");
        }
        if (!stringUnchecked(existing, "catalogSha256").equals(stringUnchecked(incoming, "catalogSha256"))) {
            throw invalid("delta catalogSha256 does not match the base package");
        }
        return applyDelta(existing, incoming);
    }

    private static JSONObject applyDelta(JSONObject existing, JSONObject delta) {
        JSONArray words = copyArray(requiredArray(existing, "words"));
        Map<String, Integer> indexById = new HashMap<>();
        for (int i = 0; i < words.length(); i++) {
            indexById.put(stringUnchecked(objectAt(words, i, "words"), "id"), i);
        }

        Set<String> oldWithdrawn = idSet(requiredArray(existing, "withdrawnWordIds"), "withdrawnWordIds");
        JSONArray upserts = requiredArray(delta, "words");
        Set<String> activeBaseIds = new HashSet<>(indexById.keySet());
        Set<String> deltaWithdrawn = idSet(requiredArray(delta, "withdrawnWordIds"), "withdrawnWordIds");
        Set<String> withdrawableIds = new HashSet<>(activeBaseIds);
        withdrawableIds.addAll(oldWithdrawn);
        if (!withdrawableIds.containsAll(deltaWithdrawn)) {
            throw invalid("delta may withdraw only words in its base or prior tombstones");
        }
        for (int i = 0; i < upserts.length(); i++) {
            JSONObject word = objectAt(upserts, i, "words");
            String id = stringUnchecked(word, "id");
            if (oldWithdrawn.contains(id)) throw invalid("withdrawn word id cannot be restored");
            Integer index = indexById.get(id);
            if (index == null) {
                indexById.put(id, words.length());
                words.put(copyObject(word));
            } else {
                JSONObject priorWord = objectAt(words, index, "words");
                if (!stringUnchecked(priorWord, "word").equals(stringUnchecked(word, "word"))) {
                    throw invalid("word identity cannot change across revisions");
                }
                JSONArray replaced = new JSONArray();
                for (int j = 0; j < words.length(); j++) {
                    replaced.put(j == index ? copyObject(word) : copyObject(objectAt(words, j, "words")));
                }
                words = replaced;
            }
        }

        Set<String> withdrawn = new LinkedHashSet<>(oldWithdrawn);
        withdrawn.addAll(idSet(requiredArray(delta, "withdrawnWordIds"), "withdrawnWordIds"));
        JSONArray activeWords = new JSONArray();
        for (int i = 0; i < words.length(); i++) {
            JSONObject word = objectAt(words, i, "words");
            if (!withdrawn.contains(stringUnchecked(word, "id"))) activeWords.put(copyObject(word));
        }
        JSONArray withdrawnArray = new JSONArray();
        List<String> orderedWithdrawn = new ArrayList<>(withdrawn);
        Collections.sort(orderedWithdrawn);
        for (String id : orderedWithdrawn) withdrawnArray.put(id);

        JSONObject result = copyObject(existing);
        for (java.util.Iterator<String> it = delta.keys(); it.hasNext();) {
            String key = it.next();
            if (!"words".equals(key) && !"withdrawnWordIds".equals(key)
                    && !"newWordIds".equals(key) && !"reviewWordIds".equals(key)
                    && !"kind".equals(key) && !"baseRevision".equals(key)) {
                putValue(result, key, copyJsonValue(delta.opt(key)));
            }
        }
        putValue(result, "schemaVersion", 2);
        putValue(result, "packageId", stringUnchecked(delta, "packageId"));
        putValue(result, "date", stringUnchecked(delta, "date"));
        putValue(result, "catalogSha256", stringUnchecked(delta, "catalogSha256"));
        putValue(result, "revision", requiredInt(delta, "revision"));
        putValue(result, "kind", "full");
        putValue(result, "newWordLimit", requiredInt(delta, "newWordLimit"));
        putValue(result, "words", activeWords);
        putValue(result, "withdrawnWordIds", withdrawnArray);
        putValue(result, "newWordIds", copyArray(requiredArray(delta, "newWordIds")));
        putValue(result, "reviewWordIds", copyArray(requiredArray(delta, "reviewWordIds")));
        result.remove("baseRevision");

        Set<String> capabilities = new LinkedHashSet<>(validateCapabilities(
                requiredArray(existing, "requiredCapabilities")));
        capabilities.addAll(validateCapabilities(requiredArray(delta, "requiredCapabilities")));
        capabilities.remove("package-delta-v1");
        if (!withdrawn.isEmpty()) capabilities.add("word-withdrawal-v1");
        List<String> orderedCapabilities = new ArrayList<>(capabilities);
        Collections.sort(orderedCapabilities);
        JSONArray capabilityArray = new JSONArray();
        for (String capability : orderedCapabilities) capabilityArray.put(capability);
        putValue(result, "requiredCapabilities", capabilityArray);

        validatePackage(result);
        return result;
    }

    private static boolean sameAppliedDelta(JSONObject existing, JSONObject delta) {
        if (requiredInt(existing, "schemaVersion") != 2
                || !stringUnchecked(existing, "packageId").equals(stringUnchecked(delta, "packageId"))
                || !stringUnchecked(existing, "catalogSha256").equals(stringUnchecked(delta, "catalogSha256"))
                || !stringUnchecked(existing, "date").equals(stringUnchecked(delta, "date"))) return false;
        if (requiredInt(delta, "baseRevision") >= revision(existing)
                || requiredInt(delta, "newWordLimit") != requiredInt(existing, "newWordLimit")) return false;
        if (!canonicalJson(requiredArray(existing, "newWordIds")).equals(canonicalJson(requiredArray(delta, "newWordIds")))
                || !canonicalJson(requiredArray(existing, "reviewWordIds")).equals(canonicalJson(requiredArray(delta, "reviewWordIds")))) return false;
        Map<String, JSONObject> active = new HashMap<>();
        JSONArray words = requiredArray(existing, "words");
        for (int i = 0; i < words.length(); i++) {
            JSONObject word = objectAt(words, i, "words");
            active.put(stringUnchecked(word, "id"), word);
        }
        for (int i = 0; i < requiredArray(delta, "words").length(); i++) {
            JSONObject word = objectAt(requiredArray(delta, "words"), i, "words");
            JSONObject current = active.get(stringUnchecked(word, "id"));
            if (current == null || !canonicalJson(current).equals(canonicalJson(word))) return false;
        }
        Set<String> tombstones = idSet(requiredArray(existing, "withdrawnWordIds"), "withdrawnWordIds");
        Set<String> deltaTombstones = idSet(requiredArray(delta, "withdrawnWordIds"), "withdrawnWordIds");
        if (!tombstones.containsAll(deltaTombstones)) return false;
        for (java.util.Iterator<String> it = delta.keys(); it.hasNext();) {
            String key = it.next();
            if ("words".equals(key) || "withdrawnWordIds".equals(key) || "newWordIds".equals(key)
                    || "reviewWordIds".equals(key) || "kind".equals(key) || "baseRevision".equals(key)
                    || "requiredCapabilities".equals(key)) continue;
            if (!existing.has(key)
                    || !canonicalJson(existing.opt(key)).equals(canonicalJson(delta.opt(key)))) return false;
        }
        return true;
    }

    private static void requireFullRevisionIdentity(JSONObject existing, JSONObject incoming) {
        Set<String> oldTombstones = requiredInt(existing, "schemaVersion") == 2
                ? idSet(requiredArray(existing, "withdrawnWordIds"), "withdrawnWordIds")
                : Collections.emptySet();
        Set<String> newTombstones = idSet(requiredArray(incoming, "withdrawnWordIds"), "withdrawnWordIds");
        if (!newTombstones.containsAll(oldTombstones)) {
            throw invalid("full package must retain prior tombstones");
        }
        Map<String, String> incomingWordForms = new HashMap<>();
        JSONArray newWords = requiredArray(incoming, "words");
        for (int i = 0; i < newWords.length(); i++) {
            JSONObject word = objectAt(newWords, i, "words");
            incomingWordForms.put(stringUnchecked(word, "id"), stringUnchecked(word, "word"));
        }
        JSONArray oldWords = requiredArray(existing, "words");
        for (int i = 0; i < oldWords.length(); i++) {
            JSONObject word = objectAt(oldWords, i, "words");
            String id = stringUnchecked(word, "id");
            String newForm = incomingWordForms.get(id);
            if (newForm != null && !stringUnchecked(word, "word").equals(newForm)) {
                throw invalid("word identity cannot change across revisions");
            }
            if (newForm == null && !newTombstones.contains(id)) {
                throw invalid("removed words must be withdrawn explicitly");
            }
        }
    }

    private static Set<String> idSet(JSONArray ids, String field) {
        Set<String> result = new HashSet<>();
        for (int i = 0; i < ids.length(); i++) result.add(textValue(ids, i, field, 160, false));
        return result;
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
        Map<String, String> eventIdByDeviceSequence = new HashMap<>();
        appendEvents(left, byId, canonicalById, eventIdByDeviceSequence);
        appendEvents(right, byId, canonicalById, eventIdByDeviceSequence);

        List<JSONObject> ordered = new ArrayList<>(byId.values());
        ordered.sort(EVENT_ORDER);
        JSONArray result = new JSONArray();
        for (JSONObject event : ordered) {
            result.put(copyObject(event));
        }
        return result;
    }

    private static void appendEvents(
            JSONArray source, Map<String, JSONObject> byId, Map<String, String> canonicalById,
            Map<String, String> eventIdByDeviceSequence) {
        for (int i = 0; i < source.length(); i++) {
            JSONObject event = objectAt(source, i, "events");
            validateEvent(event);
            String id = requiredText(event, "id", 64);
            String sequenceKey = stringUnchecked(event, "deviceId") + "\u0000"
                    + longUnchecked(event, "seq");
            String sequenceOwner = eventIdByDeviceSequence.putIfAbsent(sequenceKey, id);
            if (sequenceOwner != null && !sequenceOwner.equals(id)) {
                throw invalid("device seq has conflicting event ids");
            }
            String canonical = canonicalJson(event);
            String prior = canonicalById.get(id);
            if (prior != null && !prior.equals(canonical)) {
                throw invalid("event id has conflicting contents");
            }
            if (prior == null) {
                canonicalById.put(id, canonical);
                // The result is detached below; no intermediate copy is mutated here.
                byId.put(id, event);
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
        if (event.has("requiredCapabilities")) {
            validateCapabilities(requiredArray(event, "requiredCapabilities"));
        }
        validateOptionalEventFields(event);
        if ("undo".equals(type)) {
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

        String wordId = requiredText(event, "wordId", 160);
        validateWordId(wordId);
        requiredText(event, "packageId", 128);
        String mode = requiredText(event, "mode", 64);
        if (!MODE_PATTERN.matcher(mode).matches()) {
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
        if ("known".equals(result) && (!"skip".equals(mode) || !"primary".equals(attempt))) {
            throw invalid("known events must be primary skip answers");
        }
        Object evidence = event.opt("evidence");
        boolean legacyRecall = !event.has("evidence") && "recall".equals(mode);
        boolean selfReport = "self-report".equals(evidence) || legacyRecall;
        if (("remember".equals(result) || "hard".equals(result) || "forgot".equals(result))
                && !selfReport) {
            throw invalid("self-assessed results require recall mode or self-report evidence");
        }
        if (("correct".equals(result) || "wrong".equals(result)) && legacyRecall) {
            throw invalid("legacy recall mode must use self-assessed results");
        }
    }

    private static void validateOptionalEventFields(JSONObject event) {
        if (event.has("questionId")) requiredText(event, "questionId", 160);
        if (event.has("sessionId")) requiredText(event, "sessionId", 128);
        if (event.has("evidence")) {
            Object evidence = event.opt("evidence");
            if (!(evidence instanceof String) && !(evidence instanceof JSONObject)) {
                throw invalid("evidence must be a string or object");
            }
            if (evidence instanceof String text) validateText(text, "evidence", 2000, true);
        }
        if (event.has("packageRevision") && requiredInt(event, "packageRevision") < 0) {
            throw invalid("packageRevision must be non-negative");
        }
        if (event.has("scheduleDays")) {
            int days = requiredInt(event, "scheduleDays");
            if (days < 1 || days > 3650) throw invalid("scheduleDays must be between 1 and 3650");
        }
    }

    private static void validateDeviceId(String deviceId) {
        if (!DEVICE_ID_PATTERN.matcher(deviceId).matches()) {
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
        return projectActive(activeEvents(events, now), now);
    }

    private static Map<String, Card> projectActive(List<JSONObject> active, long now) {
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
            boolean selfReport = "self-report".equals(event.opt("evidence"))
                    || (!event.has("evidence") && "recall".equals(mode));
            int scheduledDays = event.has("scheduleDays")
                    ? requiredInt(event, "scheduleDays") : -1;
            if (primary && !"known".equals(result)) {
                // A later real answer opts a previously skipped card back into normal study.
                card.known = false;
            }
            switch (result) {
                case "correct" -> {
                    if (selfReport) card.selfRemember++;
                    else if (assisted) card.assistedRight++;
                    else card.objectiveRight++;
                }
                case "wrong" -> {
                    if (selfReport) card.selfForgot++;
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
            if (("correct".equals(result) || "remember".equals(result)) && assisted) {
                // Seeing the explanation is useful practice, but does not advance mastery.
                // Give a new card its first revisit while leaving an existing due state intact.
                if (card.dueAt == 0L) {
                    card.stage = 0;
                    card.dueAt = dueAtCalendarDaysLater(at,
                            scheduledDays > 0 ? scheduledDays : INTERVAL_DAYS[0]);
                }
                continue;
            }
            if ("correct".equals(result) || "remember".equals(result)) {
                if (card.dueAt == 0L) {
                    card.stage = 0;
                    card.dueAt = dueAtCalendarDaysLater(at,
                            scheduledDays > 0 ? scheduledDays : INTERVAL_DAYS[0]);
                } else if (at >= card.dueAt) {
                    int maxStage = scheduledDays > 0 ? 31 : INTERVAL_DAYS.length - 1;
                    card.stage = Math.min(card.stage + 1, maxStage);
                    card.dueAt = dueAtCalendarDaysLater(at,
                            scheduledDays > 0 ? scheduledDays : INTERVAL_DAYS[card.stage]);
                }
            } else if ("wrong".equals(result) || "forgot".equals(result) || "hard".equals(result)) {
                card.stage = 0;
                card.dueAt = dueAtCalendarDaysLater(at,
                        scheduledDays > 0 ? scheduledDays : INTERVAL_DAYS[0]);
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
        if (events == null || now < 0 || newLimit < -1 || newLimit > 10000) {
            throw invalid("events and a non-negative now are required");
        }
        if (cachedWordsById == null) {
            cachedWordsById = new JSONObject();
        }

        List<JSONObject> active = activeEvents(events, now);
        Map<String, Card> cards = projectActive(active, now);
        LocalDate today = Instant.ofEpochMilli(now).atZone(STUDY_ZONE).toLocalDate();
        LocalDate packageDate = LocalDate.parse(stringUnchecked(pack, "date"), PACKAGE_DATE);
        if (packageDate.isAfter(today)) return Collections.emptyList();
        Set<String> primaryToday = new HashSet<>();
        for (JSONObject event : active) {
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
        Set<String> withdrawnWords = new HashSet<>();
        if (requiredInt(pack, "schemaVersion") == 2) {
            withdrawnWords = idSet(requiredArray(pack, "withdrawnWordIds"), "withdrawnWordIds");
        }
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
                    && !withdrawnWords.contains(card.wordId)
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
                    && !withdrawnWords.contains(id)
                    && hasDefinition(id, packageWords, cachedWordsById)) {
                tasks.add(new Task(id, true, "primary"));
                queued.add(id);
            }
        }

        int packageLimit = requiredInt(pack, "newWordLimit");
        int effectiveLimit = packageLimit < 0 ? newLimit : newLimit < 0
                ? packageLimit : Math.min(packageLimit, newLimit);
        int alreadyNewToday = todayCountsActive(active, now)[0];
        int permittedNew = effectiveLimit < 0 ? MAX_WORDS
                : Math.max(0, effectiveLimit - alreadyNewToday);
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
        return todayCountsActive(activeEvents(events, now), now);
    }

    private static int[] todayCountsActive(List<JSONObject> active, long now) {
        LocalDate today = Instant.ofEpochMilli(now).atZone(STUDY_ZONE).toLocalDate();
        Map<String, Boolean> primaryKindByWord = new LinkedHashMap<>();
        Set<String> skippedWords = new HashSet<>();
        Set<String> assistedRightWords = new HashSet<>();

        for (JSONObject event : active) {
            if (!"answer".equals(stringUnchecked(event, "type"))) {
                continue;
            }
            long at = longUnchecked(event, "at");
            if (!today.equals(Instant.ofEpochMilli(at).atZone(STUDY_ZONE).toLocalDate())) {
                continue;
            }
            String wordId = stringUnchecked(event, "wordId");
            String result = stringUnchecked(event, "result");
            boolean primary = "primary".equals(stringUnchecked(event, "attempt"));
            if (primary && "correct".equals(result) && Boolean.TRUE.equals(event.opt("assisted"))) {
                assistedRightWords.add(wordId);
            }
            if (!primary) continue;
            if ("known".equals(result)) {
                skippedWords.add(wordId);
            } else {
                boolean isReview = Boolean.TRUE.equals(event.opt("isReview"));
                Boolean priorKind = primaryKindByWord.get(wordId);
                if (priorKind == null || (!isReview && priorKind)) {
                    primaryKindByWord.put(wordId, isReview);
                }
            }
        }
        Set<String> newWords = new HashSet<>();
        Set<String> reviewWords = new HashSet<>();
        for (Map.Entry<String, Boolean> entry : primaryKindByWord.entrySet()) {
            (entry.getValue() ? reviewWords : newWords).add(entry.getKey());
        }
        skippedWords.removeAll(primaryKindByWord.keySet());
        return new int[] {
                newWords.size(), reviewWords.size(), skippedWords.size(), assistedRightWords.size()
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

    private static int requiredIntValue(JSONArray array, int index, String field) {
        Object value = array.opt(index);
        long exact = exactInteger(value, field);
        if (exact < Integer.MIN_VALUE || exact > Integer.MAX_VALUE) {
            throw invalid(field + " is out of range");
        }
        return (int) exact;
    }

    private static long requiredLong(JSONObject object, String field) {
        return exactInteger(object.opt(field), field);
    }

    private static long exactInteger(Object value, String field) {
        if (!(value instanceof Number number) || number instanceof Double || number instanceof Float) {
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
        return JsonCopies.object(object);
    }

    private static JSONArray copyArray(JSONArray array) {
        return JsonCopies.array(array);
    }

    private static Object copyJsonValue(Object value) {
        if (value instanceof JSONObject object) return copyObject(object);
        if (value instanceof JSONArray array) return copyArray(array);
        return value;
    }

    private static void putValue(JSONObject object, String key, Object value) {
        try {
            object.put(key, value);
        } catch (JSONException e) {
            throw invalid("could not update package field");
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
