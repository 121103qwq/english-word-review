package cn.wordwatch.app.core;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Locale;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Bounded GitHub Contents API sync for packages and immutable per-device event batches. */
public final class GitHubClient {
    public static final String DEFAULT_OWNER = "121103qwq";
    public static final String DEFAULT_REPO = "english-word-review-data";
    public static final String DEFAULT_BRANCH = "main";
    public static final String CONTENT_PREFIX = "watch-words";
    private static final String PACKAGE_INDEX_FILE = "index.json";

    private static final String API_ROOT = "https://api.github.com";
    /** Contents API returns base64 JSON, so allow an 8 MiB decoded package with bounded overhead. */
    private static final int MAX_RESPONSE_BYTES = 12 * 1024 * 1024;
    private static final int MAX_DECODED_CONTENT_BYTES = 8 * 1024 * 1024;
    /** Keep a multi-day package fetch bounded on watch memory while allowing ordinary 14-day packs. */
    private static final int MAX_SYNC_PACKAGE_BYTES = 24 * 1024 * 1024;
    /** Keep each mutable Contents API object comfortably below GitHub's 1 MiB JSON limit. */
    public static final int MAX_EVENT_BATCH_BYTES = 256 * 1024;
    private static final int MAX_READ_RETRIES = 2;
    private static final int MAX_UPLOAD_RETRIES = 2;
    private static final long MAX_INLINE_RETRY_DELAY_MS = 10_000L;
    private static final int CONNECT_TIMEOUT_MS = 8_000;
    private static final int READ_TIMEOUT_MS = 15_000;
    private static final Pattern EVENT_FILE =
            Pattern.compile("^([A-Za-z0-9][A-Za-z0-9._-]{0,127})-(\\d{4}-(?:0[1-9]|1[0-2]))(?:-part-([0-9a-f]{20}))?\\.json$");
    private static final Pattern DAILY_PACKAGE_PATH =
            Pattern.compile("^daily/(\\d{4}-\\d{2}-\\d{2})\\.json$");

    private final Transport transport;
    private final String contentPrefix;
    private final Sleeper sleeper;
    private final RequestGuard requestGuard;

    public GitHubClient() {
        this(CONTENT_PREFIX, new HttpTransport());
    }

    public GitHubClient(Transport transport) {
        this(CONTENT_PREFIX, transport);
    }

    /** Uses a caller-selected safe relative directory, useful for isolated verification runs. */
    public GitHubClient(String contentPrefix) {
        this(contentPrefix, new HttpTransport());
    }

    public GitHubClient(String contentPrefix, Transport transport) {
        this(contentPrefix, transport, Thread::sleep, () -> true);
    }

    /** Constructor with deterministic retry and request-guard hooks for app orchestration/tests. */
    public GitHubClient(String contentPrefix, Transport transport, Sleeper sleeper, RequestGuard requestGuard) {
        if (transport == null) {
            throw new IllegalArgumentException("transport is required");
        }
        if (sleeper == null || requestGuard == null) {
            throw new IllegalArgumentException("retry and request guard hooks are required");
        }
        this.contentPrefix = validateContentPrefix(contentPrefix);
        this.transport = transport;
        this.sleeper = sleeper;
        this.requestGuard = requestGuard;
    }

    /**
     * Downloads and validates the current package, merges all recognized event files with local
     * events, and uploads only this device's missing events grouped by Shanghai calendar month.
     */
    public SyncResult sync(
            String owner,
            String repo,
            String branch,
            String token,
            String deviceId,
            JSONArray localEvents) throws IOException {
        validateRepoPart(owner, "owner");
        validateRepoPart(repo, "repo");
        if (branch == null || branch.trim().isEmpty() || branch.length() > 255 || containsControl(branch)) {
            throw new IllegalArgumentException("invalid branch");
        }
        if (token == null || token.trim().isEmpty() || containsControl(token)) {
            throw new IllegalArgumentException("token is required");
        }
        validateDeviceId(deviceId);
        if (localEvents == null) {
            throw new IllegalArgumentException("localEvents is required");
        }

        JSONArray validatedLocal = StudyEngine.mergeEvents(localEvents, new JSONArray());
        SyncAccumulator outcome = new SyncAccumulator();
        RemoteState remote = new RemoteState();

        // Event upload is independent from package compatibility. Read remote events first so a
        // missing or future-schema word package cannot prevent local study records from syncing.
        try {
            remote = downloadRemoteEvents(owner, repo, branch, token);
            outcome.eventWarnings.addAll(remote.warnings);
            if (remote.scanFailure != null) {
                outcome.recordFailure(remote.scanFailure);
                outcome.eventWarnings.add(eventFailureWarning(remote.scanFailure));
            }
        } catch (SyncException e) {
            outcome.eventFailure = e;
            outcome.eventWarnings.add("云端记录目录暂不可用，离线记录仍保留待重试");
        }

        if (outcome.eventFailure == null) {
            Map<String, List<JSONObject>> ownEventsByMonth = groupOwnDeviceEvents(validatedLocal, deviceId);
            for (Map.Entry<String, List<JSONObject>> entry : ownEventsByMonth.entrySet()) {
                String month = entry.getKey();
                String groupKey = eventGroupKey(deviceId, month);
                JSONArray localForMonth = toArray(entry.getValue());
                JSONArray existingForMonth = remote.eventsByGroup.getOrDefault(groupKey, new JSONArray());
                try {
                    StudyEngine.mergeEvents(existingForMonth, localForMonth);
                } catch (IllegalArgumentException e) {
                    outcome.eventComplete = false;
                    outcome.recordFailure(new SyncException(
                            "local and remote event IDs conflict", 0, FailureKind.PROTOCOL, 0L));
                    outcome.eventWarnings.add("本机与云端有同 ID 不同内容的记录，该月份已保留本机数据并跳过上传");
                    continue;
                }

                addConfirmedLocalIds(outcome.acknowledgedEventIds, localForMonth, existingForMonth);
                int missingCount = countMissing(localForMonth, existingForMonth);
                if (missingCount == 0) {
                    continue;
                }

                if (remote.badGroups.contains(groupKey)) {
                    outcome.eventComplete = false;
                    SyncException badFileFailure = remote.badGroupFailures.get(groupKey);
                    outcome.recordFailure(badFileFailure == null
                            ? new SyncException("remote event batch failed validation", 0, FailureKind.PROTOCOL, 0L)
                            : badFileFailure);
                    outcome.eventWarnings.add("该月份有无法验证的本机云端批次，已隔离并跳过新上传");
                    continue;
                }

                JSONArray missingEvents = selectMissing(localForMonth, existingForMonth);
                List<JSONArray> batches;
                try {
                    batches = splitBatches(deviceId, missingEvents);
                } catch (SyncException e) {
                    outcome.eventComplete = false;
                    outcome.recordFailure(e);
                    outcome.eventWarnings.add("本机记录批次超过安全大小，尚未上传");
                    continue;
                }

                String baseName = deviceId + "-" + month + ".json";
                RemoteFile baseFile = remote.filesByName.get(baseName);
                JSONArray baseEvents = baseFile == null ? new JSONArray() : baseFile.events;
                JSONArray baseCombined;
                try {
                    baseCombined = StudyEngine.mergeEvents(baseEvents, missingEvents);
                } catch (IllegalArgumentException e) {
                    outcome.eventComplete = false;
                    outcome.recordFailure(new SyncException(
                            "local and remote event IDs conflict", 0, FailureKind.PROTOCOL, 0L));
                    outcome.eventWarnings.add("该月份存在冲突记录，保留本机副本并跳过上传");
                    continue;
                }

                List<UploadPlan> plans = new ArrayList<>();
                if (batches.size() == 1 && encodedBatchSize(deviceId, baseCombined) <= MAX_EVENT_BATCH_BYTES) {
                    plans.add(new UploadPlan(baseName, month, deviceId, baseCombined, baseFile));
                } else {
                    for (JSONArray batch : batches) {
                        String partName = deviceId + "-" + month + "-part-" + batchId(batch) + ".json";
                        plans.add(new UploadPlan(partName, month, deviceId, batch,
                                remote.filesByName.get(partName)));
                    }
                }

                for (UploadPlan plan : plans) {
                    UploadOutcome uploaded = uploadPlan(
                            owner, repo, branch, token, plan, remote, localForMonth);
                    outcome.acknowledgedEventIds.addAll(uploaded.acknowledgedEventIds);
                    outcome.uploadedCount += uploaded.uploadedCount;
                    if (!uploaded.complete) {
                        outcome.eventComplete = false;
                        outcome.recordFailure(uploaded.failure);
                        if (uploaded.failure != null) {
                            outcome.eventWarnings.add(eventFailureWarning(uploaded.failure));
                        } else {
                            outcome.eventWarnings.add("有一批记录未能确认上传，已保留在本机");
                        }
                    }
                }
            }
        } else {
            outcome.eventComplete = false;
        }

        PackageDownload packages = downloadPackages(owner, repo, branch, token);
        JSONObject pack = packages.current;
        outcome.packFailure = packages.failure;
        outcome.packageWarning = joinWarnings(packages.warnings);

        JSONArray merged;
        try {
            merged = StudyEngine.mergeEvents(remote.events, validatedLocal);
        } catch (IllegalArgumentException e) {
            // Keep local progress and confirmed remote records even if another remote file reused
            // a local event ID with different contents.
            merged = validatedLocal;
            outcome.eventComplete = false;
            outcome.recordFailure(new SyncException(
                    "local and remote event IDs conflict", 0, FailureKind.PROTOCOL, 0L));
            outcome.eventWarnings.add("云端冲突记录已隔离，本机记录保持不变");
        }
        return new SyncResult(pack, packages.packages, merged, outcome.acknowledgedEventIds, outcome.uploadedCount,
                outcome.eventComplete, outcome.eventFailure, outcome.packFailure,
                outcome.packageWarning, joinWarnings(outcome.eventWarnings));
    }

    private PackageDownload downloadPackages(String owner, String repo, String branch, String token) {
        PackageDownload result = new PackageDownload();
        int retainedBytes = 0;
        try {
            PackageDocument current = downloadPackage(owner, repo, branch, token, contentPrefix + "/current.json");
            result.current = current.pack;
            retainedBytes += current.rawBytes.length;
            result.packages.add(current.pack);
        } catch (SyncException e) {
            result.failure = e;
            result.warnings.add("当前词包未更新：" + packageFailureWarning(e));
        }

        JSONObject indexFile;
        try {
            indexFile = getContentFile(owner, repo, contentPrefix + "/" + PACKAGE_INDEX_FILE,
                    branch, token, true);
        } catch (SyncException e) {
            result.recordFailure(e);
            result.warnings.add("多日词包索引未读取：" + packageFailureWarning(e));
            return result;
        } catch (IOException e) {
            SyncException failure = new SyncException("GitHub network request failed", 0,
                    FailureKind.NETWORK, 0L);
            result.recordFailure(failure);
            result.warnings.add("多日词包索引未读取：" + packageFailureWarning(failure));
            return result;
        }
        if (indexFile == null) return result;

        List<IndexEntry> entries;
        try {
            entries = parsePackageIndex(decodeContents(indexFile));
        } catch (IOException | IllegalArgumentException e) {
            SyncException failure = e instanceof SyncException ? (SyncException) e
                    : new SyncException("GitHub package index is invalid", 0, FailureKind.PROTOCOL, 0L);
            result.recordFailure(failure);
            result.warnings.add("多日词包索引格式无效，已跳过索引包");
            return result;
        }

        Set<String> retainedDates = new HashSet<>();
        if (result.current != null) retainedDates.add(result.current.optString("date"));
        for (IndexEntry entry : entries) {
            if (retainedDates.contains(entry.date)) continue;
            String path = contentPrefix + "/" + entry.path;
            try {
                byte[] raw = getRawContent(owner, repo, path, branch, token, true);
                if (raw == null) continue; // A removed historical package is optional.
                if (!entry.sha256.equalsIgnoreCase(sha256(raw))) {
                    result.recordFailure(new SyncException("indexed package checksum mismatch", 0,
                            FailureKind.PROTOCOL, 0L));
                    result.warnings.add("多日词包校验失败，已跳过 " + entry.date);
                    continue;
                }
                if (retainedBytes + raw.length > MAX_SYNC_PACKAGE_BYTES) {
                    result.recordFailure(new SyncException("indexed package set exceeded the memory limit", 0,
                            FailureKind.TOO_LARGE, 0L));
                    result.warnings.add("多日词包总量超过手表安全上限，已停止继续下载");
                    break;
                }
                JSONObject pack = parseAndValidatePackage(raw, "indexed");
                if (!entry.date.equals(pack.optString("date"))) {
                    throw new SyncException("indexed package date did not match its path", 0,
                            FailureKind.PROTOCOL, 0L);
                }
                retainedBytes += raw.length;
                retainedDates.add(entry.date);
                result.packages.add(pack);
            } catch (SyncException e) {
                if (e.statusCode == 404) continue;
                result.recordFailure(e);
                result.warnings.add("多日词包下载或校验失败，已跳过 " + entry.date);
                if (e.failureKind == FailureKind.CANCELLED || e.failureKind == FailureKind.AUTH
                        || e.failureKind == FailureKind.RATE_LIMITED || e.failureKind == FailureKind.NETWORK
                        || e.failureKind == FailureKind.SERVER) break;
            } catch (IOException e) {
                SyncException failure = new SyncException("GitHub network request failed", 0,
                        FailureKind.NETWORK, 0L);
                result.recordFailure(failure);
                result.warnings.add("多日词包下载失败，已跳过 " + entry.date);
                break;
            }
        }
        return result;
    }

    private PackageDocument downloadPackage(
            String owner, String repo, String branch, String token, String path) throws SyncException {
        try {
            byte[] raw = getRawContent(owner, repo, path, branch, token, false);
            if (raw == null) throw new SyncException("GitHub package is missing", 404, FailureKind.NOT_FOUND, 0L);
            return new PackageDocument(parseAndValidatePackage(raw, "current"), raw);
        } catch (SyncException e) {
            throw e;
        } catch (IOException e) {
            throw new SyncException("GitHub network request failed", 0, FailureKind.NETWORK, 0L);
        }
    }

    private static JSONObject parseAndValidatePackage(byte[] raw, String label) throws SyncException {
        JSONObject pack;
        try {
            pack = new JSONObject(new String(raw, StandardCharsets.UTF_8));
        } catch (JSONException e) {
            throw new SyncException("GitHub " + label + " package is invalid", 0, FailureKind.PROTOCOL, 0L);
        }
        try {
            StudyEngine.validatePackage(pack);
        } catch (IllegalArgumentException e) {
            throw new SyncException("GitHub " + label + " package failed validation", 0,
                    FailureKind.PROTOCOL, 0L);
        }
        return pack;
    }

    private static List<IndexEntry> parsePackageIndex(byte[] raw) throws IOException {
        JSONObject index;
        try {
            index = new JSONObject(new String(raw, StandardCharsets.UTF_8));
        } catch (JSONException e) {
            throw new SyncException("GitHub package index is invalid", 0, FailureKind.PROTOCOL, 0L);
        }
        if (integerValue(index, "schemaVersion") != 1) {
            throw new SyncException("GitHub package index schema is unsupported", 0,
                    FailureKind.PROTOCOL, 0L);
        }
        JSONArray packages = arrayValue(index, "packages");
        if (packages.length() > 14) {
            throw new SyncException("GitHub package index has too many entries", 0,
                    FailureKind.PROTOCOL, 0L);
        }
        List<IndexEntry> entries = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < packages.length(); i++) {
            JSONObject item = jsonObjectAt(packages, i, "package index");
            String path = stringValue(item, "path");
            Matcher matcher = DAILY_PACKAGE_PATH.matcher(path);
            if (!matcher.matches()) {
                throw new SyncException("GitHub package index contains an unsafe path", 0,
                        FailureKind.PROTOCOL, 0L);
            }
            String date = matcher.group(1);
            try {
                LocalDate.parse(date);
            } catch (RuntimeException e) {
                throw new SyncException("GitHub package index contains an invalid date", 0,
                        FailureKind.PROTOCOL, 0L);
            }
            String sha = stringValue(item, "sha256");
            if (!sha.matches("(?i)[0-9a-f]{64}") || !seen.add(date)) {
                throw new SyncException("GitHub package index entry is invalid or duplicated", 0,
                        FailureKind.PROTOCOL, 0L);
            }
            entries.add(new IndexEntry(path, date, sha.toLowerCase(Locale.ROOT)));
        }
        return entries;
    }

    private static String sha256(byte[] bytes) throws SyncException {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte value : digest) hex.append(String.format(Locale.ROOT, "%02x", value));
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new SyncException("SHA-256 is unavailable", 0, FailureKind.PROTOCOL, 0L);
        }
    }

    private RemoteState downloadRemoteEvents(
            String owner, String repo, String branch, String token) throws IOException {
        RemoteState state = new RemoteState();
        String directoryPath = contentPrefix + "/events";
        String url = contentsUrl(owner, repo, directoryPath, branch);
        Response response = request("GET", url, token, null, Collections.singleton(200), true);
        if (response.statusCode == 404) {
            return state;
        }

        JSONArray entries;
        try {
            entries = new JSONArray(response.bodyText());
        } catch (JSONException e) {
            throw new SyncException("GitHub event directory response is invalid", 0);
        }
        for (int i = 0; i < entries.length(); i++) {
            JSONObject entry;
            try {
                entry = jsonObjectAt(entries, i, "GitHub event directory");
            } catch (IllegalArgumentException e) {
                state.scanFailure = new SyncException(
                        "GitHub event directory contains an invalid entry", 0, FailureKind.PROTOCOL, 0L);
                break;
            }
            if (!"file".equals(entry.optString("type"))) {
                continue;
            }
            String name = entry.optString("name", "");
            Matcher matcher = EVENT_FILE.matcher(name);
            if (!matcher.matches()) {
                continue;
            }
            String deviceId = matcher.group(1);
            String month = matcher.group(2);
            try {
                String path = directoryPath + "/" + name;
                JSONObject fileObject = getContentFile(owner, repo, path, branch, token, false);
                if (fileObject == null) {
                    throw new SyncException("GitHub event file disappeared", 404, FailureKind.NOT_FOUND, 0L);
                }
                RemoteFile remoteFile = parseEventFile(name, month, deviceId, fileObject);
                JSONArray nextEvents = StudyEngine.mergeEvents(state.events, remoteFile.events);
                String groupKey = eventGroupKey(deviceId, month);
                JSONArray groupEvents = StudyEngine.mergeEvents(
                        state.eventsByGroup.getOrDefault(groupKey, new JSONArray()), remoteFile.events);
                state.filesByName.put(name, remoteFile);
                state.events = nextEvents;
                state.eventsByGroup.put(groupKey, groupEvents);
            } catch (IOException | IllegalArgumentException e) {
                if (e instanceof SyncException
                        && ((SyncException) e).failureKind == FailureKind.CANCELLED) {
                    throw (SyncException) e;
                }
                if (isRemoteScanFailure(e)) {
                    state.scanFailure = (SyncException) e;
                    break;
                }
                state.badGroups.add(eventGroupKey(deviceId, month));
                SyncException fileFailure = e instanceof SyncException ? (SyncException) e
                        : new SyncException("GitHub event file failed validation", 0, FailureKind.PROTOCOL, 0L);
                state.badGroupFailures.putIfAbsent(eventGroupKey(deviceId, month), fileFailure);
                state.warnings.add("云端事件文件已隔离：" + name);
            }
        }
        return state;
    }

    private static boolean isRemoteScanFailure(Exception error) {
        if (!(error instanceof SyncException)) return true;
        FailureKind kind = ((SyncException) error).failureKind;
        return kind == FailureKind.NETWORK || kind == FailureKind.AUTH || kind == FailureKind.RATE_LIMITED
                || kind == FailureKind.SERVER;
    }

    private RemoteFile reloadEventFile(
            String owner, String repo, String branch, String token,
            String fileName, String deviceId, String month)
            throws IOException {
        String path = contentPrefix + "/events/" + fileName;
        JSONObject fileObject = getContentFile(owner, repo, path, branch, token, true);
        if (fileObject == null) {
            return new RemoteFile(fileName, deviceId, month, null, new JSONArray());
        }
        return parseEventFile(fileName, month, deviceId, fileObject);
    }

    private RemoteFile parseEventFile(
            String name, String month, String expectedDeviceId, JSONObject fileObject) throws IOException {
        byte[] decoded = decodeContents(fileObject);
        JSONObject batch;
        try {
            batch = new JSONObject(new String(decoded, StandardCharsets.UTF_8));
        } catch (JSONException e) {
            throw new SyncException("GitHub event batch is invalid", 0);
        }
        if (integerValue(batch, "schemaVersion") != 1) {
            throw new SyncException("GitHub event batch has an unsupported schema", 0);
        }
        String deviceId = stringValue(batch, "deviceId");
        if (!expectedDeviceId.equals(deviceId)) {
            throw new SyncException("GitHub event batch device does not match its filename", 0);
        }
        JSONArray events = arrayValue(batch, "events");
        JSONArray validated;
        try {
            validated = StudyEngine.mergeEvents(events, new JSONArray());
        } catch (IllegalArgumentException e) {
            throw new SyncException("GitHub event batch failed validation", 0);
        }
        for (int i = 0; i < validated.length(); i++) {
            JSONObject event = jsonObjectAt(validated, i, "event batch");
            if (!expectedDeviceId.equals(event.optString("deviceId"))
                    || !monthOf(event.optLong("at", -1L)).equals(month)) {
                throw new SyncException("GitHub event does not match its batch file", 0);
            }
        }
        String sha = stringValue(fileObject, "sha");
        return new RemoteFile(name, expectedDeviceId, month, sha, validated);
    }

    private int uploadFile(
            String owner,
            String repo,
            String branch,
            String token,
            String fileName,
            String deviceId,
            String month,
            JSONArray events,
            String sha) throws SyncException {
        JSONObject batch = eventBatch(deviceId, events);
        byte[] raw = batch.toString().getBytes(StandardCharsets.UTF_8);
        if (raw.length > MAX_EVENT_BATCH_BYTES) {
            throw new SyncException("event batch exceeded the safe size limit", 0, FailureKind.TOO_LARGE, 0L);
        }
        String encoded = Base64.getEncoder().encodeToString(raw);
        JSONObject body = new JSONObject();
        put(body, "message", "Sync WordWatch events " + month);
        put(body, "content", encoded);
        put(body, "branch", branch);
        if (sha != null && !sha.isEmpty()) {
            put(body, "sha", sha);
        }
        byte[] requestBody = body.toString().getBytes(StandardCharsets.UTF_8);
        try {
            Response response = request(
                    "PUT",
                    contentsUrl(owner, repo, contentPrefix + "/events/" + fileName, null),
                    token,
                    requestBody,
                    new HashSet<>(Arrays.asList(200, 201)),
                    false);
            return response.statusCode;
        } catch (SyncException e) {
            throw e;
        } catch (IOException e) {
            throw new SyncException("GitHub network request failed", 0, FailureKind.NETWORK, 0L);
        }
    }

    private UploadOutcome uploadPlan(
            String owner,
            String repo,
            String branch,
            String token,
            UploadPlan plan,
            RemoteState remote,
            JSONArray localForMonth) {
        RemoteFile candidate = plan.existing == null
                ? new RemoteFile(plan.fileName, plan.deviceId, plan.month, null, new JSONArray())
                : plan.existing;
        int originallyMissing = countMissing(plan.events, candidate.events);

        for (int attempt = 0; ; attempt++) {
            JSONArray combined;
            try {
                combined = StudyEngine.mergeEvents(candidate.events, plan.events);
            } catch (IllegalArgumentException e) {
                return UploadOutcome.failed(new SyncException(
                        "event IDs conflict in an upload batch", 0, FailureKind.PROTOCOL, 0L));
            }
            int newlyAdded = countMissing(plan.events, candidate.events);
            if (newlyAdded == 0) {
                recordRemoteFile(remote, candidate);
                Set<String> confirmed = new LinkedHashSet<>();
                addConfirmedLocalIds(confirmed, localForMonth, candidate.events);
                return UploadOutcome.completed(confirmed, 0);
            }

            try {
                uploadFile(owner, repo, branch, token, plan.fileName, plan.deviceId,
                        plan.month, combined, candidate.sha);
                candidate.events = combined;
                candidate.sha = null; // The following sync re-reads the authoritative SHA.
                recordRemoteFile(remote, candidate);
                Set<String> confirmed = new LinkedHashSet<>();
                addConfirmedLocalIds(confirmed, localForMonth, candidate.events);
                return UploadOutcome.completed(confirmed, newlyAdded);
            } catch (SyncException failure) {
                if (!isRetryable(failure) || attempt >= MAX_UPLOAD_RETRIES) {
                    return UploadOutcome.failed(failure);
                }
                long delay = retryDelayMillis(failure, attempt);
                if (delay > MAX_INLINE_RETRY_DELAY_MS) {
                    return UploadOutcome.failed(failure.withRetryAfter(delay));
                }
                try {
                    waitBeforeRetry(delay);
                    RemoteFile latest = reloadEventFile(owner, repo, branch, token,
                            plan.fileName, plan.deviceId, plan.month);
                    // The event repository is append-only; retain known contents if the target
                    // disappeared between read and write so recreation cannot drop old records.
                    if (latest.sha == null && candidate.events.length() > 0) {
                        latest.events = StudyEngine.mergeEvents(candidate.events, latest.events);
                    }
                    candidate = latest;
                } catch (SyncException retryFailure) {
                    return UploadOutcome.failed(retryFailure);
                } catch (IOException retryFailure) {
                    return UploadOutcome.failed(new SyncException(
                            "GitHub network request failed", 0, FailureKind.NETWORK,
                            failure.retryAfterMillis));
                }

                // A PUT may have committed before a connection failed. Confirm by reading the
                // deterministic target file before retrying, so a retry cannot duplicate a batch.
                if (countMissing(plan.events, candidate.events) == 0) {
                    recordRemoteFile(remote, candidate);
                    Set<String> confirmed = new LinkedHashSet<>();
                    addConfirmedLocalIds(confirmed, localForMonth, candidate.events);
                    return UploadOutcome.completed(confirmed, originallyMissing);
                }
                recordRemoteFile(remote, candidate);
            }
        }
    }

    private static void recordRemoteFile(RemoteState remote, RemoteFile file) {
        remote.filesByName.put(file.name, file);
        String key = eventGroupKey(file.deviceId, file.month);
        remote.eventsByGroup.put(key, StudyEngine.mergeEvents(
                remote.eventsByGroup.getOrDefault(key, new JSONArray()), file.events));
        remote.events = StudyEngine.mergeEvents(remote.events, file.events);
    }

    private JSONObject getContentFile(
            String owner, String repo, String path, String branch, String token, boolean allowNotFound)
            throws IOException {
        Response response = request(
                "GET", contentsUrl(owner, repo, path, branch), token, null,
                allowNotFound ? new HashSet<>(Arrays.asList(200, 404)) : Collections.singleton(200),
                allowNotFound);
        if (response.statusCode == 404) {
            return null;
        }
        try {
            return new JSONObject(response.bodyText());
        } catch (JSONException e) {
            throw new SyncException("GitHub file response is invalid", 0);
        }
    }

    /**
     * Downloads a package as raw bytes. GitHub's Contents API omits the base64 content field
     * for files larger than 1 MiB unless the raw media type is requested.
     */
    private byte[] getRawContent(
            String owner, String repo, String path, String branch, String token, boolean allowNotFound)
            throws IOException {
        Response response = request(
                "GET", contentsUrl(owner, repo, path, branch), token, null,
                allowNotFound ? new HashSet<>(Arrays.asList(200, 404)) : Collections.singleton(200),
                allowNotFound, "application/vnd.github.raw+json");
        if (response.statusCode == 404) return null;
        if (response.body.length == 0) {
            throw new SyncException("GitHub file content is empty", 0, FailureKind.PROTOCOL, 0L);
        }
        if (response.body.length > MAX_DECODED_CONTENT_BYTES) {
            throw new SyncException("GitHub file content exceeded the size limit", 0, FailureKind.TOO_LARGE, 0L);
        }
        return response.body;
    }

    private Response request(
            String method,
            String url,
            String token,
            byte[] body,
            Set<Integer> expectedStatuses,
            boolean allowMissingDirectory) throws IOException {
        return request(method, url, token, body, expectedStatuses, allowMissingDirectory,
                "application/vnd.github+json");
    }

    private Response request(
            String method,
            String url,
            String token,
            byte[] body,
            Set<Integer> expectedStatuses,
            boolean allowMissingDirectory,
            String accept) throws IOException {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Accept", accept);
        headers.put("X-GitHub-Api-Version", "2022-11-28");
        headers.put("User-Agent", "WordWatch");
        headers.put("Authorization", "Bearer " + token);
        if (body != null) {
            headers.put("Content-Type", "application/json; charset=utf-8");
        }

        int maxAttempts = "GET".equals(method) ? MAX_READ_RETRIES + 1 : 1;
        for (int attempt = 0; ; attempt++) {
            ensureRequestAllowed();
            try {
                Request request = new Request(method, url, headers, body);
                Response response = transport.execute(request);
                if (response == null) throw new SyncException("GitHub response is missing", 0);
                if (response.body.length > MAX_RESPONSE_BYTES) {
                    throw new SyncException("GitHub response exceeded the size limit", 0, FailureKind.TOO_LARGE, 0L);
                }
                if (response.statusCode >= 300 && response.statusCode < 400) {
                    throw new SyncException("GitHub returned an unexpected redirect", response.statusCode,
                            FailureKind.HTTP, 0L);
                }
                if (allowMissingDirectory && response.statusCode == 404) {
                    return response;
                }
                if (!expectedStatuses.contains(response.statusCode)) {
                    throw failureForResponse(response);
                }
                return response;
            } catch (SyncException e) {
                if (!"GET".equals(method) || attempt + 1 >= maxAttempts || !isRetryable(e)) {
                    throw e;
                }
                long delay = retryDelayMillis(e, attempt);
                if (delay > MAX_INLINE_RETRY_DELAY_MS) {
                    throw e.withRetryAfter(delay);
                }
                waitBeforeRetry(delay);
            } catch (ResponseTooLargeException e) {
                throw new SyncException("GitHub response exceeded the size limit", 0,
                        FailureKind.TOO_LARGE, 0L);
            } catch (IOException | RuntimeException e) {
                SyncException failure = new SyncException(
                        "GitHub network request failed", 0, FailureKind.NETWORK, 0L);
                if (!"GET".equals(method) || attempt + 1 >= maxAttempts) {
                    throw failure;
                }
                long delay = retryDelayMillis(failure, attempt);
                waitBeforeRetry(delay);
            }
        }
    }

    private static byte[] decodeContents(JSONObject fileObject) throws IOException {
        String encoded = stringValue(fileObject, "content").replaceAll("\\s", "");
        if (encoded.isEmpty()) {
            throw new SyncException("GitHub file content is empty", 0);
        }
        try {
            byte[] decoded = Base64.getDecoder().decode(encoded);
            if (decoded.length > MAX_DECODED_CONTENT_BYTES) {
                throw new SyncException("GitHub file content exceeded the size limit", 0);
            }
            return decoded;
        } catch (IllegalArgumentException e) {
            throw new SyncException("GitHub file content is invalid", 0);
        }
    }

    private String contentsUrl(String owner, String repo, String path, String branch) {
        StringBuilder url = new StringBuilder(API_ROOT)
                .append("/repos/").append(encodePathSegment(owner))
                .append('/').append(encodePathSegment(repo))
                .append("/contents/");
        String[] segments = path.split("/");
        for (int i = 0; i < segments.length; i++) {
            if (i > 0) url.append('/');
            url.append(encodePathSegment(segments[i]));
        }
        if (branch != null) {
            url.append("?ref=").append(encodeQuery(branch));
        }
        return url.toString();
    }

    private static String encodePathSegment(String value) {
        return encodeQuery(value).replace("+", "%20");
    }

    private static String encodeQuery(String value) {
        try {
            return URLEncoder.encode(value, StandardCharsets.UTF_8.name());
        } catch (java.io.UnsupportedEncodingException e) {
            throw new IllegalStateException("UTF-8 is unavailable");
        }
    }

    private static Map<String, List<JSONObject>> groupOwnDeviceEvents(JSONArray events, String deviceId) {
        Map<String, List<JSONObject>> byMonth = new TreeMap<>();
        for (int i = 0; i < events.length(); i++) {
            JSONObject event = jsonObjectAt(events, i, "local events");
            if (!deviceId.equals(event.optString("deviceId"))) {
                continue;
            }
            String month = monthOf(event.optLong("at", -1L));
            byMonth.computeIfAbsent(month, ignored -> new ArrayList<>()).add(event);
        }
        return byMonth;
    }

    private static String monthOf(long at) {
        if (at < 0L) {
            throw new IllegalArgumentException("event timestamp is invalid");
        }
        LocalDate date = Instant.ofEpochMilli(at).atZone(StudyEngine.STUDY_ZONE).toLocalDate();
        return String.format(java.util.Locale.ROOT, "%04d-%02d", date.getYear(), date.getMonthValue());
    }

    private static JSONArray toArray(List<JSONObject> items) {
        JSONArray result = new JSONArray();
        for (JSONObject item : items) {
            result.put(item);
        }
        return result;
    }

    private static int countMissing(JSONArray wanted, JSONArray existing) {
        Set<String> present = new HashSet<>();
        for (int i = 0; i < existing.length(); i++) {
            JSONObject event = jsonObjectAt(existing, i, "events");
            present.add(event.optString("id"));
        }
        int count = 0;
        for (int i = 0; i < wanted.length(); i++) {
            JSONObject event = jsonObjectAt(wanted, i, "events");
            if (!present.contains(event.optString("id"))) {
                count++;
            }
        }
        return count;
    }

    private static JSONArray selectMissing(JSONArray wanted, JSONArray existing) {
        Set<String> present = new HashSet<>();
        for (int i = 0; i < existing.length(); i++) {
            present.add(jsonObjectAt(existing, i, "events").optString("id"));
        }
        JSONArray result = new JSONArray();
        for (int i = 0; i < wanted.length(); i++) {
            JSONObject event = jsonObjectAt(wanted, i, "events");
            if (!present.contains(event.optString("id"))) result.put(event);
        }
        return result;
    }

    private static void addConfirmedLocalIds(Set<String> out, JSONArray local, JSONArray confirmed) {
        Set<String> confirmedIds = new HashSet<>();
        for (int i = 0; i < confirmed.length(); i++) {
            confirmedIds.add(jsonObjectAt(confirmed, i, "events").optString("id"));
        }
        for (int i = 0; i < local.length(); i++) {
            String id = jsonObjectAt(local, i, "events").optString("id");
            if (confirmedIds.contains(id)) out.add(id);
        }
    }

    private static List<JSONArray> splitBatches(String deviceId, JSONArray events) throws SyncException {
        List<JSONArray> batches = new ArrayList<>();
        JSONArray current = new JSONArray();
        for (int i = 0; i < events.length(); i++) {
            JSONObject event = jsonObjectAt(events, i, "events");
            JSONArray candidate = copyArray(current);
            candidate.put(event);
            if (encodedBatchSize(deviceId, candidate) > MAX_EVENT_BATCH_BYTES) {
                if (current.length() == 0) {
                    throw new SyncException("single event exceeds the safe batch size", 0,
                            FailureKind.TOO_LARGE, 0L);
                }
                batches.add(current);
                current = new JSONArray().put(event);
                if (encodedBatchSize(deviceId, current) > MAX_EVENT_BATCH_BYTES) {
                    throw new SyncException("single event exceeds the safe batch size", 0,
                            FailureKind.TOO_LARGE, 0L);
                }
            } else {
                current = candidate;
            }
        }
        if (current.length() > 0) batches.add(current);
        return batches;
    }

    private static JSONArray copyArray(JSONArray source) {
        JSONArray copy = new JSONArray();
        for (int i = 0; i < source.length(); i++) copy.put(source.opt(i));
        return copy;
    }

    private static int encodedBatchSize(String deviceId, JSONArray events) {
        return eventBatch(deviceId, events).toString().getBytes(StandardCharsets.UTF_8).length;
    }

    private static JSONObject eventBatch(String deviceId, JSONArray events) {
        JSONObject batch = new JSONObject();
        put(batch, "schemaVersion", 1);
        put(batch, "deviceId", deviceId);
        put(batch, "events", events);
        return batch;
    }

    private static String batchId(JSONArray events) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (int i = 0; i < events.length(); i++) {
                String id = jsonObjectAt(events, i, "events").optString("id");
                digest.update(id.getBytes(StandardCharsets.UTF_8));
                digest.update((byte) '\n');
            }
            byte[] hash = digest.digest();
            StringBuilder hex = new StringBuilder();
            for (int i = 0; i < 10; i++) hex.append(String.format(java.util.Locale.ROOT, "%02x", hash[i]));
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable");
        }
    }

    private static String eventGroupKey(String deviceId, String month) {
        return deviceId + "\u0000" + month;
    }

    private static String joinWarnings(Set<String> warnings) {
        StringBuilder result = new StringBuilder();
        for (String warning : warnings) {
            if (warning == null || warning.isEmpty()) continue;
            if (result.length() > 0) result.append("；");
            result.append(warning);
        }
        return result.toString();
    }

    private static String packageFailureWarning(SyncException failure) {
        switch (failure.failureKind) {
            case NETWORK: return "离线或网络暂不可用";
            case AUTH: return "GitHub 授权或访问权限不足";
            case RATE_LIMITED: return "GitHub 暂时限流";
            case NOT_FOUND: return "找不到当前词包";
            case CANCELLED: return "自动同步网络条件已变化";
            default: return "格式不兼容或内容无法验证";
        }
    }

    private static String eventFailureWarning(SyncException failure) {
        switch (failure.failureKind) {
            case NETWORK: return "网络暂不可用，未确认的记录仍保留在本机";
            case AUTH: return "GitHub 授权或仓库写入权限不足，未确认的记录仍保留在本机";
            case RATE_LIMITED: return "GitHub 暂时限流，未确认的记录仍保留在本机";
            case CANCELLED: return "自动同步网络条件已变化，未确认的记录仍保留在本机";
            case TOO_LARGE: return "记录批次超过安全大小，未确认的记录仍保留在本机";
            default: return "记录上传未完成，未确认的记录仍保留在本机";
        }
    }

    private static boolean isRetryable(SyncException failure) {
        return failure.failureKind == FailureKind.NETWORK
                || failure.failureKind == FailureKind.RATE_LIMITED
                || failure.failureKind == FailureKind.SERVER
                || failure.failureKind == FailureKind.CONFLICT;
    }

    private static long retryDelayMillis(SyncException failure, int attempt) {
        if (failure.failureKind == FailureKind.RATE_LIMITED) return failure.retryAfterMillis;
        if (failure.retryAfterMillis > 0L) return failure.retryAfterMillis;
        return Math.min(4_000L, 250L << Math.min(attempt, 4));
    }

    private void ensureRequestAllowed() throws SyncException {
        if (!requestGuard.canProceed()) {
            throw new SyncException("automatic sync network constraint changed", 0,
                    FailureKind.CANCELLED, 0L);
        }
    }

    private void waitBeforeRetry(long delayMillis) throws SyncException {
        ensureRequestAllowed();
        if (delayMillis <= 0L) return;
        try {
            sleeper.sleep(delayMillis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new SyncException("GitHub retry interrupted", 0, FailureKind.CANCELLED, delayMillis);
        }
        ensureRequestAllowed();
    }

    private static SyncException failureForResponse(Response response) {
        int status = response.statusCode;
        if (status == 401 || (status == 403 && !isRateLimitResponse(response))) {
            return new SyncException("GitHub authentication or permission failed", status,
                    FailureKind.AUTH, 0L);
        }
        if (status == 403 || status == 429) {
            return new SyncException("GitHub rate limited the request", status,
                    FailureKind.RATE_LIMITED, retryAfterMillis(response));
        }
        if (status == 404) {
            return new SyncException("GitHub resource was not found", status, FailureKind.NOT_FOUND, 0L);
        }
        if (isConflict(status)) {
            return new SyncException("GitHub content changed during update", status, FailureKind.CONFLICT, 0L);
        }
        if (status >= 500 && status <= 599) {
            return new SyncException("GitHub server is temporarily unavailable", status,
                    FailureKind.SERVER, 0L);
        }
        return new SyncException("GitHub request failed", status, FailureKind.HTTP, 0L);
    }

    private static boolean isRateLimitResponse(Response response) {
        String remaining = response.header("x-ratelimit-remaining");
        String retryAfter = response.header("retry-after");
        String body = response.bodyText().toLowerCase(java.util.Locale.ROOT);
        return response.statusCode == 429 || "0".equals(remaining)
                || (retryAfter != null && !retryAfter.isEmpty())
                || body.contains("rate limit") || body.contains("secondary rate");
    }

    private static long retryAfterMillis(Response response) {
        String retryAfter = response.header("retry-after");
        if (retryAfter != null) {
            try {
                return Math.max(0L, Long.parseLong(retryAfter.trim()) * 1000L);
            } catch (NumberFormatException ignored) {
                try {
                    long target = java.time.ZonedDateTime.parse(retryAfter.trim(),
                            java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME)
                            .toInstant().toEpochMilli();
                    return Math.max(0L, target - System.currentTimeMillis());
                } catch (java.time.format.DateTimeParseException ignoredDate) {
                    // Fall through to the reset header or safe rate-limit delay.
                }
            }
        }
        if ("0".equals(response.header("x-ratelimit-remaining"))) {
            String reset = response.header("x-ratelimit-reset");
            if (reset != null) {
                try {
                    return Math.max(0L, Long.parseLong(reset.trim()) * 1000L - System.currentTimeMillis());
                } catch (NumberFormatException ignored) {
                    // Use the conservative fallback below.
                }
            }
        }
        return 60_000L;
    }

    private static JSONObject jsonObjectAt(JSONArray array, int index, String field) {
        Object value = array.opt(index);
        if (!(value instanceof JSONObject object)) {
            throw new IllegalArgumentException(field + " items must be objects");
        }
        return object;
    }

    private static String stringValue(JSONObject object, String field) throws IOException {
        Object value = object.opt(field);
        if (!(value instanceof String text) || text.isEmpty()) {
            throw new SyncException("GitHub response is missing a required field", 0);
        }
        return text;
    }

    private static JSONArray arrayValue(JSONObject object, String field) throws IOException {
        Object value = object.opt(field);
        if (!(value instanceof JSONArray array)) {
            throw new SyncException("GitHub response is missing a required array", 0);
        }
        return array;
    }

    private static int integerValue(JSONObject object, String field) throws IOException {
        Object value = object.opt(field);
        if (!(value instanceof Number number) || number.intValue() != 1
                || number.doubleValue() != 1.0d) {
            throw new SyncException("GitHub response has an unsupported schema", 0);
        }
        return number.intValue();
    }

    private static void put(JSONObject object, String key, Object value) {
        try {
            object.put(key, value);
        } catch (JSONException e) {
            throw new IllegalArgumentException("could not build GitHub request");
        }
    }

    private static void validateRepoPart(String value, String field) {
        if (value == null || !value.matches("[A-Za-z0-9_.-]{1,100}")) {
            throw new IllegalArgumentException("invalid " + field);
        }
    }

    private static void validateDeviceId(String deviceId) {
        if (deviceId == null || !deviceId.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,127}")) {
            throw new IllegalArgumentException("invalid deviceId");
        }
    }

    private static String validateContentPrefix(String contentPrefix) {
        if (contentPrefix == null || contentPrefix.isEmpty() || contentPrefix.length() > 255
                || contentPrefix.startsWith("/") || contentPrefix.endsWith("/")
                || contentPrefix.indexOf('\\') >= 0) {
            throw new IllegalArgumentException("invalid content prefix");
        }
        String[] segments = contentPrefix.split("/", -1);
        if (segments.length > 8) {
            throw new IllegalArgumentException("invalid content prefix");
        }
        for (String segment : segments) {
            if (segment.isEmpty() || segment.contains("..")
                    || !segment.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,63}")) {
                throw new IllegalArgumentException("invalid content prefix");
            }
        }
        return contentPrefix;
    }

    private static boolean containsControl(String value) {
        for (int i = 0; i < value.length(); i++) {
            if (Character.isISOControl(value.charAt(i))) {
                return true;
            }
        }
        return false;
    }

    private static boolean isConflict(int status) {
        return status == 409 || status == 422;
    }

    /** Request passed to an injectable transport. Never log its Authorization header. */
    public static final class Request {
        public final String method;
        public final String url;
        public final Map<String, String> headers;
        public final byte[] body;

        public Request(String method, String url, Map<String, String> headers, byte[] body) {
            this.method = method;
            this.url = url;
            this.headers = Collections.unmodifiableMap(new LinkedHashMap<>(headers));
            this.body = body == null ? null : body.clone();
        }
    }

    /** A bounded HTTP response. */
    public static final class Response {
        public final int statusCode;
        public final byte[] body;
        public final Map<String, String> headers;

        public Response(int statusCode, byte[] body) {
            this(statusCode, body, Collections.emptyMap());
        }

        public Response(int statusCode, byte[] body, Map<String, String> headers) {
            this.statusCode = statusCode;
            this.body = body == null ? new byte[0] : body.clone();
            Map<String, String> normalized = new HashMap<>();
            if (headers != null) {
                for (Map.Entry<String, String> entry : headers.entrySet()) {
                    if (entry.getKey() != null && entry.getValue() != null) {
                        normalized.put(entry.getKey().toLowerCase(Locale.ROOT), entry.getValue());
                    }
                }
            }
            this.headers = Collections.unmodifiableMap(normalized);
        }

        public String header(String name) {
            return name == null ? null : headers.get(name.toLowerCase(Locale.ROOT));
        }

        public String bodyText() {
            return new String(body, StandardCharsets.UTF_8);
        }
    }

    public interface Transport {
        Response execute(Request request) throws IOException;
    }

    public interface ConnectionFactory {
        HttpURLConnection open(URL url) throws IOException;
    }

    /** Default fixed-host HTTPS transport with redirects disabled and bounded reads. */
    public static final class HttpTransport implements Transport {
        private final ConnectionFactory connectionFactory;

        public HttpTransport() {
            this(url -> (HttpURLConnection) url.openConnection());
        }

        public HttpTransport(ConnectionFactory connectionFactory) {
            if (connectionFactory == null) throw new IllegalArgumentException("connection factory is required");
            this.connectionFactory = connectionFactory;
        }

        @Override
        public Response execute(Request request) throws IOException {
            URL target = new URL(request.url);
            if (!"https".equalsIgnoreCase(target.getProtocol())
                    || !"api.github.com".equalsIgnoreCase(target.getHost())
                    || (target.getPort() != -1 && target.getPort() != 443)) {
                throw new IOException("unsupported API host");
            }

            HttpURLConnection connection = connectionFactory.open(target);
            try {
                connection.setInstanceFollowRedirects(false);
                connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
                connection.setReadTimeout(READ_TIMEOUT_MS);
                connection.setRequestMethod(request.method);
                for (Map.Entry<String, String> header : request.headers.entrySet()) {
                    connection.setRequestProperty(header.getKey(), header.getValue());
                }
                if (request.body != null) {
                    connection.setDoOutput(true);
                    connection.setFixedLengthStreamingMode(request.body.length);
                    try (OutputStream output = connection.getOutputStream()) {
                        output.write(request.body);
                    }
                }
                int status = connection.getResponseCode();
                InputStream stream = status >= 400 ? connection.getErrorStream() : connection.getInputStream();
                byte[] body = stream == null ? new byte[0] : readBounded(stream, MAX_RESPONSE_BYTES);
                Map<String, String> headers = new HashMap<>();
                for (Map.Entry<String, List<String>> header : connection.getHeaderFields().entrySet()) {
                    if (header.getKey() == null || header.getValue() == null || header.getValue().isEmpty()) continue;
                    headers.put(header.getKey(), header.getValue().get(0));
                }
                return new Response(status, body, headers);
            } finally {
                connection.disconnect();
            }
        }

        private static byte[] readBounded(InputStream stream, int maxBytes) throws IOException {
            try (InputStream input = stream; ByteArrayOutputStream output = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[8192];
                int total = 0;
                int count;
                while ((count = input.read(buffer)) != -1) {
                    total += count;
                    if (total > maxBytes) {
                        throw new ResponseTooLargeException();
                    }
                    output.write(buffer, 0, count);
                }
                return output.toByteArray();
            }
        }

    }

    private static final class ResponseTooLargeException extends IOException {
        private ResponseTooLargeException() { super("response exceeded the size limit"); }
    }

    public static final class SyncResult {
        public final JSONObject pack;
        public final List<JSONObject> packages;
        public final JSONArray events;
        public final Set<String> acknowledgedEventIds;
        public final int uploadedCount;
        public final boolean eventsComplete;
        public final SyncException eventFailure;
        public final SyncException packFailure;
        /** Package-specific warning to pass to AppStore.acceptSync(..., warning). */
        public final String warning;
        /** Event-only warnings, including quarantined foreign files. */
        public final String eventWarning;

        public SyncResult(JSONObject pack, List<JSONObject> packages, JSONArray events, Set<String> acknowledgedEventIds,
                int uploadedCount, boolean eventsComplete, SyncException eventFailure,
                SyncException packFailure, String warning, String eventWarning) {
            this.pack = pack;
            this.packages = Collections.unmodifiableList(new ArrayList<>(packages));
            this.events = events;
            this.acknowledgedEventIds = Collections.unmodifiableSet(new LinkedHashSet<>(acknowledgedEventIds));
            this.uploadedCount = uploadedCount;
            this.eventsComplete = eventsComplete;
            this.eventFailure = eventFailure;
            this.packFailure = packFailure;
            this.warning = warning == null ? "" : warning;
            this.eventWarning = eventWarning == null ? "" : eventWarning;
        }
    }

    public enum FailureKind {
        NETWORK, AUTH, RATE_LIMITED, CONFLICT, NOT_FOUND, SERVER, HTTP, PROTOCOL, TOO_LARGE, CANCELLED
    }

    public static final class SyncException extends IOException {
        public final int statusCode;
        public final FailureKind failureKind;
        public final long retryAfterMillis;

        public SyncException(String message, int statusCode) {
            this(message, statusCode, statusCode == 0 ? FailureKind.PROTOCOL
                    : statusCode == 404 ? FailureKind.NOT_FOUND : FailureKind.HTTP, 0L);
        }

        public SyncException(String message, int statusCode, FailureKind failureKind, long retryAfterMillis) {
            super(message);
            this.statusCode = statusCode;
            this.failureKind = failureKind == null ? FailureKind.HTTP : failureKind;
            this.retryAfterMillis = Math.max(0L, retryAfterMillis);
        }

        public SyncException withRetryAfter(long delayMillis) {
            return new SyncException(getMessage(), statusCode, failureKind, delayMillis);
        }
    }

    @FunctionalInterface
    public interface Sleeper {
        void sleep(long millis) throws InterruptedException;
    }

    @FunctionalInterface
    public interface RequestGuard {
        boolean canProceed();
    }

    private static final class RemoteState {
        private final Map<String, RemoteFile> filesByName = new LinkedHashMap<>();
        private final Map<String, JSONArray> eventsByGroup = new LinkedHashMap<>();
        private final Set<String> badGroups = new HashSet<>();
        private final Map<String, SyncException> badGroupFailures = new HashMap<>();
        private final Set<String> warnings = new LinkedHashSet<>();
        private JSONArray events = new JSONArray();
        private SyncException scanFailure;
    }

    private static final class RemoteFile {
        private final String name;
        private final String deviceId;
        private final String month;
        private String sha;
        private JSONArray events;

        private RemoteFile(String name, String deviceId, String month, String sha, JSONArray events) {
            this.name = name;
            this.deviceId = deviceId;
            this.month = month;
            this.sha = sha;
            this.events = events;
        }
    }

    private static final class SyncAccumulator {
        private final Set<String> acknowledgedEventIds = new LinkedHashSet<>();
        private final Set<String> eventWarnings = new LinkedHashSet<>();
        private boolean eventComplete = true;
        private int uploadedCount;
        private SyncException eventFailure;
        private SyncException packFailure;
        private String packageWarning = "";

        private void recordFailure(SyncException failure) {
            if (eventFailure == null) eventFailure = failure;
        }
    }

    private static final class PackageDownload {
        private final List<JSONObject> packages = new ArrayList<>();
        private final Set<String> warnings = new LinkedHashSet<>();
        private JSONObject current;
        private SyncException failure;

        private void recordFailure(SyncException next) {
            if (failure == null) failure = next;
        }
    }

    private static final class PackageDocument {
        private final JSONObject pack;
        private final byte[] rawBytes;

        private PackageDocument(JSONObject pack, byte[] rawBytes) {
            this.pack = pack;
            this.rawBytes = rawBytes;
        }
    }

    private static final class IndexEntry {
        private final String path;
        private final String date;
        private final String sha256;

        private IndexEntry(String path, String date, String sha256) {
            this.path = path;
            this.date = date;
            this.sha256 = sha256;
        }
    }

    private static final class UploadPlan {
        private final String fileName;
        private final String month;
        private final String deviceId;
        private final JSONArray events;
        private final RemoteFile existing;

        private UploadPlan(String fileName, String month, String deviceId, JSONArray events, RemoteFile existing) {
            this.fileName = fileName;
            this.month = month;
            this.deviceId = deviceId;
            this.events = events;
            this.existing = existing;
        }
    }

    private static final class UploadOutcome {
        private final boolean complete;
        private final SyncException failure;
        private final Set<String> acknowledgedEventIds;
        private final int uploadedCount;

        private UploadOutcome(boolean complete, SyncException failure, Set<String> acknowledgedEventIds,
                int uploadedCount) {
            this.complete = complete;
            this.failure = failure;
            this.acknowledgedEventIds = acknowledgedEventIds;
            this.uploadedCount = uploadedCount;
        }

        private static UploadOutcome completed(Set<String> acknowledged, int uploadedCount) {
            return new UploadOutcome(true, null, acknowledged, uploadedCount);
        }

        private static UploadOutcome failed(SyncException failure) {
            return new UploadOutcome(false, failure, Collections.emptySet(), 0);
        }
    }
}
