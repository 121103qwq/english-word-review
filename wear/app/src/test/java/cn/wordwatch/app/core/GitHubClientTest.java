package cn.wordwatch.app.core;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.security.MessageDigest;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class GitHubClientTest {
    private static final String TOKEN = "local-test-token-do-not-log";

    @Test
    public void conflictRetryReloadsRemoteAndWritesMergedBatchWithLatestSha() throws Exception {
        JSONObject pack = TestFixtures.packageWith(
                java.util.Collections.singletonList("csv:0001"), java.util.Collections.emptyList());
        JSONObject local = TestFixtures.answer(
                "00000000-0000-0000-0000-000000000061", 1,
                TestFixtures.at("2026-01-01", 9, 0), "csv:0001",
                "correct", "forward", "primary", false);
        JSONObject concurrent = TestFixtures.answer(
                "00000000-0000-0000-0000-000000000062", 2,
                TestFixtures.at("2026-01-01", 8, 0), "csv:0002",
                "wrong", "reverse", "primary", true);

        ScriptedTransport transport = new ScriptedTransport(request -> {
            if (request.method.equals("GET") && request.url.contains("/current.json?ref=main")) {
                return packageResponse(pack);
            }
            if (request.method.equals("GET") && request.url.endsWith("/events?ref=main")) {
                return jsonResponse(200, new JSONArray());
            }
            if (request.method.equals("GET") && request.url.contains("/events/device-A-2026-01.json?ref=main")) {
                JSONArray remoteEvents = new JSONArray().put(concurrent);
                return jsonResponse(200,
                        TestFixtures.githubFile(TestFixtures.eventBatch("device-A", remoteEvents), "concurrent-sha"));
            }
            if (request.method.equals("PUT")) {
                return new GitHubClient.Response(201, "{}".getBytes(StandardCharsets.UTF_8));
            }
            throw new IOException("unexpected request");
        });
        transport.setFirstPutConflict();

        GitHubClient.SyncResult result = client(transport).sync(
                GitHubClient.DEFAULT_OWNER,
                GitHubClient.DEFAULT_REPO,
                GitHubClient.DEFAULT_BRANCH,
                TOKEN,
                "device-A",
                new JSONArray().put(local));

        assertEquals(1, result.uploadedCount);
        assertEquals(2, result.events.length());
        assertEquals(6, transport.requests.size());
        GitHubClient.Request firstPut = transport.requests.get(1);
        GitHubClient.Request retryPut = transport.requests.get(3);
        assertTrue(firstPut.url.contains("/contents/watch-words/events/"));
        assertTrue(firstPut.url.endsWith("/events/device-A-2026-01.json"));
        assertFalse(new JSONObject(new String(firstPut.body, StandardCharsets.UTF_8)).has("sha"));
        JSONObject retryBody = new JSONObject(new String(retryPut.body, StandardCharsets.UTF_8));
        assertEquals("concurrent-sha", retryBody.optString("sha"));
        JSONObject uploadedBatch = decodedBatch(retryBody.optString("content"));
        assertEquals("device-A", uploadedBatch.optString("deviceId"));
        assertEquals(2, uploadedBatch.getJSONArray("events").length());
        assertEquals(concurrent.optString("id"),
                uploadedBatch.getJSONArray("events").getJSONObject(0).optString("id"));
        assertEquals(local.optString("id"),
                uploadedBatch.getJSONArray("events").getJSONObject(1).optString("id"));
    }

    @Test
    public void alreadySyncedEventsDoNotCreateAnEmptyCommit() throws Exception {
        JSONObject pack = TestFixtures.packageWith(
                java.util.Collections.singletonList("csv:0001"), java.util.Collections.emptyList());
        JSONObject event = TestFixtures.answer(
                "00000000-0000-0000-0000-000000000071", 1,
                TestFixtures.at("2026-01-02", 9, 0), "csv:0001",
                "correct", "forward", "primary", false);
        JSONArray remoteEvents = new JSONArray().put(new JSONObject(event.toString()));

        ScriptedTransport transport = new ScriptedTransport(request -> {
            if (request.method.equals("GET") && request.url.contains("/current.json?ref=main")) {
                return packageResponse(pack);
            }
            if (request.method.equals("GET") && request.url.endsWith("/events?ref=main")) {
                return jsonResponse(200, new JSONArray().put(new JSONObject()
                        .put("name", "device-A-2026-01.json")
                        .put("type", "file")));
            }
            if (request.method.equals("GET") && request.url.contains("/events/device-A-2026-01.json?ref=main")) {
                return jsonResponse(200,
                        TestFixtures.githubFile(TestFixtures.eventBatch("device-A", remoteEvents), "event-sha"));
            }
            throw new IOException("unexpected request");
        });

        GitHubClient.SyncResult result = client(transport).sync(
                GitHubClient.DEFAULT_OWNER,
                GitHubClient.DEFAULT_REPO,
                GitHubClient.DEFAULT_BRANCH,
                TOKEN,
                "device-A",
                new JSONArray().put(event));

        assertEquals(0, result.uploadedCount);
        assertEquals(1, result.events.length());
        assertEquals(4, transport.requests.size());
    }

    @Test
    public void permissionErrorsNeverExposeTokenOrResponseBody() throws Exception {
        ScriptedTransport transport = new ScriptedTransport(request ->
                new GitHubClient.Response(403,
                        ("{\"message\":\"denied " + TOKEN + "\"}").getBytes(StandardCharsets.UTF_8)));

        GitHubClient.SyncResult result = client(transport).sync(
                GitHubClient.DEFAULT_OWNER,
                GitHubClient.DEFAULT_REPO,
                GitHubClient.DEFAULT_BRANCH,
                TOKEN,
                "device-A",
                new JSONArray());

        assertEquals(GitHubClient.FailureKind.AUTH, result.eventFailure.failureKind);
        assertEquals(403, result.eventFailure.statusCode);
        assertFalse(result.eventFailure.getMessage().contains(TOKEN));
        assertFalse(result.eventFailure.getMessage().contains("denied"));
        assertEquals(GitHubClient.FailureKind.AUTH, result.packFailure.failureKind);
    }

    @Test
    public void customContentPrefixIsUsedAndTraversalPrefixesAreRejected() throws Exception {
        JSONObject pack = TestFixtures.packageWith(
                java.util.Collections.singletonList("csv:0001"), java.util.Collections.emptyList());
        ScriptedTransport transport = new ScriptedTransport(request -> {
            if (request.method.equals("GET") && request.url.contains("/current.json?ref=main")) {
                return packageResponse(pack);
            }
            if (request.method.equals("GET") && request.url.endsWith("/events?ref=main")) {
                return jsonResponse(200, new JSONArray());
            }
            throw new IOException("unexpected request");
        });

        client("watch-words-verification/run-20261012", transport).sync(
                GitHubClient.DEFAULT_OWNER,
                GitHubClient.DEFAULT_REPO,
                GitHubClient.DEFAULT_BRANCH,
                TOKEN,
                "device-A",
                new JSONArray());

        assertTrue(transport.requests.get(0).url.contains(
                "/contents/watch-words-verification/run-20261012/events?ref=main"));
        assertTrue(transport.requests.get(1).url.contains(
                "/contents/watch-words-verification/run-20261012/current.json"));
        for (String unsafe : new String[] {
                "../watch-words", "watch-words/../private", "watch//events", "watch\\events"
        }) {
            try {
                new GitHubClient(unsafe, transport);
                fail("expected unsafe prefix to be rejected: " + unsafe);
            } catch (IllegalArgumentException expected) {
                // Expected failure.
            }
        }
    }

    @Test
    public void incompatiblePackageDoesNotBlockRecordUploadAndOnlyConfirmedIdsAreReturned() throws Exception {
        JSONObject event = TestFixtures.answer(
                "00000000-0000-0000-0000-000000000081", 1,
                TestFixtures.at("2026-02-01", 9, 0), "csv:0001",
                "correct", "forward", "primary", false);
        JSONObject incompatible = new JSONObject().put("schemaVersion", 99).put("words", new JSONArray());
        ScriptedTransport transport = new ScriptedTransport(request -> {
            if (request.method.equals("GET") && request.url.endsWith("/events?ref=main")) {
                return jsonResponse(200, new JSONArray());
            }
            if (request.method.equals("PUT")) return new GitHubClient.Response(201, new byte[0]);
            if (request.method.equals("GET") && request.url.contains("/current.json?ref=main")) {
                return packageResponse(incompatible);
            }
            throw new IOException("unexpected request");
        });

        GitHubClient.SyncResult result = client(transport).sync(
                GitHubClient.DEFAULT_OWNER, GitHubClient.DEFAULT_REPO, GitHubClient.DEFAULT_BRANCH,
                TOKEN, "device-A", new JSONArray().put(event));

        assertNull(result.pack);
        assertTrue(result.eventsComplete);
        assertEquals(GitHubClient.FailureKind.PROTOCOL, result.packFailure.failureKind);
        assertTrue(result.acknowledgedEventIds.contains(event.optString("id")));
        assertEquals(1, result.uploadedCount);
        assertEquals(1, countRequests(transport, "PUT"));
    }

    @Test
    public void badForeignEventFileIsQuarantinedWithoutBlockingThisDevice() throws Exception {
        JSONObject event = TestFixtures.answer(
                "00000000-0000-0000-0000-000000000082", 1,
                TestFixtures.at("2026-03-01", 9, 0), "csv:0001",
                "correct", "forward", "primary", false);
        JSONObject badBatch = new JSONObject().put("schemaVersion", 2)
                .put("deviceId", "device-B").put("events", new JSONArray());
        ScriptedTransport transport = new ScriptedTransport(request -> {
            if (request.method.equals("GET") && request.url.endsWith("/events?ref=main")) {
                return jsonResponse(200, new JSONArray().put(new JSONObject()
                        .put("name", "device-B-2026-03.json").put("type", "file")));
            }
            if (request.method.equals("GET") && request.url.contains("/events/device-B-2026-03.json?ref=main")) {
                return jsonResponse(200, TestFixtures.githubFile(badBatch, "bad-sha"));
            }
            if (request.method.equals("PUT")) return new GitHubClient.Response(201, new byte[0]);
            if (request.method.equals("GET") && request.url.contains("/current.json?ref=main")) {
                return packageResponse(TestFixtures.packageWith(
                        Collections.singletonList("csv:0001"), Collections.emptyList()));
            }
            throw new IOException("unexpected request");
        });

        GitHubClient.SyncResult result = client(transport).sync(
                GitHubClient.DEFAULT_OWNER, GitHubClient.DEFAULT_REPO, GitHubClient.DEFAULT_BRANCH,
                TOKEN, "device-A", new JSONArray().put(event));

        assertTrue(result.eventsComplete);
        assertTrue(result.acknowledgedEventIds.contains(event.optString("id")));
        assertTrue(result.eventWarning.contains("device-B-2026-03.json"));
        assertEquals(1, countRequests(transport, "PUT"));
    }

    @Test
    public void partialMonthFailureAcknowledgesOnlyConfirmedMonth() throws Exception {
        JSONObject january = TestFixtures.answer(
                "00000000-0000-0000-0000-000000000083", 1,
                TestFixtures.at("2026-01-31", 9, 0), "csv:0001",
                "correct", "forward", "primary", false);
        JSONObject february = TestFixtures.answer(
                "00000000-0000-0000-0000-000000000084", 2,
                TestFixtures.at("2026-02-01", 9, 0), "csv:0001",
                "wrong", "forward", "primary", false);
        ScriptedTransport transport = new ScriptedTransport(request -> {
            if (request.method.equals("GET") && request.url.endsWith("/events?ref=main")) {
                return jsonResponse(200, new JSONArray());
            }
            if (request.method.equals("PUT") && request.url.endsWith("device-A-2026-01.json")) {
                return new GitHubClient.Response(201, new byte[0]);
            }
            if (request.method.equals("PUT") && request.url.endsWith("device-A-2026-02.json")) {
                return new GitHubClient.Response(401, new byte[0]);
            }
            if (request.method.equals("GET") && request.url.contains("/current.json?ref=main")) {
                return packageResponse(TestFixtures.packageWith(
                        Collections.singletonList("csv:0001"), Collections.emptyList()));
            }
            throw new IOException("unexpected request");
        });

        GitHubClient.SyncResult result = client(transport).sync(
                GitHubClient.DEFAULT_OWNER, GitHubClient.DEFAULT_REPO, GitHubClient.DEFAULT_BRANCH,
                TOKEN, "device-A", new JSONArray().put(january).put(february));

        assertFalse(result.eventsComplete);
        assertEquals(Set.of(january.optString("id")), result.acknowledgedEventIds);
        assertEquals(1, result.uploadedCount);
        assertEquals(GitHubClient.FailureKind.AUTH, result.eventFailure.failureKind);
        assertEquals(2, countRequests(transport, "PUT"));
        assertEquals(2, result.events.length());
    }

    @Test
    public void ambiguousUploadIsConfirmedByReadbackWithoutSecondPut() throws Exception {
        JSONObject event = TestFixtures.answer(
                "00000000-0000-0000-0000-000000000085", 1,
                TestFixtures.at("2026-04-01", 9, 0), "csv:0001",
                "correct", "forward", "primary", false);
        AtomicBoolean committed = new AtomicBoolean(false);
        ScriptedTransport transport = new ScriptedTransport(request -> {
            if (request.method.equals("GET") && request.url.endsWith("/events?ref=main")) {
                return jsonResponse(200, new JSONArray());
            }
            if (request.method.equals("PUT")) {
                committed.set(true);
                throw new IOException("simulated connection lost after commit");
            }
            if (request.method.equals("GET") && request.url.contains("/events/device-A-2026-04.json?ref=main")) {
                JSONObject batch = new JSONObject().put("schemaVersion", 1).put("deviceId", "device-A")
                        .put("events", new JSONArray().put(event));
                return jsonResponse(200, TestFixtures.githubFile(batch, "committed-sha"));
            }
            if (request.method.equals("GET") && request.url.contains("/current.json?ref=main")) {
                return packageResponse(TestFixtures.packageWith(
                        Collections.singletonList("csv:0001"), Collections.emptyList()));
            }
            throw new IOException("unexpected request");
        });

        GitHubClient.SyncResult result = client(transport).sync(
                GitHubClient.DEFAULT_OWNER, GitHubClient.DEFAULT_REPO, GitHubClient.DEFAULT_BRANCH,
                TOKEN, "device-A", new JSONArray().put(event));

        assertTrue(committed.get());
        assertTrue(result.eventsComplete);
        assertEquals(1, result.uploadedCount);
        assertEquals(Set.of(event.optString("id")), result.acknowledgedEventIds);
        assertEquals(1, countRequests(transport, "PUT"));
    }

    @Test
    public void largeHistoryIsSplitIntoBoundedDeterministicFiles() throws Exception {
        JSONArray events = new JSONArray();
        String evidence = String.join("", Collections.nCopies(100, "abcdefghijklmnopqrst"));
        for (int i = 1; i <= 170; i++) {
            JSONObject event = TestFixtures.answer(String.format(
                    java.util.Locale.ROOT, "00000000-0000-0000-0000-%012d", i), i,
                    TestFixtures.at("2026-05-01", 9, 0), "csv:0001",
                    "correct", "forward", "primary", false).put("evidence", evidence);
            events.put(event);
        }
        ScriptedTransport transport = new ScriptedTransport(request -> {
            if (request.method.equals("GET") && request.url.endsWith("/events?ref=main")) {
                return jsonResponse(200, new JSONArray());
            }
            if (request.method.equals("PUT")) return new GitHubClient.Response(201, new byte[0]);
            if (request.method.equals("GET") && request.url.contains("/current.json?ref=main")) {
                return packageResponse(TestFixtures.packageWith(
                        Collections.singletonList("csv:0001"), Collections.emptyList()));
            }
            throw new IOException("unexpected request");
        });

        GitHubClient.SyncResult result = client(transport).sync(
                GitHubClient.DEFAULT_OWNER, GitHubClient.DEFAULT_REPO, GitHubClient.DEFAULT_BRANCH,
                TOKEN, "device-A", events);

        assertTrue(result.eventsComplete);
        assertEquals(170, result.uploadedCount);
        assertEquals(170, result.acknowledgedEventIds.size());
        int putCount = 0;
        for (GitHubClient.Request request : transport.requests) {
            if (!request.method.equals("PUT")) continue;
            putCount++;
            assertTrue(request.url.contains("-part-"));
            JSONObject body = new JSONObject(new String(request.body, StandardCharsets.UTF_8));
            byte[] raw = Base64.getDecoder().decode(body.getString("content"));
            assertTrue(raw.length <= GitHubClient.MAX_EVENT_BATCH_BYTES);
        }
        assertTrue(putCount >= 2);
    }

    @Test
    public void rateLimitIsClassifiedAndCarriesRetryDelay() throws Exception {
        Map<String, String> headers = new HashMap<>();
        headers.put("Retry-After", "30");
        ScriptedTransport transport = new ScriptedTransport(request -> {
            if (request.method.equals("GET") && request.url.endsWith("/events?ref=main")) {
                return new GitHubClient.Response(429, "rate limited".getBytes(StandardCharsets.UTF_8), headers);
            }
            if (request.method.equals("GET") && request.url.contains("/current.json?ref=main")) {
                return packageResponse(TestFixtures.packageWith(
                        Collections.singletonList("csv:0001"), Collections.emptyList()));
            }
            throw new IOException("unexpected request");
        });

        GitHubClient.SyncResult result = client(transport).sync(
                GitHubClient.DEFAULT_OWNER, GitHubClient.DEFAULT_REPO, GitHubClient.DEFAULT_BRANCH,
                TOKEN, "device-A", new JSONArray());

        assertEquals(GitHubClient.FailureKind.RATE_LIMITED, result.eventFailure.failureKind);
        assertEquals(429, result.eventFailure.statusCode);
        assertEquals(30_000L, result.eventFailure.retryAfterMillis);
        assertEquals(3, transport.requests.size());
    }

    @Test
    public void validPackageIndexDownloadsOnlyFixedDatedPathsAndVerifiesBytes() throws Exception {
        JSONObject current = datedPackage("2026-10-12");
        JSONObject prior = datedPackage("2026-10-11");
        byte[] priorBytes = prior.toString().getBytes(StandardCharsets.UTF_8);
        JSONObject index = new JSONObject().put("schemaVersion", 1).put("packages", new JSONArray().put(
                new JSONObject().put("path", "daily/2026-10-11.json").put("sha256", sha256(priorBytes))));
        ScriptedTransport transport = new ScriptedTransport(request -> {
            if (request.method.equals("GET") && request.url.endsWith("/events?ref=main")) {
                return jsonResponse(200, new JSONArray());
            }
            if (request.method.equals("GET") && request.url.contains("/current.json?ref=main")) {
                return packageResponse(current);
            }
            if (request.method.equals("GET") && request.url.contains("/index.json?ref=main")) {
                return jsonResponse(200, TestFixtures.githubFile(index, "index-sha"));
            }
            if (request.method.equals("GET") && request.url.contains("/daily/2026-10-11.json?ref=main")) {
                return packageResponse(prior);
            }
            throw new IOException("unexpected request");
        });

        GitHubClient.SyncResult result = client(transport).sync(
                GitHubClient.DEFAULT_OWNER, GitHubClient.DEFAULT_REPO, GitHubClient.DEFAULT_BRANCH,
                TOKEN, "device-A", new JSONArray());

        assertEquals("2026-10-12", result.pack.optString("date"));
        assertEquals(2, result.packages.size());
        assertEquals("2026-10-11", result.packages.get(1).optString("date"));
        assertTrue(result.warning.isEmpty());
        assertTrue(transport.requests.stream().anyMatch(request ->
                request.url.contains("/daily/2026-10-11.json?ref=main")));
    }

    @Test
    public void packageLargerThanOneMiBUsesRawContentsResponse() throws Exception {
        JSONObject pack = datedPackage("2026-10-12").put("extensionPayload", "x".repeat(1024 * 1024 + 32));
        byte[] raw = pack.toString().getBytes(StandardCharsets.UTF_8);
        assertTrue(raw.length > 1024 * 1024);
        AtomicBoolean rawMediaRequested = new AtomicBoolean(false);
        ScriptedTransport transport = new ScriptedTransport(request -> {
            if (request.method.equals("GET") && request.url.endsWith("/events?ref=main")) {
                return jsonResponse(200, new JSONArray());
            }
            if (request.method.equals("GET") && request.url.contains("/current.json?ref=main")) {
                rawMediaRequested.set("application/vnd.github.raw+json".equals(request.headers.get("Accept")));
                return new GitHubClient.Response(200, raw);
            }
            throw new IOException("unexpected request");
        });

        GitHubClient.SyncResult result = client(transport).sync(
                GitHubClient.DEFAULT_OWNER, GitHubClient.DEFAULT_REPO, GitHubClient.DEFAULT_BRANCH,
                TOKEN, "device-A", new JSONArray());

        assertTrue(rawMediaRequested.get());
        assertEquals("2026-10-12", result.pack.optString("date"));
        assertNull(result.packFailure);
    }

    @Test
    public void unsafePackageIndexPathIsRejectedBeforeAnyFileRequest() throws Exception {
        JSONObject current = datedPackage("2026-10-12");
        JSONObject index = new JSONObject().put("schemaVersion", 1).put("packages", new JSONArray().put(
                new JSONObject().put("path", "../../private.json").put("sha256", "a".repeat(64))));
        ScriptedTransport transport = new ScriptedTransport(request -> {
            if (request.method.equals("GET") && request.url.endsWith("/events?ref=main")) {
                return jsonResponse(200, new JSONArray());
            }
            if (request.method.equals("GET") && request.url.contains("/current.json?ref=main")) {
                return packageResponse(current);
            }
            if (request.method.equals("GET") && request.url.contains("/index.json?ref=main")) {
                return jsonResponse(200, TestFixtures.githubFile(index, "index-sha"));
            }
            throw new IOException("unexpected request");
        });

        GitHubClient.SyncResult result = client(transport).sync(
                GitHubClient.DEFAULT_OWNER, GitHubClient.DEFAULT_REPO, GitHubClient.DEFAULT_BRANCH,
                TOKEN, "device-A", new JSONArray());

        assertEquals(1, result.packages.size());
        assertTrue(result.warning.contains("索引格式无效"));
        assertFalse(transport.requests.stream().anyMatch(request -> request.url.contains("private.json")));
    }

    @Test
    public void packageIndexChecksumMismatchSkipsOnlyThatPackage() throws Exception {
        JSONObject current = datedPackage("2026-10-12");
        JSONObject prior = datedPackage("2026-10-11");
        JSONObject index = new JSONObject().put("schemaVersion", 1).put("packages", new JSONArray().put(
                new JSONObject().put("path", "daily/2026-10-11.json").put("sha256", "0".repeat(64))));
        ScriptedTransport transport = new ScriptedTransport(request -> {
            if (request.method.equals("GET") && request.url.endsWith("/events?ref=main")) {
                return jsonResponse(200, new JSONArray());
            }
            if (request.method.equals("GET") && request.url.contains("/current.json?ref=main")) {
                return packageResponse(current);
            }
            if (request.method.equals("GET") && request.url.contains("/index.json?ref=main")) {
                return jsonResponse(200, TestFixtures.githubFile(index, "index-sha"));
            }
            if (request.method.equals("GET") && request.url.contains("/daily/2026-10-11.json?ref=main")) {
                return packageResponse(prior);
            }
            throw new IOException("unexpected request");
        });

        GitHubClient.SyncResult result = client(transport).sync(
                GitHubClient.DEFAULT_OWNER, GitHubClient.DEFAULT_REPO, GitHubClient.DEFAULT_BRANCH,
                TOKEN, "device-A", new JSONArray());

        assertEquals(1, result.packages.size());
        assertEquals(GitHubClient.FailureKind.PROTOCOL, result.packFailure.failureKind);
        assertTrue(result.warning.contains("校验失败"));
    }

    @Test
    public void cancelledGuardPreventsEverySubsequentTransportRequest() throws Exception {
        JSONObject event = TestFixtures.answer(
                "00000000-0000-0000-0000-000000000086", 1,
                TestFixtures.at("2026-06-01", 9, 0), "csv:0001",
                "correct", "forward", "primary", false);
        AtomicInteger guardCalls = new AtomicInteger();
        ScriptedTransport transport = new ScriptedTransport(request -> {
            if (request.method.equals("GET") && request.url.endsWith("/events?ref=main")) {
                return jsonResponse(200, new JSONArray());
            }
            throw new IOException("unexpected request");
        });
        GitHubClient guarded = new GitHubClient("watch-words", transport, millis -> {},
                () -> guardCalls.incrementAndGet() == 1);

        GitHubClient.SyncResult result = guarded.sync(
                GitHubClient.DEFAULT_OWNER, GitHubClient.DEFAULT_REPO, GitHubClient.DEFAULT_BRANCH,
                TOKEN, "device-A", new JSONArray().put(event));

        assertFalse(result.eventsComplete);
        assertEquals(GitHubClient.FailureKind.CANCELLED, result.eventFailure.failureKind);
        assertEquals(GitHubClient.FailureKind.CANCELLED, result.packFailure.failureKind);
        assertEquals(1, transport.requests.size());
    }

    private static GitHubClient.Response jsonResponse(int status, Object value) {
        return new GitHubClient.Response(
                status, value.toString().getBytes(StandardCharsets.UTF_8));
    }

    private static GitHubClient.Response packageResponse(JSONObject pack) {
        return new GitHubClient.Response(200, pack.toString().getBytes(StandardCharsets.UTF_8));
    }

    private static GitHubClient client(GitHubClient.Transport transport) {
        return client("watch-words", transport);
    }

    private static GitHubClient client(String prefix, GitHubClient.Transport transport) {
        return new GitHubClient(prefix, transport, millis -> {}, () -> true);
    }

    private static int countRequests(ScriptedTransport transport, String method) {
        int count = 0;
        for (GitHubClient.Request request : transport.requests) {
            if (method.equals(request.method)) count++;
        }
        return count;
    }

    private static JSONObject datedPackage(String date) throws Exception {
        return TestFixtures.packageWith(Collections.singletonList("csv:0001"), Collections.emptyList())
                .put("date", date).put("packageId", "daily-" + date);
    }

    private static String sha256(byte[] bytes) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
        StringBuilder hex = new StringBuilder();
        for (byte value : digest) hex.append(String.format(java.util.Locale.ROOT, "%02x", value));
        return hex.toString();
    }

    private static JSONObject decodedBatch(String encoded) throws Exception {
        byte[] bytes = Base64.getDecoder().decode(encoded);
        return new JSONObject(new String(bytes, StandardCharsets.UTF_8));
    }

    private static final class ScriptedTransport implements GitHubClient.Transport {
        private final Responder responder;
        private final List<GitHubClient.Request> requests = new ArrayList<>();
        private boolean firstPutConflict;
        private boolean conflictUsed;

        private ScriptedTransport(Responder responder) {
            this.responder = responder;
        }

        private void setFirstPutConflict() {
            firstPutConflict = true;
        }

        @Override
        public GitHubClient.Response execute(GitHubClient.Request request) throws IOException {
            requests.add(request);
            if (firstPutConflict && !conflictUsed && request.method.equals("PUT")) {
                conflictUsed = true;
                return new GitHubClient.Response(409,
                        "{\"message\":\"conflict\"}".getBytes(StandardCharsets.UTF_8));
            }
            try {
                return responder.respond(request);
            } catch (IOException e) {
                if (request.method.equals("GET") && request.url.contains("/index.json?ref=")) {
                    return new GitHubClient.Response(404, new byte[0]);
                }
                throw e;
            } catch (Exception e) {
                throw new IOException("scripted response failed");
            }
        }
    }

    @FunctionalInterface
    private interface Responder {
        GitHubClient.Response respond(GitHubClient.Request request) throws Exception;
    }
}
