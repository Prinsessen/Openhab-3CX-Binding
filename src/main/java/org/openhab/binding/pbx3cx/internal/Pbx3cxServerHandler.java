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

import static org.openhab.binding.pbx3cx.internal.Pbx3cxBindingConstants.*;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.openhab.binding.pbx3cx.internal.discovery.Pbx3cxExtensionDiscoveryService;
import org.openhab.binding.pbx3cx.internal.dto.ActiveCall;
import org.openhab.binding.pbx3cx.internal.dto.CallQueue;
import org.openhab.binding.pbx3cx.internal.dto.CallerInfo;
import org.openhab.binding.pbx3cx.internal.dto.Extension;
import org.openhab.binding.pbx3cx.internal.dto.RingGroup;
import org.openhab.core.OpenHAB;
import org.openhab.core.library.types.DateTimeType;
import org.openhab.core.library.types.DecimalType;
import org.openhab.core.library.types.StringType;
import org.openhab.core.thing.Bridge;
import org.openhab.core.thing.ChannelUID;
import org.openhab.core.thing.Thing;
import org.openhab.core.thing.ThingStatus;
import org.openhab.core.thing.ThingStatusDetail;
import org.openhab.core.thing.binding.BaseBridgeHandler;
import org.openhab.core.thing.binding.ThingHandler;
import org.openhab.core.thing.binding.ThingHandlerService;
import org.openhab.core.types.Command;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * The {@link Pbx3cxServerHandler} manages the connection to the 3CX PBX server.
 * <p>
 * It polls xAPI for active calls (every N seconds), extension presence (every M seconds),
 * and trunk status (every K seconds). Call state changes are detected by comparing
 * current active calls with the previous snapshot.
 * <p>
 * Also handles the MakeCall command channel and notifies child extension handlers.
 *
 * @author openHAB Community - Initial contribution
 */
@NonNullByDefault
public class Pbx3cxServerHandler extends BaseBridgeHandler {

    private final Logger logger = LoggerFactory.getLogger(Pbx3cxServerHandler.class);

    private @Nullable Pbx3cxApiClient apiClient;
    private @Nullable ScheduledFuture<?> pollJob;
    private @Nullable ScheduledFuture<?> presenceJob;
    private @Nullable ScheduledFuture<?> trunkJob;
    private @Nullable ScheduledFuture<?> groupQueueJob;
    private @Nullable ScheduledFuture<?> autoIdleJob;
    private @Nullable ScheduledFuture<?> midnightResetJob;

    // Previous call snapshot for change detection
    private Map<Integer, ActiveCall> previousCalls = new HashMap<>();
    private Map<Integer, Long> callStartTimes = new HashMap<>();

    // Extension presence cache (for child handlers & discovery)
    private Map<String, ExtensionState> extensionStates = new HashMap<>();

    // Ring group & queue caches
    private Map<String, RingGroupState> ringGroupStates = new HashMap<>();
    private Map<String, QueueState> queueStates = new HashMap<>();

    // SIP client for MakeCall/AlarmCall via baresip
    private @Nullable BaresipSipClient sipClient;

    // Call state
    private String currentCallState = STATE_IDLE;
    private int missedCountToday = 0;
    private int totalCallsToday = 0;
    private int consecutiveErrors = 0;

    // Recent calls history (last N completed calls)
    private final LinkedList<JsonObject> recentCalls = new LinkedList<>();
    private final LinkedList<JsonObject> recentMissedCalls = new LinkedList<>();
    private int recentCallsMax = 10;
    private int recentMissedCallsMax = 10;

    // File-based persistence path
    private static final Path HISTORY_DIR = Path.of(OpenHAB.getUserDataFolder(), "pbx3cx");
    private static final Path HISTORY_FILE = HISTORY_DIR.resolve("call_history.json");

    public Pbx3cxServerHandler(Bridge bridge) {
        super(bridge);
    }

    @Override
    public void initialize() {
        logger.debug("Initializing 3CX PBX Server handler");
        Pbx3cxServerConfiguration config = getConfigAs(Pbx3cxServerConfiguration.class);

        if (config.hostname.isEmpty()) {
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.CONFIGURATION_ERROR, "Hostname is required");
            return;
        }
        if (config.username.isEmpty()) {
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.CONFIGURATION_ERROR, "Username is required");
            return;
        }
        if (config.password.isEmpty()) {
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.CONFIGURATION_ERROR, "Password is required");
            return;
        }

        updateStatus(ThingStatus.UNKNOWN, ThingStatusDetail.NONE, "Connecting to 3CX...");

        // Start connection in background
        scheduler.execute(this::connect);
    }

    private void connect() {
        Pbx3cxServerConfiguration config = getConfigAs(Pbx3cxServerConfiguration.class);

        Pbx3cxApiClient client = new Pbx3cxApiClient(config);
        this.apiClient = client;

        try {
            if (!client.authenticate()) {
                updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.CONFIGURATION_ERROR,
                        "Authentication failed — check username/password");
                return;
            }
        } catch (Exception e) {
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.COMMUNICATION_ERROR,
                    "Connection error: " + e.getMessage());
            // Retry in 30s
            scheduler.schedule(this::connect, 30, TimeUnit.SECONDS);
            return;
        }

        updateStatus(ThingStatus.ONLINE);
        updateState(CHANNEL_SYSTEM_STATUS, new StringType("ONLINE"));
        consecutiveErrors = 0;

        // Read history limits from config
        recentCallsMax = Math.max(1, config.recentCallsMax);
        recentMissedCallsMax = Math.max(1, config.recentMissedCallsMax);

        // Restore call history from disk
        loadCallHistory();

        // Initialize SIP client for MakeCall/AlarmCall
        BaresipSipClient sip = new BaresipSipClient(config);
        if (sip.isConfigured()) {
            this.sipClient = sip;
            logger.info("SIP client configured: ext {} -> {}", config.sipExtension, config.sipServer);
        } else {
            this.sipClient = null;
            logger.info("SIP client not configured — MakeCall/AlarmCall disabled");
        }

        // Start polling jobs
        int pollInterval = Math.max(1, config.pollInterval);
        int presenceInterval = Math.max(5, config.presenceInterval);
        int trunkInterval = Math.max(10, config.trunkInterval);

        pollJob = scheduler.scheduleWithFixedDelay(this::pollActiveCalls, 1, pollInterval, TimeUnit.SECONDS);
        presenceJob = scheduler.scheduleWithFixedDelay(this::pollPresence, 5, presenceInterval, TimeUnit.SECONDS);
        trunkJob = scheduler.scheduleWithFixedDelay(this::pollTrunks, 10, trunkInterval, TimeUnit.SECONDS);
        groupQueueJob = scheduler.scheduleWithFixedDelay(this::pollRingGroupsAndQueues, 15, 60, TimeUnit.SECONDS);

        // Schedule midnight missed-count reset
        scheduleMidnightReset();

        logger.info("3CX PBX connected: {}:{} (poll={}s, presence={}s, trunks={}s, recentCalls={}, recentMissed={})",
                config.hostname, config.port, pollInterval, presenceInterval, trunkInterval, recentCallsMax,
                recentMissedCallsMax);
    }

    @Override
    public void dispose() {
        cancelJob(pollJob);
        cancelJob(presenceJob);
        cancelJob(trunkJob);
        cancelJob(groupQueueJob);
        cancelJob(autoIdleJob);
        cancelJob(midnightResetJob);
        pollJob = null;
        presenceJob = null;
        trunkJob = null;
        groupQueueJob = null;
        autoIdleJob = null;
        midnightResetJob = null;

        Pbx3cxApiClient client = this.apiClient;
        if (client != null) {
            client.dispose();
        }
        apiClient = null;

        // Save call history to disk before clearing
        saveCallHistory();

        previousCalls.clear();
        callStartTimes.clear();
        extensionStates.clear();
        ringGroupStates.clear();
        queueStates.clear();
        recentCalls.clear();
        recentMissedCalls.clear();
        sipClient = null;
    }

    @Override
    public void handleCommand(ChannelUID channelUID, Command command) {
        if (command instanceof org.openhab.core.types.RefreshType) {
            return;
        }
        switch (channelUID.getId()) {
            case CHANNEL_MAKE_CALL:
                handleMakeCall(command);
                break;
            case CHANNEL_ALARM_CALL:
                handleAlarmCall(command);
                break;
            default:
                break;
        }
    }

    @Override
    public Collection<Class<? extends ThingHandlerService>> getServices() {
        return Set.of(Pbx3cxExtensionDiscoveryService.class);
    }

    // ─── Active Calls Polling ──────────────────────────────────────────────

    private void pollActiveCalls() {
        Pbx3cxApiClient client = this.apiClient;
        if (client == null) {
            return;
        }

        try {
            List<ActiveCall> activeCalls = client.getActiveCalls();
            consecutiveErrors = 0;
            processActiveCalls(activeCalls);
        } catch (Exception e) {
            consecutiveErrors++;
            if (consecutiveErrors <= 3) {
                logger.debug("3CX poll error ({}): {}", consecutiveErrors, e.getMessage());
            } else if (consecutiveErrors == 4) {
                logger.warn("3CX poll errors persisting: {}", e.getMessage());
                updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.COMMUNICATION_ERROR,
                        "Poll errors: " + e.getMessage());
                updateState(CHANNEL_SYSTEM_STATUS, new StringType("ERROR"));
            }
        }
    }

    private void processActiveCalls(List<ActiveCall> activeCalls) {
        Map<Integer, ActiveCall> currentIds = new HashMap<>();
        ZonedDateTime now = ZonedDateTime.now();

        for (ActiveCall call : activeCalls) {
            int callId = call.getId();
            currentIds.put(callId, call);

            CallerInfo caller = CallerInfo.parse(call.getCaller());
            CallerInfo callee = CallerInfo.parse(call.getCallee());
            String status = call.getStatus() != null ? call.getStatus() : "Unknown";
            String direction = CallerInfo.determineDirection(caller.getExtension(), callee.getExtension());

            ActiveCall prev = previousCalls.get(callId);

            if (prev == null) {
                // ── New call detected ──
                callStartTimes.put(callId, System.currentTimeMillis());

                String state;
                switch (status) {
                    case "Ringing":
                        state = STATE_RINGING;
                        break;
                    case "Talking":
                        state = STATE_ACTIVE;
                        break;
                    case "Routing":
                    case "Initiating":
                        state = "outbound".equals(direction) ? STATE_OUTGOING : STATE_RINGING;
                        break;
                    default:
                        state = STATE_ACTIVE;
                }

                logger.info("NEW CALL [{}]: {} ({}) → {} ({}) [{}]", direction, caller.getExtension(), caller.getName(),
                        callee.getExtension(), callee.getName(), status);

                // For inbound trunk calls, resolve the real external caller number
                // from CallHistoryView (ActiveCalls only shows DID, not caller).
                // IMPORTANT: Resolve BEFORE updating CHANNEL_CALL_STATE, because
                // rules trigger on state change and read CHANNEL_CALLER_NUMBER immediately.
                String callerNumber;
                String callerName;
                if (caller.isTrunk()) {
                    Pbx3cxApiClient client = this.apiClient;
                    String resolved = client != null ? client.resolveExternalCallerNumber(caller.getExtension()) : null;
                    callerNumber = resolved != null ? resolved : caller.getExternalNumber();
                    callerName = caller.getProviderName();
                    if (resolved != null) {
                        logger.info("CALLER ID RESOLVED: trunk {} → external caller {}", caller.getExtension(),
                                resolved);
                    }
                } else {
                    callerNumber = caller.getExtension();
                    callerName = caller.getName();
                }

                updateState(CHANNEL_CALLER_NUMBER, new StringType(callerNumber));
                updateState(CHANNEL_CALLER_NAME, new StringType(callerName));

                currentCallState = state;
                updateState(CHANNEL_CALL_STATE, new StringType(state));
                updateState(CHANNEL_CALLED_NUMBER,
                        new StringType(callee.isTrunk() ? callee.getExternalNumber() : callee.getExtension()));
                updateState(CHANNEL_CALL_DIRECTION, new StringType(direction));
                updateState(CHANNEL_CALL_AGENT,
                        new StringType("inbound".equals(direction) ? callee.getExtension() : caller.getExtension()));
                updateState(CHANNEL_CALL_TIMESTAMP, new DateTimeType(now));
                scheduleAutoIdle();

            } else if (!status.equals(prev.getStatus())
                    || !String.valueOf(call.getCallee()).equals(String.valueOf(prev.getCallee()))) {
                // ── Status or callee changed ──
                if (!status.equals(prev.getStatus())) {
                    logger.info("STATUS CHANGE [{}]: {} → {} ({} → {})", callId, prev.getStatus(), status,
                            caller.getExtension(), callee.getExtension());
                }

                String direction2 = CallerInfo.determineDirection(caller.getExtension(), callee.getExtension());

                if ("Talking".equals(status)) {
                    currentCallState = STATE_ACTIVE;
                    updateState(CHANNEL_CALL_STATE, new StringType(STATE_ACTIVE));
                } else if ("Ringing".equals(status)) {
                    currentCallState = STATE_RINGING;
                    updateState(CHANNEL_CALL_STATE, new StringType(STATE_RINGING));
                }

                // Update callee if resolved (from ROUTER to actual extension)
                CallerInfo prevCallee = CallerInfo.parse(prev.getCallee());
                if (!callee.getExtension().isEmpty() && !callee.getExtension().equals(prevCallee.getExtension())) {
                    updateState(CHANNEL_CALLED_NUMBER,
                            new StringType(callee.isTrunk() ? callee.getExternalNumber() : callee.getExtension()));
                    updateState(CHANNEL_CALL_DIRECTION, new StringType(direction2));
                    updateState(CHANNEL_CALL_AGENT, new StringType(
                            "inbound".equals(direction2) ? callee.getExtension() : caller.getExtension()));
                    logger.info("CALLEE RESOLVED [{}]: → {} ({}), direction={}", callId, callee.getExtension(),
                            callee.getName(), direction2);
                }
                scheduleAutoIdle();
            }
        }

        // ── Detect ended calls ──
        for (Map.Entry<Integer, ActiveCall> entry : previousCalls.entrySet()) {
            int callId = entry.getKey();
            if (!currentIds.containsKey(callId)) {
                ActiveCall prevCall = entry.getValue();
                CallerInfo caller = CallerInfo.parse(prevCall.getCaller());
                CallerInfo callee = CallerInfo.parse(prevCall.getCallee());
                String prevStatus = prevCall.getStatus() != null ? prevCall.getStatus() : "";

                // Resolve callee extension (handle VoiceMail and ROUTER patterns)
                String calleeExt = callee.isVoiceMail() ? callee.getVoiceMailExtension()
                        : callee.isRouter() ? caller.getExtension() : callee.getExtension();
                String direction = CallerInfo.determineDirection(caller.getExtension(), calleeExt);

                Long startTime = callStartTimes.remove(callId);
                int duration = startTime != null ? (int) ((System.currentTimeMillis() - startTime) / 1000) : 0;
                // VoiceMail pickup is not a real answer — 3CX sets status to Talking when VM picks up
                boolean wasAnswered = "Talking".equals(prevStatus) && !callee.isVoiceMail() && !callee.isRouter();

                // Resolve display names for caller and callee
                String callerNum;
                if (caller.isTrunk()) {
                    Pbx3cxApiClient client = this.apiClient;
                    String resolved = client != null ? client.resolveExternalCallerNumber(caller.getExtension()) : null;
                    callerNum = resolved != null ? resolved : caller.getExternalNumber();
                } else {
                    callerNum = caller.getExtension();
                }
                String callerNm = caller.isTrunk() ? caller.getProviderName() : caller.getName();
                String calledNum;
                String calledNm;
                if (callee.isRouter()) {
                    calledNum = "";
                    calledNm = "Routing...";
                } else if (callee.isVoiceMail()) {
                    calledNum = callee.getVoiceMailExtension();
                    calledNm = callee.getVoiceMailName();
                } else if (callee.isTrunk()) {
                    calledNum = callee.getExternalNumber();
                    calledNm = callee.getProviderName();
                } else {
                    calledNum = callee.getExtension();
                    calledNm = callee.getName();
                }

                logger.info("CALL ENDED [{}]: {} ({}) → {} ({}), duration={}s, answered={}", direction, callerNum,
                        callerNm, calledNum, calledNm, duration, wasAnswered);

                cancelAutoIdle();

                currentCallState = STATE_IDLE;
                updateState(CHANNEL_CALL_STATE, new StringType(STATE_IDLE));
                updateState(CHANNEL_CALL_DURATION, new DecimalType(duration));
                updateState(CHANNEL_CALL_TIMESTAMP, new DateTimeType(ZonedDateTime.now()));

                boolean isMissed = !wasAnswered
                        && ("inbound".equals(direction) || "internal".equals(direction) || callee.isRouter());

                if (isMissed) {
                    missedCountToday++;
                    updateState(CHANNEL_MISSED_COUNT, new DecimalType(missedCountToday));
                    String missedCaller = callerNm.isEmpty() ? callerNum : callerNum + " " + callerNm;
                    updateState(CHANNEL_LAST_MISSED_CALLER, new StringType(missedCaller));
                    updateState(CHANNEL_LAST_MISSED_TIME, new DateTimeType(ZonedDateTime.now()));

                    // Add to recent missed calls history
                    JsonObject missed = new JsonObject();
                    missed.addProperty("callerNumber", callerNum);
                    missed.addProperty("callerName", callerNm);
                    missed.addProperty("calledNumber", calledNum);
                    missed.addProperty("calledName", calledNm);
                    missed.addProperty("direction", direction);
                    missed.addProperty("endTime", ZonedDateTime.now().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME));
                    recentMissedCalls.addFirst(missed);
                    while (recentMissedCalls.size() > recentMissedCallsMax) {
                        recentMissedCalls.removeLast();
                    }
                }

                // Increment total calls counter
                totalCallsToday++;
                updateState(CHANNEL_TOTAL_CALLS, new DecimalType(totalCallsToday));

                // Add to recent calls history (missed calls go only to the missed list)
                if (!isMissed) {
                    JsonObject recent = new JsonObject();
                    recent.addProperty("callerNumber", callerNum);
                    recent.addProperty("callerName", callerNm);
                    recent.addProperty("calledNumber", calledNum);
                    recent.addProperty("calledName", calledNm);
                    recent.addProperty("direction", direction);
                    recent.addProperty("duration", duration);
                    recent.addProperty("answered", wasAnswered);
                    recent.addProperty("endTime", ZonedDateTime.now().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME));
                    recentCalls.addFirst(recent);
                    while (recentCalls.size() > recentCallsMax) {
                        recentCalls.removeLast();
                    }
                }

                // Persist after every ended call
                saveCallHistory();
            }
        }

        // Update active call count
        updateState(CHANNEL_ACTIVE_CALLS, new DecimalType(activeCalls.size()));

        // ── Build and publish active calls JSON ──
        JsonArray activeJson = new JsonArray();
        for (ActiveCall call : activeCalls) {
            CallerInfo caller = CallerInfo.parse(call.getCaller());
            CallerInfo callee = CallerInfo.parse(call.getCallee());
            String calleeExt = callee.isVoiceMail() ? callee.getVoiceMailExtension()
                    : callee.isRouter() ? caller.getExtension() : callee.getExtension();
            String direction = CallerInfo.determineDirection(caller.getExtension(), calleeExt);
            Long startTime = callStartTimes.get(call.getId());
            int dur = startTime != null ? (int) ((System.currentTimeMillis() - startTime) / 1000) : 0;

            // Resolve callee display: handle ROUTER, VoiceMail, trunk, or normal
            String calledNum;
            String calledNm;
            if (callee.isRouter()) {
                calledNum = "";
                calledNm = "Routing...";
            } else if (callee.isVoiceMail()) {
                calledNum = callee.getVoiceMailExtension();
                calledNm = callee.getVoiceMailName();
            } else if (callee.isTrunk()) {
                calledNum = callee.getExternalNumber();
                calledNm = callee.getProviderName();
            } else {
                calledNum = callee.getExtension();
                calledNm = callee.getName();
            }

            JsonObject obj = new JsonObject();
            obj.addProperty("id", call.getId());
            // Resolve real external caller number for trunk calls
            String jsonCallerNumber;
            if (caller.isTrunk()) {
                Pbx3cxApiClient client = this.apiClient;
                String resolved = client != null ? client.resolveExternalCallerNumber(caller.getExtension()) : null;
                jsonCallerNumber = resolved != null ? resolved : caller.getExternalNumber();
            } else {
                jsonCallerNumber = caller.getExtension();
            }
            obj.addProperty("callerNumber", jsonCallerNumber);
            obj.addProperty("callerName", caller.isTrunk() ? caller.getProviderName() : caller.getName());
            obj.addProperty("calledNumber", calledNum);
            obj.addProperty("calledName", calledNm);
            obj.addProperty("status", call.getStatus() != null ? call.getStatus() : "Unknown");
            obj.addProperty("direction", direction);
            obj.addProperty("duration", dur);
            obj.addProperty("agent", "inbound".equals(direction) ? calleeExt : caller.getExtension());
            activeJson.add(obj);
        }
        updateState(CHANNEL_ACTIVE_CALLS_JSON, new StringType(activeJson.toString()));

        // ── Publish recent calls JSON ──
        JsonArray recentJson = new JsonArray();
        for (JsonObject r : recentCalls) {
            recentJson.add(r);
        }
        updateState(CHANNEL_RECENT_CALLS_JSON, new StringType(recentJson.toString()));

        // ── Publish recent missed calls JSON ──
        JsonArray missedJson = new JsonArray();
        for (JsonObject m : recentMissedCalls) {
            missedJson.add(m);
        }
        updateState(CHANNEL_RECENT_MISSED_CALLS_JSON, new StringType(missedJson.toString()));

        // Save snapshot
        previousCalls = currentIds;

        // Re-mark ONLINE if we recovered from errors
        if (getThing().getStatus() != ThingStatus.ONLINE && consecutiveErrors == 0) {
            updateStatus(ThingStatus.ONLINE);
            updateState(CHANNEL_SYSTEM_STATUS, new StringType("ONLINE"));
        }
    }

    // ─── Call History Persistence ──────────────────────────────────────────

    private void saveCallHistory() {
        try {
            Files.createDirectories(HISTORY_DIR);
            JsonObject root = new JsonObject();
            JsonArray recent = new JsonArray();
            for (JsonObject r : recentCalls) {
                recent.add(r);
            }
            JsonArray missed = new JsonArray();
            for (JsonObject m : recentMissedCalls) {
                missed.add(m);
            }
            root.add("recentCalls", recent);
            root.add("recentMissedCalls", missed);
            root.addProperty("missedCountToday", missedCountToday);
            root.addProperty("totalCallsToday", totalCallsToday);
            root.addProperty("savedAt", ZonedDateTime.now().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME));
            Files.writeString(HISTORY_FILE, root.toString(), StandardCharsets.UTF_8);
            logger.debug("Saved call history ({} recent, {} missed)", recentCalls.size(), recentMissedCalls.size());
        } catch (IOException e) {
            logger.warn("Failed to save call history: {}", e.getMessage());
        }
    }

    private void loadCallHistory() {
        if (!Files.exists(HISTORY_FILE)) {
            logger.debug("No call history file found, starting fresh");
            // Always publish 0 to overwrite stale item state from before restart
            updateState(CHANNEL_MISSED_COUNT, new DecimalType(missedCountToday));
            updateState(CHANNEL_TOTAL_CALLS, new DecimalType(totalCallsToday));
            return;
        }
        try {
            String json = Files.readString(HISTORY_FILE, StandardCharsets.UTF_8);
            JsonObject root = JsonParser.parseString(json).getAsJsonObject();

            if (root.has("recentCalls")) {
                recentCalls.clear();
                for (JsonElement el : root.getAsJsonArray("recentCalls")) {
                    recentCalls.addLast(el.getAsJsonObject());
                }
                while (recentCalls.size() > recentCallsMax) {
                    recentCalls.removeLast();
                }
            }
            if (root.has("recentMissedCalls")) {
                recentMissedCalls.clear();
                for (JsonElement el : root.getAsJsonArray("recentMissedCalls")) {
                    recentMissedCalls.addLast(el.getAsJsonObject());
                }
                while (recentMissedCalls.size() > recentMissedCallsMax) {
                    recentMissedCalls.removeLast();
                }
            }
            if (root.has("missedCountToday")) {
                missedCountToday = root.get("missedCountToday").getAsInt();
            }
            if (root.has("totalCallsToday")) {
                totalCallsToday = root.get("totalCallsToday").getAsInt();
            }
            // Always publish loaded values to channels immediately
            updateState(CHANNEL_MISSED_COUNT, new DecimalType(missedCountToday));
            updateState(CHANNEL_TOTAL_CALLS, new DecimalType(totalCallsToday));
            JsonArray recentJson = new JsonArray();
            for (JsonObject r : recentCalls) {
                recentJson.add(r);
            }
            updateState(CHANNEL_RECENT_CALLS_JSON, new StringType(recentJson.toString()));
            JsonArray missedJson = new JsonArray();
            for (JsonObject m : recentMissedCalls) {
                missedJson.add(m);
            }
            updateState(CHANNEL_RECENT_MISSED_CALLS_JSON, new StringType(missedJson.toString()));

            logger.info("Restored call history ({} recent, {} missed, {} missed today)", recentCalls.size(),
                    recentMissedCalls.size(), missedCountToday);
        } catch (Exception e) {
            logger.warn("Failed to load call history: {}", e.getMessage());
        }
    }

    // ─── Extension Presence Polling ────────────────────────────────────────

    private void pollPresence() {
        Pbx3cxApiClient client = this.apiClient;
        if (client == null) {
            return;
        }

        try {
            List<Extension> users = client.getUsers();

            // Build set of extensions currently on calls
            Set<String> onCallExts = new HashSet<>();
            for (ActiveCall call : previousCalls.values()) {
                CallerInfo caller = CallerInfo.parse(call.getCaller());
                CallerInfo callee = CallerInfo.parse(call.getCallee());
                if (!caller.getExtension().isEmpty()) {
                    onCallExts.add(caller.getExtension());
                }
                if (!callee.getExtension().isEmpty()) {
                    onCallExts.add(callee.getExtension());
                }
            }

            for (Extension user : users) {
                String extNum = user.getNumber();
                if (extNum == null || extNum.isEmpty()) {
                    continue;
                }

                boolean onCall = onCallExts.contains(extNum);
                String profile = user.getCurrentProfileName();

                String extStatus;
                if (onCall) {
                    extStatus = EXT_ONCALL;
                } else if (!user.isRegistered()) {
                    extStatus = EXT_OFFLINE;
                } else if ("Away".equals(profile)) {
                    extStatus = EXT_AWAY;
                } else if ("DND".equals(profile)) {
                    extStatus = EXT_DND;
                } else {
                    extStatus = EXT_AVAILABLE;
                }

                ExtensionState newState = new ExtensionState(extNum, user.getDisplayName(), extStatus,
                        profile != null ? profile : "", user.isRegistered());

                ExtensionState oldState = extensionStates.put(extNum, newState);

                // Notify child extension handlers if status changed
                if (oldState == null || !oldState.status.equals(newState.status)) {
                    if (oldState != null) {
                        logger.info("Extension {} ({}): {} → {}", extNum, newState.name, oldState.status,
                                newState.status);
                    }
                    notifyExtensionHandlers(extNum, newState);
                }
            }
        } catch (Exception e) {
            logger.debug("Presence poll error: {}", e.getMessage());
        }
    }

    /**
     * Notify child extension thing handlers of a state change.
     */
    private void notifyExtensionHandlers(String extensionNumber, ExtensionState state) {
        for (Thing childThing : getThing().getThings()) {
            if (childThing.getHandler() instanceof Pbx3cxExtensionHandler handler) {
                String childExt = (String) childThing.getConfiguration().get("extensionNumber");
                if (extensionNumber.equals(childExt)) {
                    handler.updateExtensionState(state);
                }
            }
        }
    }

    @Override
    public void childHandlerInitialized(ThingHandler childHandler, Thing childThing) {
        if (childHandler instanceof Pbx3cxExtensionHandler handler) {
            String childExt = (String) childThing.getConfiguration().get("extensionNumber");
            if (childExt != null) {
                ExtensionState cached = extensionStates.get(childExt);
                if (cached != null) {
                    logger.info("Pushing cached state to new extension {}: {}", childExt, cached.status);
                    scheduler.schedule(() -> handler.updateExtensionState(cached), 2,
                            java.util.concurrent.TimeUnit.SECONDS);
                }
            }
        } else if (childHandler instanceof Pbx3cxRingGroupHandler handler) {
            String rgNum = (String) childThing.getConfiguration().get("ringGroupNumber");
            if (rgNum != null) {
                RingGroupState cached = ringGroupStates.get(rgNum);
                if (cached != null) {
                    logger.info("Pushing cached state to new ring group {}: {}", rgNum, cached.name);
                    scheduler.schedule(() -> handler.updateRingGroupState(cached), 2,
                            java.util.concurrent.TimeUnit.SECONDS);
                }
            }
        } else if (childHandler instanceof Pbx3cxQueueHandler handler) {
            String qNum = (String) childThing.getConfiguration().get("queueNumber");
            if (qNum != null) {
                QueueState cached = queueStates.get(qNum);
                if (cached != null) {
                    logger.info("Pushing cached state to new queue {}: {}", qNum, cached.name);
                    scheduler.schedule(() -> handler.updateQueueState(cached), 2,
                            java.util.concurrent.TimeUnit.SECONDS);
                }
            }
        }
    }

    // ─── Trunk Polling ─────────────────────────────────────────────────────

    private void pollTrunks() {
        Pbx3cxApiClient client = this.apiClient;
        if (client == null) {
            return;
        }

        try {
            String json = client.getTrunks();
            if (json == null) {
                return;
            }

            JsonObject root = JsonParser.parseString(json).getAsJsonObject();
            JsonArray trunks = root.getAsJsonArray("value");
            if (trunks == null) {
                return;
            }

            int registered = 0;
            int total = trunks.size();
            for (JsonElement elem : trunks) {
                JsonObject t = elem.getAsJsonObject();
                if (t.has("IsOnline") && t.get("IsOnline").getAsBoolean()) {
                    registered++;
                }
            }

            String summary = String.format("%d/%d online", registered, total);
            updateState(CHANNEL_TRUNK_STATUS, new StringType(summary));
            logger.debug("Trunks updated: {}", summary);
        } catch (Exception e) {
            logger.debug("Trunk poll error: {}", e.getMessage());
        }
    }

    // ─── Ring Group & Queue Polling ────────────────────────────────────────

    private void pollRingGroupsAndQueues() {
        Pbx3cxApiClient client = this.apiClient;
        if (client == null) {
            return;
        }

        try {
            // Poll Ring Groups
            List<RingGroup> ringGroups = client.getRingGroups();
            for (RingGroup rg : ringGroups) {
                String rgNum = rg.getNumber();
                if (rgNum == null || rgNum.isEmpty()) {
                    continue;
                }

                // Fetch members for this ring group
                List<RingGroup.Member> members = client.getRingGroupMembers(rg.getId());
                rg.setMembers(members);

                StringBuilder memberStr = new StringBuilder();
                for (RingGroup.Member m : members) {
                    if (memberStr.length() > 0) {
                        memberStr.append(", ");
                    }
                    String mNum = m.getNumber() != null ? m.getNumber() : "";
                    String mName = m.getName() != null ? m.getName() : "";
                    memberStr.append(mNum);
                    if (!mName.isEmpty()) {
                        memberStr.append(" ").append(mName);
                    }
                }

                String rgNameRaw = rg.getName();
                String rgName = rgNameRaw != null ? rgNameRaw : rgNum;
                String strategyRaw = rg.getRingStrategy();
                String strategy = strategyRaw != null ? strategyRaw : "Unknown";

                RingGroupState newState = new RingGroupState(rgNum, rgName, strategy, memberStr.toString(),
                        members.size(), rg.isRegistered());

                ringGroupStates.put(rgNum, newState);
                notifyRingGroupHandlers(rgNum, newState);
            }

            // Poll Queues
            List<CallQueue> queues = client.getQueues();
            for (CallQueue q : queues) {
                String qNum = q.getNumber();
                if (qNum == null || qNum.isEmpty()) {
                    continue;
                }

                // Fetch agents for this queue
                List<CallQueue.Agent> agents = client.getQueueAgents(q.getId());
                q.setAgents(agents);

                StringBuilder agentStr = new StringBuilder();
                for (CallQueue.Agent a : agents) {
                    if (agentStr.length() > 0) {
                        agentStr.append(", ");
                    }
                    String aNum = a.getNumber() != null ? a.getNumber() : "";
                    String aName = a.getName() != null ? a.getName() : "";
                    agentStr.append(aNum);
                    if (!aName.isEmpty()) {
                        agentStr.append(" ").append(aName);
                    }
                }

                // Count waiting callers for this queue from active calls
                int waitingCallers = 0;
                for (ActiveCall call : previousCalls.values()) {
                    CallerInfo callee = CallerInfo.parse(call.getCallee());
                    if (qNum.equals(callee.getExtension()) && "Ringing".equals(call.getStatus())) {
                        waitingCallers++;
                    }
                }

                String qNameRaw = q.getName();
                String qName = qNameRaw != null ? qNameRaw : qNum;
                String strategyRaw = q.getPollingStrategy();
                String strategy = strategyRaw != null ? strategyRaw : "Unknown";

                QueueState newState = new QueueState(qNum, qName, strategy, agentStr.toString(), agents.size(),
                        waitingCallers, q.isRegistered());

                queueStates.put(qNum, newState);
                notifyQueueHandlers(qNum, newState);
            }

            logger.debug("Ring groups/queues polled: {} groups, {} queues", ringGroups.size(), queues.size());
        } catch (Exception e) {
            logger.debug("Ring group/queue poll error: {}", e.getMessage());
        }
    }

    private void notifyRingGroupHandlers(String ringGroupNumber, RingGroupState state) {
        for (Thing childThing : getThing().getThings()) {
            if (childThing.getHandler() instanceof Pbx3cxRingGroupHandler handler) {
                String childRg = (String) childThing.getConfiguration().get("ringGroupNumber");
                if (ringGroupNumber.equals(childRg)) {
                    handler.updateRingGroupState(state);
                }
            }
        }
    }

    private void notifyQueueHandlers(String queueNumber, QueueState state) {
        for (Thing childThing : getThing().getThings()) {
            if (childThing.getHandler() instanceof Pbx3cxQueueHandler handler) {
                String childQ = (String) childThing.getConfiguration().get("queueNumber");
                if (queueNumber.equals(childQ)) {
                    handler.updateQueueState(state);
                }
            }
        }
    }

    // ─── MakeCall (plain SIP call via baresip) ─────────────────────────────

    private void handleMakeCall(Command command) {
        String destination = command.toString().trim();
        if (destination.isEmpty()) {
            logger.warn("MakeCall: empty destination");
            return;
        }

        BaresipSipClient client = this.sipClient;
        if (client == null) {
            logger.warn("MakeCall: SIP not configured — set sipServer/sipExtension/sipAuthUser/sipAuthPass");
            return;
        }

        logger.info("MakeCall: dialing {}", destination);

        scheduler.execute(() -> {
            String result = client.makePlainCall(destination);
            logger.info("MakeCall {}: {}", destination, result);
        });
    }

    // ─── AlarmCall (SIP call with audio + DTMF confirmation) ──────────────

    private void handleAlarmCall(Command command) {
        String cmd = command.toString().trim();
        if (cmd.isEmpty()) {
            logger.warn("AlarmCall: empty command");
            return;
        }

        // Format: "alertType" or "alertType,destination"
        String[] parts = cmd.split(",", 2);
        String alertType = parts[0].trim();
        String destination = parts.length > 1 ? parts[1].trim() : null;

        if (alertType.isEmpty()) {
            logger.warn("AlarmCall: alert type is required (e.g. towing, unplug, generic)");
            return;
        }

        BaresipSipClient client = this.sipClient;
        if (client == null) {
            logger.warn("AlarmCall: SIP not configured — set sipServer/sipExtension/sipAuthUser/sipAuthPass");
            updateState(CHANNEL_ALARM_CALL_RESULT, new StringType("ERROR"));
            return;
        }

        logger.info("AlarmCall: type={}, destination={}", alertType, destination != null ? destination : "(config)");
        updateState(CHANNEL_ALARM_CALL_RESULT, new StringType("IN_PROGRESS"));

        scheduler.execute(() -> {
            String result = client.makeAlarmCall(alertType, destination);
            logger.info("AlarmCall {}: {}", alertType, result);
            updateState(CHANNEL_ALARM_CALL_RESULT, new StringType(result));
        });
    }

    // ─── Auto-Idle Timer ───────────────────────────────────────────────────

    private void scheduleAutoIdle() {
        cancelAutoIdle();
        autoIdleJob = scheduler.schedule(() -> {
            if (!STATE_IDLE.equals(currentCallState)) {
                logger.info("Auto-idle: resetting from '{}' after {}s timeout", currentCallState,
                        AUTO_IDLE_TIMEOUT_SECONDS);
                currentCallState = STATE_IDLE;
                updateState(CHANNEL_CALL_STATE, new StringType(STATE_IDLE));
            }
        }, AUTO_IDLE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
    }

    private void cancelAutoIdle() {
        cancelJob(autoIdleJob);
        autoIdleJob = null;
    }

    // ─── Midnight Reset ────────────────────────────────────────────────────

    private void scheduleMidnightReset() {
        // Calculate seconds until next midnight
        ZonedDateTime now = ZonedDateTime.now();
        ZonedDateTime nextMidnight = now.toLocalDate().plusDays(1).atStartOfDay(now.getZone());
        long secondsUntilMidnight = java.time.Duration.between(now, nextMidnight).getSeconds();

        midnightResetJob = scheduler.scheduleAtFixedRate(() -> {
            missedCountToday = 0;
            totalCallsToday = 0;
            updateState(CHANNEL_MISSED_COUNT, new DecimalType(0));
            updateState(CHANNEL_TOTAL_CALLS, new DecimalType(0));
            logger.info("Call counters reset at midnight");
        }, secondsUntilMidnight, 86400, TimeUnit.SECONDS);
    }

    // ─── Webhook Integration (called by servlet) ───────────────────────────

    /**
     * Handle an incoming webhook event (from CRM/CFD integration).
     * Called by {@link Pbx3cxWebhookServlet}.
     */
    public void handleWebhookEvent(String event, Map<String, String> data) {
        ZonedDateTime now = ZonedDateTime.now();

        switch (event) {
            case "ringing":
            case "lookup": {
                String callerNumber = getFirst(data, "caller_number", "Number", "ani");
                String callerName = getFirst(data, "caller_name", "Name", "ani_name");
                String calledNumber = getFirst(data, "called_number", "CalledNumber", "did");
                String agent = getFirst(data, "agent", "Agent", "extension");

                logger.info("WEBHOOK RINGING: {} ({}) → {} [agent: {}]", callerNumber, callerName, calledNumber, agent);

                currentCallState = STATE_RINGING;
                updateState(CHANNEL_CALL_STATE, new StringType(STATE_RINGING));
                updateState(CHANNEL_CALLER_NUMBER, new StringType(callerNumber));
                updateState(CHANNEL_CALLER_NAME, new StringType(callerName));
                updateState(CHANNEL_CALLED_NUMBER, new StringType(calledNumber));
                updateState(CHANNEL_CALL_DIRECTION, new StringType("inbound"));
                updateState(CHANNEL_CALL_AGENT, new StringType(agent));
                updateState(CHANNEL_CALL_TIMESTAMP, new DateTimeType(now));
                scheduleAutoIdle();
                break;
            }

            case "answered": {
                String callerNumber = getFirst(data, "caller_number", "CallerNumber");
                String agent = getFirst(data, "agent", "Agent");

                logger.info("WEBHOOK ANSWERED: {} [agent: {}]", callerNumber, agent);

                currentCallState = STATE_ACTIVE;
                updateState(CHANNEL_CALL_STATE, new StringType(STATE_ACTIVE));
                if (!callerNumber.isEmpty()) {
                    updateState(CHANNEL_CALLER_NUMBER, new StringType(callerNumber));
                }
                if (!agent.isEmpty()) {
                    updateState(CHANNEL_CALL_AGENT, new StringType(agent));
                }
                updateState(CHANNEL_CALL_TIMESTAMP, new DateTimeType(now));
                scheduleAutoIdle();
                break;
            }

            case "hangup": {
                String duration = getFirst(data, "duration", "Duration");
                String wasAnswered = getFirst(data, "was_answered", "WasAnswered");
                String callerNumber = getFirst(data, "caller_number", "CallerNumber");

                logger.info("WEBHOOK HANGUP: duration={}, answered={}", duration, wasAnswered);

                cancelAutoIdle();
                currentCallState = STATE_IDLE;
                updateState(CHANNEL_CALL_STATE, new StringType(STATE_IDLE));

                int dur = 0;
                try {
                    dur = Integer.parseInt(duration);
                } catch (NumberFormatException ignored) {
                }
                updateState(CHANNEL_CALL_DURATION, new DecimalType(dur));
                updateState(CHANNEL_CALL_TIMESTAMP, new DateTimeType(now));

                if ("false".equalsIgnoreCase(wasAnswered) || "0".equals(wasAnswered)) {
                    missedCountToday++;
                    updateState(CHANNEL_MISSED_COUNT, new DecimalType(missedCountToday));
                    updateState(CHANNEL_LAST_MISSED_CALLER, new StringType(callerNumber));
                    updateState(CHANNEL_LAST_MISSED_TIME, new DateTimeType(now));
                }
                break;
            }

            case "callinfo": {
                String callType = getFirst(data, "CallType", "call_type");
                String callerNumber = getFirst(data, "Number", "CallerNumber", "caller_number");
                String callerName = getFirst(data, "Name", "CallerName", "caller_name");
                String duration = getFirst(data, "Duration", "call_duration");
                String agent = getFirst(data, "Agent", "agent");
                String recordingUrl = getFirst(data, "RecordingUrl", "recording_url");
                String wasAnswered = getFirst(data, "WasAnswered", "was_answered", "Answered");

                boolean isMissed = "false".equalsIgnoreCase(wasAnswered) || "0".equals(wasAnswered)
                        || "missed".equalsIgnoreCase(callType) || "notanswered".equalsIgnoreCase(callType);

                logger.info("WEBHOOK CALLINFO: {} ({}), type={}, missed={}", callerNumber, callerName, callType,
                        isMissed);

                cancelAutoIdle();
                currentCallState = STATE_IDLE;
                updateState(CHANNEL_CALL_STATE, new StringType(STATE_IDLE));
                updateState(CHANNEL_CALLER_NUMBER, new StringType(callerNumber));
                updateState(CHANNEL_CALLER_NAME, new StringType(callerName));

                int dur = 0;
                try {
                    dur = Integer.parseInt(duration);
                } catch (NumberFormatException ignored) {
                }
                updateState(CHANNEL_CALL_DURATION, new DecimalType(dur));
                updateState(CHANNEL_CALL_AGENT, new StringType(agent));
                updateState(CHANNEL_CALL_TIMESTAMP, new DateTimeType(now));

                if (!recordingUrl.isEmpty()) {
                    updateState(CHANNEL_RECORDING_URL, new StringType(recordingUrl));
                }

                if (isMissed) {
                    missedCountToday++;
                    updateState(CHANNEL_MISSED_COUNT, new DecimalType(missedCountToday));
                    updateState(CHANNEL_LAST_MISSED_CALLER, new StringType(callerNumber));
                    updateState(CHANNEL_LAST_MISSED_TIME, new DateTimeType(now));
                }
                break;
            }

            case "outgoing": {
                String callerNumber = getFirst(data, "caller_number", "CallerNumber");
                String calledNumber = getFirst(data, "called_number", "CalledNumber");
                String agent = getFirst(data, "agent", "Agent");

                logger.info("WEBHOOK OUTGOING: {} → {}", agent, calledNumber);

                currentCallState = STATE_OUTGOING;
                updateState(CHANNEL_CALL_STATE, new StringType(STATE_OUTGOING));
                updateState(CHANNEL_CALLER_NUMBER, new StringType(callerNumber));
                updateState(CHANNEL_CALLED_NUMBER, new StringType(calledNumber));
                updateState(CHANNEL_CALL_DIRECTION, new StringType("outbound"));
                updateState(CHANNEL_CALL_AGENT, new StringType(agent));
                updateState(CHANNEL_CALL_TIMESTAMP, new DateTimeType(now));
                scheduleAutoIdle();
                break;
            }

            case "reset_missed": {
                missedCountToday = 0;
                updateState(CHANNEL_MISSED_COUNT, new DecimalType(0));
                logger.info("Missed call counter reset via webhook");
                break;
            }

            default:
                logger.debug("Unknown webhook event: {}", event);
        }
    }

    // ─── Public accessors ──────────────────────────────────────────────────

    public @Nullable Pbx3cxApiClient getApiClient() {
        return apiClient;
    }

    /**
     * Get cached extension states (used by discovery service).
     */
    public Map<String, ExtensionState> getExtensionStates() {
        return new HashMap<>(extensionStates);
    }

    /**
     * Get cached ring group states (used by discovery service).
     */
    public Map<String, RingGroupState> getRingGroupStates() {
        return new HashMap<>(ringGroupStates);
    }

    /**
     * Get cached queue states (used by discovery service).
     */
    public Map<String, QueueState> getQueueStates() {
        return new HashMap<>(queueStates);
    }

    // ─── Helpers ───────────────────────────────────────────────────────────

    private void cancelJob(@Nullable ScheduledFuture<?> job) {
        if (job != null && !job.isCancelled()) {
            job.cancel(false);
        }
    }

    private static String getFirst(Map<String, String> data, String... keys) {
        for (String key : keys) {
            for (Map.Entry<String, String> entry : data.entrySet()) {
                if (entry.getKey().equalsIgnoreCase(key)) {
                    String val = entry.getValue();
                    if (val != null && !val.isEmpty()) {
                        return val;
                    }
                }
            }
        }
        return "";
    }

    /**
     * Cached extension state, shared with child handlers and discovery.
     */
    public static class ExtensionState {
        public final String number;
        public final String name;
        public final String status;
        public final String profile;
        public final boolean registered;

        public ExtensionState(String number, String name, String status, String profile, boolean registered) {
            this.number = number;
            this.name = name;
            this.status = status;
            this.profile = profile;
            this.registered = registered;
        }
    }

    /**
     * Cached ring group state, shared with child handlers and discovery.
     */
    public static class RingGroupState {
        public final String number;
        public final String name;
        public final String strategy;
        public final String members;
        public final int memberCount;
        public final boolean registered;

        public RingGroupState(String number, String name, String strategy, String members, int memberCount,
                boolean registered) {
            this.number = number;
            this.name = name;
            this.strategy = strategy;
            this.members = members;
            this.memberCount = memberCount;
            this.registered = registered;
        }
    }

    /**
     * Cached queue state, shared with child handlers and discovery.
     */
    public static class QueueState {
        public final String number;
        public final String name;
        public final String strategy;
        public final String agents;
        public final int agentCount;
        public final int waitingCallers;
        public final boolean registered;

        public QueueState(String number, String name, String strategy, String agents, int agentCount,
                int waitingCallers, boolean registered) {
            this.number = number;
            this.name = name;
            this.strategy = strategy;
            this.agents = agents;
            this.agentCount = agentCount;
            this.waitingCallers = waitingCallers;
            this.registered = registered;
        }
    }
}
