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

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.openhab.core.thing.ThingTypeUID;

/**
 * The {@link Pbx3cxBindingConstants} class defines common constants used across the binding.
 *
 * @author openHAB Community - Initial contribution
 */
@NonNullByDefault
public class Pbx3cxBindingConstants {

    public static final String BINDING_ID = "pbx3cx";

    // Thing Type UIDs
    public static final ThingTypeUID THING_TYPE_SERVER = new ThingTypeUID(BINDING_ID, "server");
    public static final ThingTypeUID THING_TYPE_EXTENSION = new ThingTypeUID(BINDING_ID, "extension");
    public static final ThingTypeUID THING_TYPE_RINGGROUP = new ThingTypeUID(BINDING_ID, "ringgroup");
    public static final ThingTypeUID THING_TYPE_QUEUE = new ThingTypeUID(BINDING_ID, "queue");

    // ─── Bridge (server) channels ──────────────────────────────────────────
    public static final String CHANNEL_CALL_STATE = "callState";
    public static final String CHANNEL_CALLER_NUMBER = "callerNumber";
    public static final String CHANNEL_CALLER_NAME = "callerName";
    public static final String CHANNEL_CALLED_NUMBER = "calledNumber";
    public static final String CHANNEL_CALL_DIRECTION = "callDirection";
    public static final String CHANNEL_CALL_DURATION = "callDuration";
    public static final String CHANNEL_CALL_AGENT = "callAgent";
    public static final String CHANNEL_CALL_TIMESTAMP = "callTimestamp";

    public static final String CHANNEL_MISSED_COUNT = "missedCount";
    public static final String CHANNEL_TOTAL_CALLS = "totalCalls";
    public static final String CHANNEL_LAST_MISSED_CALLER = "lastMissedCaller";
    public static final String CHANNEL_LAST_MISSED_TIME = "lastMissedTime";

    public static final String CHANNEL_ACTIVE_CALLS = "activeCalls";
    public static final String CHANNEL_ACTIVE_CALLS_JSON = "activeCallsJson";
    public static final String CHANNEL_RECENT_CALLS_JSON = "recentCallsJson";
    public static final String CHANNEL_RECENT_MISSED_CALLS_JSON = "recentMissedCallsJson";
    public static final String CHANNEL_TRUNK_STATUS = "trunkStatus";
    public static final String CHANNEL_RECORDING_URL = "recordingUrl";
    public static final String CHANNEL_SYSTEM_STATUS = "systemStatus";
    public static final String CHANNEL_MAKE_CALL = "makeCall";
    public static final String CHANNEL_ALARM_CALL = "alarmCall";
    public static final String CHANNEL_ALARM_CALL_RESULT = "alarmCallResult";

    // ─── Extension channels ────────────────────────────────────────────────
    public static final String CHANNEL_EXT_STATUS = "status";
    public static final String CHANNEL_EXT_PRESENCE = "presence";
    public static final String CHANNEL_EXT_NAME = "name";
    public static final String CHANNEL_EXT_REGISTERED = "registered";

    // ─── Ring Group channels ───────────────────────────────────────────────
    public static final String CHANNEL_RG_NAME = "name";
    public static final String CHANNEL_RG_STRATEGY = "strategy";
    public static final String CHANNEL_RG_MEMBERS = "members";
    public static final String CHANNEL_RG_MEMBER_COUNT = "memberCount";
    public static final String CHANNEL_RG_REGISTERED = "registered";

    // ─── Queue channels ────────────────────────────────────────────────────
    public static final String CHANNEL_Q_NAME = "name";
    public static final String CHANNEL_Q_STRATEGY = "strategy";
    public static final String CHANNEL_Q_AGENTS = "agents";
    public static final String CHANNEL_Q_AGENT_COUNT = "agentCount";
    public static final String CHANNEL_Q_WAITING_CALLERS = "waitingCallers";
    public static final String CHANNEL_Q_REGISTERED = "registered";

    // ─── Call states ───────────────────────────────────────────────────────
    public static final String STATE_IDLE = "idle";
    public static final String STATE_RINGING = "ringing";
    public static final String STATE_ACTIVE = "active";
    public static final String STATE_OUTGOING = "outgoing";

    // ─── Extension statuses ────────────────────────────────────────────────
    public static final String EXT_AVAILABLE = "available";
    public static final String EXT_ONCALL = "oncall";
    public static final String EXT_AWAY = "away";
    public static final String EXT_DND = "dnd";
    public static final String EXT_OFFLINE = "offline";

    // ─── API paths ─────────────────────────────────────────────────────────
    public static final String API_LOGIN = "/webclient/api/Login/GetAccessToken";
    public static final String API_ACTIVE_CALLS = "/xapi/v1/ActiveCalls";
    public static final String API_USERS = "/xapi/v1/Users";
    public static final String API_TRUNKS = "/xapi/v1/Trunks";
    public static final String API_RING_GROUPS = "/xapi/v1/RingGroups";
    public static final String API_QUEUES = "/xapi/v1/Queues";

    // ─── SIP script defaults ───────────────────────────────────────────────
    public static final String DEFAULT_MAKE_CALL_SCRIPT = "/etc/openhab/scripts/sip_makecall.py";
    public static final int DEFAULT_MAKE_CALL_DURATION = 30;

    // ─── Defaults ──────────────────────────────────────────────────────────
    public static final int DEFAULT_PORT = 5001;
    public static final int DEFAULT_POLL_INTERVAL = 2;
    public static final int DEFAULT_PRESENCE_INTERVAL = 30;
    public static final int DEFAULT_TRUNK_INTERVAL = 60;
    public static final int DEFAULT_WEBHOOK_PORT = 5002;
    public static final int TOKEN_REFRESH_MARGIN_SECONDS = 15;
    public static final int AUTO_IDLE_TIMEOUT_SECONDS = 120;
}
