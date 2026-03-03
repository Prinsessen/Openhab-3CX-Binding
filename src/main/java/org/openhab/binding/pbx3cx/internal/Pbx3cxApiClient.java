/*
 * Copyright (c) 2010-2026 Contributors to the openHAB project
 *
 * See the NOTICE file(s) distributed with this work for additional
 * information.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * http://www.eclipse.org/legal/epl-2.0
 *
 * SPDX-License-Identifier: EPL-2.0
 */
package org.openhab.binding.pbx3cx.internal;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.KeyManagementException;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.locks.ReentrantLock;

import javax.net.ssl.SSLContext;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.openhab.binding.pbx3cx.internal.dto.ActiveCall;
import org.openhab.binding.pbx3cx.internal.dto.CallQueue;
import org.openhab.binding.pbx3cx.internal.dto.Extension;
import org.openhab.binding.pbx3cx.internal.dto.RingGroup;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * The {@link Pbx3cxApiClient} handles all HTTP communication with the 3CX xAPI.
 * <p>
 * Manages JWT token lifecycle (60s expiry, auto-refresh) and provides methods
 * for polling active calls, user presence, trunk status, and initiating calls.
 *
 * @author openHAB Community - Initial contribution
 */
@NonNullByDefault
public class Pbx3cxApiClient {

    private final Logger logger = LoggerFactory.getLogger(Pbx3cxApiClient.class);
    private final Gson gson = new GsonBuilder().create();

    private final String baseUrl;
    private final String username;
    private final String password;
    private final HttpClient httpClient;

    private @Nullable String accessToken;
    private @Nullable String refreshToken;
    private Instant tokenExpiresAt = Instant.EPOCH;
    private final ReentrantLock tokenLock = new ReentrantLock();

    private boolean authenticated = false;

    public Pbx3cxApiClient(Pbx3cxServerConfiguration config) {
        this.baseUrl = String.format("https://%s:%d", config.hostname, config.port);
        this.username = config.username;
        this.password = config.password;

        HttpClient.Builder builder = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10));

        if (!config.verifySsl) {
            try {
                SSLContext sslContext = createTrustAllSslContext();
                builder.sslContext(sslContext);
            } catch (Exception e) {
                logger.warn("Failed to create trust-all SSL context, using default: {}", e.getMessage());
            }
        }

        this.httpClient = builder.build();
    }

    /**
     * Create an SSL context that trusts all certificates (for self-signed 3CX certs).
     * Delegates to {@link TrustAllSslUtil} which is not annotated with NonNullByDefault.
     */
    private static SSLContext createTrustAllSslContext() throws NoSuchAlgorithmException, KeyManagementException {
        return TrustAllSslUtil.createTrustAllSslContext();
    }

    /**
     * Authenticate with 3CX xAPI, obtaining JWT tokens.
     */
    public boolean authenticate() throws IOException, InterruptedException {
        tokenLock.lock();
        try {
            JsonObject body = new JsonObject();
            body.addProperty("Username", username);
            body.addProperty("Password", password);

            HttpRequest request = HttpRequest.newBuilder().uri(URI.create(baseUrl + Pbx3cxBindingConstants.API_LOGIN))
                    .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                    .header("Content-Type", "application/json").timeout(Duration.ofSeconds(10)).build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                logger.error("3CX auth failed: HTTP {}: {}", response.statusCode(), truncate(response.body(), 200));
                authenticated = false;
                return false;
            }

            JsonObject data = JsonParser.parseString(response.body()).getAsJsonObject();
            String status = getJsonString(data, "Status");
            if (!"AuthSuccess".equals(status)) {
                logger.error("3CX auth rejected: {}", status);
                authenticated = false;
                return false;
            }

            JsonObject token = data.getAsJsonObject("Token");
            if (token == null) {
                logger.error("3CX auth response missing Token object");
                authenticated = false;
                return false;
            }

            accessToken = getJsonString(token, "access_token");
            refreshToken = getJsonString(token, "refresh_token");
            int expiresIn = token.has("expires_in") ? token.get("expires_in").getAsInt() : 60;
            tokenExpiresAt = Instant.now().plusSeconds(expiresIn);

            logger.info("3CX authenticated (token expires in {}s)", expiresIn);
            authenticated = true;
            return true;
        } finally {
            tokenLock.unlock();
        }
    }

    /**
     * Ensure we have a valid token, refreshing if within margin.
     */
    public boolean ensureAuthenticated() throws IOException, InterruptedException {
        tokenLock.lock();
        try {
            if (accessToken != null && Instant.now().plusSeconds(Pbx3cxBindingConstants.TOKEN_REFRESH_MARGIN_SECONDS)
                    .isBefore(tokenExpiresAt)) {
                return true;
            }
            // Token expired or about to expire — re-authenticate
            return authenticate();
        } finally {
            tokenLock.unlock();
        }
    }

    public boolean isAuthenticated() {
        return authenticated && accessToken != null;
    }

    /**
     * Poll active calls from xAPI.
     *
     * @return list of active calls, empty list on error
     */
    public List<ActiveCall> getActiveCalls() throws IOException, InterruptedException {
        String json = apiGet(Pbx3cxBindingConstants.API_ACTIVE_CALLS);
        if (json == null) {
            return List.of();
        }

        List<ActiveCall> calls = new ArrayList<>();
        try {
            JsonObject root = JsonParser.parseString(json).getAsJsonObject();
            JsonArray values = root.getAsJsonArray("value");
            if (values != null) {
                for (JsonElement elem : values) {
                    JsonObject obj = elem.getAsJsonObject();
                    ActiveCall call = new ActiveCall();
                    call.setId(obj.has("Id") ? obj.get("Id").getAsInt() : 0);
                    call.setCaller(getJsonString(obj, "Caller"));
                    call.setCallee(getJsonString(obj, "Callee"));
                    call.setStatus(getJsonString(obj, "Status"));
                    call.setEstablishedAt(getJsonString(obj, "EstablishedAt"));
                    calls.add(call);
                }
            }
        } catch (Exception e) {
            logger.warn("Error parsing ActiveCalls response: {}", e.getMessage());
        }
        return calls;
    }

    /**
     * Poll extension/user presence from xAPI.
     *
     * @return list of extensions, empty on error
     */
    public List<Extension> getUsers() throws IOException, InterruptedException {
        String json = apiGet(Pbx3cxBindingConstants.API_USERS
                + "?$select=Number,FirstName,LastName,CurrentProfileName,QueueStatus,IsRegistered");
        if (json == null) {
            return List.of();
        }

        List<Extension> extensions = new ArrayList<>();
        try {
            JsonObject root = JsonParser.parseString(json).getAsJsonObject();
            JsonArray values = root.getAsJsonArray("value");
            if (values != null) {
                for (JsonElement elem : values) {
                    JsonObject obj = elem.getAsJsonObject();
                    Extension ext = new Extension();
                    ext.setNumber(getJsonString(obj, "Number"));
                    ext.setFirstName(getJsonString(obj, "FirstName"));
                    ext.setLastName(getJsonString(obj, "LastName"));
                    ext.setCurrentProfileName(getJsonString(obj, "CurrentProfileName"));
                    ext.setQueueStatus(getJsonString(obj, "QueueStatus"));
                    ext.setIsRegistered(obj.has("IsRegistered") && obj.get("IsRegistered").getAsBoolean());
                    extensions.add(ext);
                }
            }
        } catch (Exception e) {
            logger.warn("Error parsing Users response: {}", e.getMessage());
        }
        return extensions;
    }

    /**
     * Poll ring groups from xAPI.
     *
     * @return list of ring groups with their members
     */
    public List<RingGroup> getRingGroups() throws IOException, InterruptedException {
        String json = apiGet(Pbx3cxBindingConstants.API_RING_GROUPS);
        if (json == null) {
            return List.of();
        }

        List<RingGroup> groups = new ArrayList<>();
        try {
            JsonObject root = JsonParser.parseString(json).getAsJsonObject();
            JsonArray values = root.getAsJsonArray("value");
            if (values != null) {
                for (JsonElement elem : values) {
                    JsonObject obj = elem.getAsJsonObject();
                    RingGroup rg = new RingGroup();
                    rg.setId(obj.has("Id") ? obj.get("Id").getAsInt() : 0);
                    rg.setNumber(getJsonString(obj, "Number"));
                    rg.setName(getJsonString(obj, "Name"));
                    rg.setRingStrategy(getJsonString(obj, "RingStrategy"));
                    rg.setIsRegistered(obj.has("IsRegistered") && obj.get("IsRegistered").getAsBoolean());
                    rg.setRingTime(obj.has("RingTime") ? obj.get("RingTime").getAsInt() : 0);
                    groups.add(rg);
                }
            }
        } catch (Exception e) {
            logger.warn("Error parsing RingGroups response: {}", e.getMessage());
        }
        return groups;
    }

    /**
     * Poll ring group members from xAPI.
     *
     * @param ringGroupId the internal ID of the ring group
     * @return list of members
     */
    public List<RingGroup.Member> getRingGroupMembers(int ringGroupId) throws IOException, InterruptedException {
        String json = apiGet(Pbx3cxBindingConstants.API_RING_GROUPS + "(" + ringGroupId + ")/Members");
        if (json == null) {
            return List.of();
        }

        List<RingGroup.Member> members = new ArrayList<>();
        try {
            JsonObject root = JsonParser.parseString(json).getAsJsonObject();
            JsonArray values = root.getAsJsonArray("value");
            if (values != null) {
                for (JsonElement elem : values) {
                    JsonObject obj = elem.getAsJsonObject();
                    RingGroup.Member m = new RingGroup.Member();
                    m.setNumber(getJsonString(obj, "Number"));
                    m.setName(getJsonString(obj, "Name"));
                    members.add(m);
                }
            }
        } catch (Exception e) {
            logger.warn("Error parsing RingGroup members: {}", e.getMessage());
        }
        return members;
    }

    /**
     * Poll call queues from xAPI.
     *
     * @return list of call queues with their agents
     */
    public List<CallQueue> getQueues() throws IOException, InterruptedException {
        String json = apiGet(Pbx3cxBindingConstants.API_QUEUES);
        if (json == null) {
            return List.of();
        }

        List<CallQueue> queues = new ArrayList<>();
        try {
            JsonObject root = JsonParser.parseString(json).getAsJsonObject();
            JsonArray values = root.getAsJsonArray("value");
            if (values != null) {
                for (JsonElement elem : values) {
                    JsonObject obj = elem.getAsJsonObject();
                    CallQueue q = new CallQueue();
                    q.setId(obj.has("Id") ? obj.get("Id").getAsInt() : 0);
                    q.setNumber(getJsonString(obj, "Number"));
                    q.setName(getJsonString(obj, "Name"));
                    q.setPollingStrategy(getJsonString(obj, "PollingStrategy"));
                    q.setIsRegistered(obj.has("IsRegistered") && obj.get("IsRegistered").getAsBoolean());
                    q.setRingTimeout(obj.has("RingTimeout") ? obj.get("RingTimeout").getAsInt() : 0);
                    q.setMasterTimeout(obj.has("MasterTimeout") ? obj.get("MasterTimeout").getAsInt() : 0);
                    queues.add(q);
                }
            }
        } catch (Exception e) {
            logger.warn("Error parsing Queues response: {}", e.getMessage());
        }
        return queues;
    }

    /**
     * Poll queue agents from xAPI.
     *
     * @param queueId the internal ID of the queue
     * @return list of agents
     */
    public List<CallQueue.Agent> getQueueAgents(int queueId) throws IOException, InterruptedException {
        String json = apiGet(Pbx3cxBindingConstants.API_QUEUES + "(" + queueId + ")/Agents");
        if (json == null) {
            return List.of();
        }

        List<CallQueue.Agent> agents = new ArrayList<>();
        try {
            JsonObject root = JsonParser.parseString(json).getAsJsonObject();
            JsonArray values = root.getAsJsonArray("value");
            if (values != null) {
                for (JsonElement elem : values) {
                    JsonObject obj = elem.getAsJsonObject();
                    CallQueue.Agent a = new CallQueue.Agent();
                    a.setNumber(getJsonString(obj, "Number"));
                    a.setName(getJsonString(obj, "Name"));
                    a.setSkillGroup(getJsonString(obj, "SkillGroup"));
                    agents.add(a);
                }
            }
        } catch (Exception e) {
            logger.warn("Error parsing Queue agents: {}", e.getMessage());
        }
        return agents;
    }

    /**
     * Poll trunk status from xAPI.
     *
     * @return JSON array of trunk objects, or empty string on error
     */
    public @Nullable String getTrunks() throws IOException, InterruptedException {
        return apiGet(Pbx3cxBindingConstants.API_TRUNKS);
    }

    /**
     * Initiate a call via xAPI MakeCall.
     *
     * @param extension the extension that will ring first
     * @param destination the number to dial when picked up
     * @return true on success
     */
    public boolean makeCall(String extension, String destination) throws IOException, InterruptedException {
        if (!ensureAuthenticated()) {
            logger.warn("MakeCall failed: not authenticated");
            return false;
        }

        JsonObject body = new JsonObject();
        body.addProperty("dn", extension);
        body.addProperty("destination", destination);
        body.addProperty("testCall", false);
        body.addProperty("contact", "");

        HttpRequest request = HttpRequest.newBuilder().uri(URI.create(baseUrl + Pbx3cxBindingConstants.API_MAKE_CALL))
                .POST(HttpRequest.BodyPublishers.ofString(body.toString())).header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + accessToken).timeout(Duration.ofSeconds(10)).build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() >= 200 && response.statusCode() < 300) {
            logger.info("MakeCall: {} → {} SUCCESS", extension, destination);
            return true;
        } else {
            logger.warn("MakeCall failed: HTTP {} {}", response.statusCode(), truncate(response.body(), 200));
            return false;
        }
    }

    /**
     * Execute an authenticated GET request to the xAPI.
     */
    private @Nullable String apiGet(String path) throws IOException, InterruptedException {
        if (!ensureAuthenticated()) {
            return null;
        }

        HttpRequest request = HttpRequest.newBuilder().uri(URI.create(baseUrl + path)).GET()
                .header("Authorization", "Bearer " + accessToken).timeout(Duration.ofSeconds(5)).build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() == 401) {
            // Token expired mid-flight, force re-auth on next call
            logger.debug("3CX token expired (401), will re-auth");
            tokenLock.lock();
            try {
                accessToken = null;
            } finally {
                tokenLock.unlock();
            }
            return null;
        }

        if (response.statusCode() != 200) {
            logger.warn("3CX API {} returned HTTP {}", path, response.statusCode());
            return null;
        }

        return response.body();
    }

    /**
     * Shut down the client and release resources.
     */
    public void dispose() {
        tokenLock.lock();
        try {
            accessToken = null;
            refreshToken = null;
            authenticated = false;
        } finally {
            tokenLock.unlock();
        }
    }

    private static @Nullable String getJsonString(JsonObject obj, String key) {
        if (obj.has(key) && !obj.get(key).isJsonNull()) {
            return obj.get(key).getAsString();
        }
        return null;
    }

    private static String truncate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }
}
