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
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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

    private static final String API_ROOT = "https://api.github.com";
    private static final int MAX_RESPONSE_BYTES = 2_000_000;
    private static final int CONNECT_TIMEOUT_MS = 8_000;
    private static final int READ_TIMEOUT_MS = 15_000;
    private static final int MAX_CONFLICT_RETRIES = 2;
    private static final Pattern EVENT_FILE =
            Pattern.compile("^([A-Za-z0-9][A-Za-z0-9._-]{0,127})-(\\d{4}-(?:0[1-9]|1[0-2]))\\.json$");

    private final Transport transport;
    private final String contentPrefix;

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
        if (transport == null) {
            throw new IllegalArgumentException("transport is required");
        }
        this.contentPrefix = validateContentPrefix(contentPrefix);
        this.transport = transport;
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
        JSONObject pack = downloadCurrent(owner, repo, branch, token);
        RemoteState remote = downloadRemoteEvents(owner, repo, branch, token);

        Map<String, List<JSONObject>> ownEventsByMonth = groupOwnDeviceEvents(validatedLocal, deviceId);
        int uploadedCount = 0;
        for (Map.Entry<String, List<JSONObject>> entry : ownEventsByMonth.entrySet()) {
            String month = entry.getKey();
            String fileName = deviceId + "-" + month + ".json";
            JSONArray localForMonth = toArray(entry.getValue());
            RemoteFile remoteFile = remote.filesByName.get(fileName);
            if (remoteFile == null) {
                remoteFile = new RemoteFile(fileName, null, new JSONArray());
            }

            int missing = countMissing(localForMonth, remoteFile.events);
            if (missing == 0) {
                continue;
            }

            RemoteFile candidate = remoteFile;
            for (int attempt = 0; ; attempt++) {
                JSONArray combined = StudyEngine.mergeEvents(candidate.events, localForMonth);
                int newlyAdded = countMissing(localForMonth, candidate.events);
                if (newlyAdded == 0) {
                    candidate.events = combined;
                    remote.filesByName.put(fileName, candidate);
                    break;
                }

                int status;
                try {
                    status = uploadFile(owner, repo, branch, token, fileName, month, combined, candidate.sha);
                } catch (SyncException e) {
                    if (!isConflict(e.statusCode)) {
                        throw e;
                    }
                    if (attempt >= MAX_CONFLICT_RETRIES) {
                        throw new SyncException("GitHub event update conflicted after retries", e.statusCode);
                    }
                    candidate = reloadEventFile(owner, repo, branch, token, deviceId, month);
                    remote.filesByName.put(fileName, candidate);
                    continue;
                }
                if (status != 200 && status != 201) {
                    throw new SyncException("GitHub event update failed", status);
                }
                candidate.events = combined;
                candidate.sha = null; // The next sync re-reads the authoritative SHA.
                remote.filesByName.put(fileName, candidate);
                uploadedCount += newlyAdded;
                break;
            }
        }

        JSONArray allRemote = new JSONArray();
        for (RemoteFile file : remote.filesByName.values()) {
            allRemote = StudyEngine.mergeEvents(allRemote, file.events);
        }
        JSONArray merged = StudyEngine.mergeEvents(allRemote, validatedLocal);
        return new SyncResult(pack, merged, uploadedCount);
    }

    private JSONObject downloadCurrent(String owner, String repo, String branch, String token) throws IOException {
        String path = contentPrefix + "/current.json";
        JSONObject file = getContentFile(owner, repo, path, branch, token, false);
        byte[] decoded = decodeContents(file);
        JSONObject pack;
        try {
            pack = new JSONObject(new String(decoded, StandardCharsets.UTF_8));
        } catch (JSONException e) {
            throw new SyncException("GitHub current package is invalid", 0);
        }
        try {
            StudyEngine.validatePackage(pack);
        } catch (IllegalArgumentException e) {
            throw new SyncException("GitHub current package failed validation", 0);
        }
        return pack;
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
            JSONObject entry = jsonObjectAt(entries, i, "GitHub event directory");
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
            String path = directoryPath + "/" + name;
            JSONObject fileObject = getContentFile(owner, repo, path, branch, token, false);
            RemoteFile remoteFile = parseEventFile(name, month, deviceId, fileObject);
            RemoteFile prior = state.filesByName.putIfAbsent(name, remoteFile);
            if (prior != null) {
                throw new SyncException("GitHub event directory contains duplicate files", 0);
            }
            state.events = StudyEngine.mergeEvents(state.events, remoteFile.events);
        }
        return state;
    }

    private RemoteFile reloadEventFile(
            String owner, String repo, String branch, String token, String deviceId, String month)
            throws IOException {
        String name = deviceId + "-" + month + ".json";
        String path = contentPrefix + "/events/" + name;
        JSONObject fileObject = getContentFile(owner, repo, path, branch, token, true);
        if (fileObject == null) {
            return new RemoteFile(name, null, new JSONArray());
        }
        return parseEventFile(name, month, deviceId, fileObject);
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
        return new RemoteFile(name, sha, validated);
    }

    private int uploadFile(
            String owner,
            String repo,
            String branch,
            String token,
            String fileName,
            String month,
            JSONArray events,
            String sha) throws IOException {
        JSONObject batch = new JSONObject();
        put(batch, "schemaVersion", 1);
        put(batch, "deviceId", fileName.substring(0, fileName.length() - month.length() - 6));
        put(batch, "events", events);

        byte[] raw = batch.toString().getBytes(StandardCharsets.UTF_8);
        String encoded = Base64.getEncoder().encodeToString(raw);
        JSONObject body = new JSONObject();
        put(body, "message", "Sync WordWatch events " + month);
        put(body, "content", encoded);
        put(body, "branch", branch);
        if (sha != null && !sha.isEmpty()) {
            put(body, "sha", sha);
        }
        byte[] requestBody = body.toString().getBytes(StandardCharsets.UTF_8);
        Response response = request(
                "PUT",
                contentsUrl(owner, repo, contentPrefix + "/events/" + fileName, null),
                token,
                requestBody,
                new HashSet<>(Arrays.asList(200, 201)),
                false);
        return response.statusCode;
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

    private Response request(
            String method,
            String url,
            String token,
            byte[] body,
            Set<Integer> expectedStatuses,
            boolean allowMissingDirectory) throws IOException {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Accept", "application/vnd.github+json");
        headers.put("X-GitHub-Api-Version", "2022-11-28");
        headers.put("User-Agent", "WordWatch");
        headers.put("Authorization", "Bearer " + token);
        if (body != null) {
            headers.put("Content-Type", "application/json; charset=utf-8");
        }

        Response response;
        try {
            Request request = new Request(method, url, headers, body);
            response = transport.execute(request);
        } catch (IOException | RuntimeException e) {
            throw new SyncException("GitHub request failed", 0);
        }
        if (response == null || response.body.length > MAX_RESPONSE_BYTES) {
            throw new SyncException("GitHub response exceeded the size limit", 0);
        }
        if (response.statusCode >= 300 && response.statusCode < 400) {
            throw new SyncException("GitHub returned an unexpected redirect", response.statusCode);
        }
        if (response.statusCode == 401 || response.statusCode == 403) {
            throw new SyncException("GitHub permission denied", response.statusCode);
        }
        if (allowMissingDirectory && response.statusCode == 404) {
            return response;
        }
        if (!expectedStatuses.contains(response.statusCode)) {
            throw new SyncException("GitHub request failed", response.statusCode);
        }
        return response;
    }

    private static byte[] decodeContents(JSONObject fileObject) throws IOException {
        String encoded = stringValue(fileObject, "content").replaceAll("\\s", "");
        if (encoded.isEmpty()) {
            throw new SyncException("GitHub file content is empty", 0);
        }
        try {
            byte[] decoded = Base64.getDecoder().decode(encoded);
            if (decoded.length > MAX_RESPONSE_BYTES) {
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

        public Response(int statusCode, byte[] body) {
            this.statusCode = statusCode;
            this.body = body == null ? new byte[0] : body.clone();
        }

        public String bodyText() {
            return new String(body, StandardCharsets.UTF_8);
        }
    }

    public interface Transport {
        Response execute(Request request) throws IOException;
    }

    /** Default fixed-host HTTPS transport with redirects disabled and bounded reads. */
    public static final class HttpTransport implements Transport {
        @Override
        public Response execute(Request request) throws IOException {
            URL target = new URL(request.url);
            if (!"https".equalsIgnoreCase(target.getProtocol())
                    || !"api.github.com".equalsIgnoreCase(target.getHost())
                    || (target.getPort() != -1 && target.getPort() != 443)) {
                throw new IOException("unsupported API host");
            }

            HttpURLConnection connection = (HttpURLConnection) target.openConnection();
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
                return new Response(status, body);
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
                        throw new IOException("response exceeded the size limit");
                    }
                    output.write(buffer, 0, count);
                }
                return output.toByteArray();
            }
        }
    }

    public static final class SyncResult {
        public final JSONObject pack;
        public final JSONArray events;
        public final int uploadedCount;

        public SyncResult(JSONObject pack, JSONArray events, int uploadedCount) {
            this.pack = pack;
            this.events = events;
            this.uploadedCount = uploadedCount;
        }
    }

    public static final class SyncException extends IOException {
        public final int statusCode;

        public SyncException(String message, int statusCode) {
            super(message);
            this.statusCode = statusCode;
        }
    }

    private static final class RemoteState {
        private final Map<String, RemoteFile> filesByName = new LinkedHashMap<>();
        private JSONArray events = new JSONArray();
    }

    private static final class RemoteFile {
        private final String name;
        private String sha;
        private JSONArray events;

        private RemoteFile(String name, String sha, JSONArray events) {
            this.name = name;
            this.sha = sha;
            this.events = events;
        }
    }
}
