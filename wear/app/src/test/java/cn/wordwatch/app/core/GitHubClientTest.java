package cn.wordwatch.app.core;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
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
                return jsonResponse(200, TestFixtures.githubFile(pack, "package-sha"));
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

        GitHubClient.SyncResult result = new GitHubClient(transport).sync(
                GitHubClient.DEFAULT_OWNER,
                GitHubClient.DEFAULT_REPO,
                GitHubClient.DEFAULT_BRANCH,
                TOKEN,
                "device-A",
                new JSONArray().put(local));

        assertEquals(1, result.uploadedCount);
        assertEquals(2, result.events.length());
        assertEquals(5, transport.requests.size());
        GitHubClient.Request firstPut = transport.requests.get(2);
        GitHubClient.Request retryPut = transport.requests.get(4);
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
                return jsonResponse(200, TestFixtures.githubFile(pack, "package-sha"));
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

        GitHubClient.SyncResult result = new GitHubClient(transport).sync(
                GitHubClient.DEFAULT_OWNER,
                GitHubClient.DEFAULT_REPO,
                GitHubClient.DEFAULT_BRANCH,
                TOKEN,
                "device-A",
                new JSONArray().put(event));

        assertEquals(0, result.uploadedCount);
        assertEquals(1, result.events.length());
        assertEquals(3, transport.requests.size());
    }

    @Test
    public void permissionErrorsNeverExposeTokenOrResponseBody() throws Exception {
        ScriptedTransport transport = new ScriptedTransport(request ->
                new GitHubClient.Response(403,
                        ("{\"message\":\"denied " + TOKEN + "\"}").getBytes(StandardCharsets.UTF_8)));

        try {
            new GitHubClient(transport).sync(
                    GitHubClient.DEFAULT_OWNER,
                    GitHubClient.DEFAULT_REPO,
                    GitHubClient.DEFAULT_BRANCH,
                    TOKEN,
                    "device-A",
                    new JSONArray());
            fail("expected permission error");
        } catch (GitHubClient.SyncException expected) {
            assertEquals(403, expected.statusCode);
            assertFalse(expected.getMessage().contains(TOKEN));
            assertEquals("GitHub permission denied", expected.getMessage());
        }
    }

    @Test
    public void customContentPrefixIsUsedAndTraversalPrefixesAreRejected() throws Exception {
        JSONObject pack = TestFixtures.packageWith(
                java.util.Collections.singletonList("csv:0001"), java.util.Collections.emptyList());
        ScriptedTransport transport = new ScriptedTransport(request -> {
            if (request.method.equals("GET") && request.url.contains("/current.json?ref=main")) {
                return jsonResponse(200, TestFixtures.githubFile(pack, "package-sha"));
            }
            if (request.method.equals("GET") && request.url.endsWith("/events?ref=main")) {
                return jsonResponse(200, new JSONArray());
            }
            throw new IOException("unexpected request");
        });

        new GitHubClient("watch-words-verification/run-20261012", transport).sync(
                GitHubClient.DEFAULT_OWNER,
                GitHubClient.DEFAULT_REPO,
                GitHubClient.DEFAULT_BRANCH,
                TOKEN,
                "device-A",
                new JSONArray());

        assertTrue(transport.requests.get(0).url.contains(
                "/contents/watch-words-verification/run-20261012/current.json"));
        assertTrue(transport.requests.get(1).url.contains(
                "/contents/watch-words-verification/run-20261012/events?ref=main"));
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

    private static GitHubClient.Response jsonResponse(int status, Object value) {
        return new GitHubClient.Response(
                status, value.toString().getBytes(StandardCharsets.UTF_8));
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
