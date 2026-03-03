# openHAB 3CX PBX Binding (`pbx3cx`)

A comprehensive openHAB binding for **3CX PBX V20** that provides real-time call monitoring, extension presence tracking, ring group/queue management, and a live HTML dashboard — all via the 3CX xAPI (REST + JWT).

![openHAB](https://img.shields.io/badge/openHAB-5.x-blue) ![3CX](https://img.shields.io/badge/3CX-V20-green) ![License](https://img.shields.io/badge/license-EPL--2.0-orange)

---

## Table of Contents

- [Features](#features)
- [Requirements](#requirements)
- [Installation](#installation)
- [Configuration](#configuration)
  - [Bridge (Server)](#bridge-server)
  - [Extensions](#extensions)
  - [Ring Groups](#ring-groups)
  - [Queues](#queues)
- [Channels](#channels)
  - [Bridge Channels](#bridge-channels)
  - [Extension Channels](#extension-channels)
  - [Ring Group Channels](#ring-group-channels)
  - [Queue Channels](#queue-channels)
- [Items](#items)
- [Dashboard](#dashboard)
- [Rules Examples](#rules-examples)
- [JSON Formats](#json-formats)
- [Caller ID Resolution](#caller-id-resolution)
- [Call History Persistence](#call-history-persistence)
- [Webhook Support](#webhook-support)
- [SIP / Alarm Calls](#sip--alarm-calls)
- [Building from Source](#building-from-source)
- [Architecture](#architecture)
- [Troubleshooting](#troubleshooting)
- [Credits](#credits)
- [License](#license)

---

## Features

- **Real-time call monitoring** — Active calls with caller/callee resolution, direction detection, duration tracking
- **Extension presence** — Online/offline/away/DND status for every extension
- **Ring groups & queues** — Member lists, strategy display, waiting caller counts
- **Missed call tracking** — Automatic counter, last missed caller info, reset at midnight
- **Total calls counter** — Daily call volume tracking with midnight reset
- **Call history** — Recent calls and missed calls stored as JSON (configurable size)
- **File-based persistence** — Call history survives OpenHAB restarts
- **Live HTML dashboard** — Beautiful real-time dashboard with 2-second refresh
- **Trunk monitoring** — SIP trunk online/offline status
- **VoiceMail detection** — Recognizes voicemail pickups (not counted as answered)
- **ROUTER resolution** — Handles 3CX ROUTER placeholder in call routing
- **Auto-discovery** — Extensions discovered automatically from 3CX
- **Webhook receiver** — Listens for 3CX CRM/CFD integration webhooks
- **Self-signed SSL** — Works with 3CX self-signed certificates

---

## Requirements

- **openHAB** 4.x or 5.x
- **3CX PBX V20** with xAPI enabled
- **3CX user account** with admin privileges (typically an extension in the `system_admins` group)
- Network access from openHAB to 3CX webclient port (default 5001)

---

## Installation

### Pre-built JAR (Recommended)

1. Copy the JAR file to your openHAB addons folder:

```bash
sudo cp org.openhab.binding.pbx3cx-5.2.0-SNAPSHOT.jar /usr/share/openhab/addons/
```

2. OpenHAB will automatically load the binding within ~20 seconds.

### Building from Source

See [Building from Source](#building-from-source) below.

---

## Configuration

### Bridge (Server)

The bridge connects to your 3CX PBX server and polls for call activity.

| Parameter              | Type    | Required | Default | Description                                              |
|------------------------|---------|----------|---------|----------------------------------------------------------|
| `hostname`             | String  | Yes      |         | 3CX server hostname or IP address                        |
| `port`                 | Integer | No       | 5001    | 3CX webclient API port                                   |
| `username`             | String  | Yes      |         | 3CX extension number with admin privileges               |
| `password`             | String  | Yes      |         | 3CX xAPI password for the extension                      |
| `pollInterval`         | Integer | No       | 2       | Active calls polling interval (seconds)                  |
| `presenceInterval`     | Integer | No       | 30      | Extension presence polling interval (seconds)            |
| `trunkInterval`        | Integer | No       | 60      | Trunk status polling interval (seconds)                  |
| `verifySsl`            | Boolean | No       | true    | Verify SSL certificates (set false for self-signed certs) |
| `webhookPort`          | Integer | No       | 5002    | CRM/CFD webhook listener port (0 = disabled)             |
| `recentCallsMax`       | Integer | No       | 10      | Maximum number of recent calls to keep in history        |
| `recentMissedCallsMax` | Integer | No       | 10      | Maximum number of missed calls to keep in history        |
| `sipServer`            | String  | No       |         | SIP server hostname/IP for baresip (enables alarm calls) |
| `sipExtension`         | String  | No       |         | SIP extension number (e.g. `600`)                        |
| `sipAuthUser`          | String  | No       |         | SIP authentication username                              |
| `sipAuthPass`          | String  | No       |         | SIP authentication password                              |
| `sipAlertExtensions`   | String  | No       |         | Comma-separated phone numbers for alarm calls            |
| `sipMaxRingTime`       | Integer | No       | 30      | Max seconds to wait for call answer                      |
| `sipAudioPath`         | String  | No       | `/etc/openhab/sounds/alerts` | Directory containing alert WAV files |
| `makeCallDuration`     | Integer | No       | 30      | Default call duration in seconds for plain calls         |

> **SIP Note:** The SIP parameters (`sipServer`, `sipExtension`, `sipAuthUser`, `sipAuthPass`) are required for `makeCall` and `alarmCall` functionality. The binding uses an embedded [baresip](https://github.com/baresip/baresip) SIP client — see [SIP / Alarm Calls](#sip--alarm-calls) for details.

**Example** (`things/pbx3cx.things`):

```java
Bridge pbx3cx:server:main "3CX PBX" [
    hostname="your-3cx-server.example.com",
    port=5001,
    username="600",
    password="your-xapi-password",
    pollInterval=2,
    presenceInterval=30,
    trunkInterval=60,
    verifySsl=true,
    webhookPort=0,
    recentCallsMax=20,
    recentMissedCallsMax=20,
    sipServer="192.168.x.x",
    sipExtension="600",
    sipAuthUser="your-sip-user",
    sipAuthPass="your-sip-password",
    sipAlertExtensions="+45XXXXXXXX,+45YYYYYYYY",
    sipMaxRingTime=30,
    sipAudioPath="/etc/openhab/sounds/alerts",
    makeCallDuration=30
] {
    // Extensions
    Thing extension ext200 "200 John Doe"       [ extensionNumber="200" ]
    Thing extension ext201 "201 Jane Smith"      [ extensionNumber="201" ]
    Thing extension ext202 "202 Bob Wilson"      [ extensionNumber="202" ]

    // Ring Groups
    Thing ringgroup rg800 "800 Sales"            [ ringGroupNumber="800" ]
    Thing ringgroup rg801 "801 Support"          [ ringGroupNumber="801" ]

    // Queues
    Thing queue q900 "900 Main Queue"            [ queueNumber="900" ]
}
```

> **Security Note:** Never commit `password` values to version control. Use openHAB's credential store or keep the things file out of your Git repository.

### Extensions

Extensions are child things of the bridge that track individual phone status.

| Parameter         | Type   | Required | Description                          |
|-------------------|--------|----------|--------------------------------------|
| `extensionNumber` | String | Yes      | The 3CX extension number (e.g. 200)  |

Extensions can also be auto-discovered — go to **Settings → Things → 3CX PBX Server → Scan** in the openHAB UI.

### Ring Groups

| Parameter         | Type   | Required | Description                              |
|-------------------|--------|----------|------------------------------------------|
| `ringGroupNumber` | String | Yes      | The 3CX ring group number (e.g. 800)     |

### Queues

| Parameter     | Type   | Required | Description                          |
|---------------|--------|----------|--------------------------------------|
| `queueNumber` | String | Yes      | The 3CX queue number (e.g. 900)      |

---

## Channels

### Bridge Channels

| Channel                | Type     | Description                                          |
|------------------------|----------|------------------------------------------------------|
| `callState`            | String   | Current call state: `idle`, `ringing`, `active`, `outgoing` |
| `callerNumber`         | String   | Phone number of the current/last caller (see [Caller ID Resolution](#caller-id-resolution)) |
| `callerName`           | String   | Display name of the current/last caller              |
| `calledNumber`         | String   | The destination number being called                  |
| `callDirection`        | String   | Call direction: `inbound`, `outbound`, `internal`    |
| `callDuration`         | Number   | Duration of the last call in seconds                 |
| `callAgent`            | String   | Extension handling the call                          |
| `callTimestamp`        | DateTime | Timestamp of the current/last call event             |
| `missedCount`          | Number   | Number of missed calls since midnight (auto-resets)  |
| `totalCalls`           | Number   | Total calls today since midnight (auto-resets)       |
| `lastMissedCaller`     | String   | Caller info for the last missed call                 |
| `lastMissedTime`       | DateTime | Timestamp of the last missed call                    |
| `activeCalls`          | Number   | Number of currently active calls                     |
| `activeCallsJson`      | String   | JSON array of active calls (see [JSON Formats](#json-formats)) |
| `recentCallsJson`      | String   | JSON array of recent completed calls                 |
| `recentMissedCallsJson`| String   | JSON array of recent missed calls                    |
| `trunkStatus`          | String   | SIP trunk status summary                             |
| `recordingUrl`         | String   | URL to the last call recording                       |
| `systemStatus`         | String   | PBX connection status: `ONLINE`, `OFFLINE`           |
| `makeCall`             | String   | Send a command to initiate a SIP call (destination number) |
| `alarmCall`            | String   | Trigger alarm call with audio + DTMF confirmation (see below) |
| `alarmCallResult`      | String   | Result of last alarm call: `CONFIRMED`, `NO_ANSWER`, `FAILED` |

### Extension Channels

| Channel      | Type   | Description                                                  |
|--------------|--------|--------------------------------------------------------------|
| `status`     | String | Extension status: `idle`, `ringing`, `dialing`, `talking`, `offline` |
| `presence`   | String | Presence profile: `Available`, `Away`, `DND`, `Lunch`, etc.  |
| `name`       | String | Display name of the extension                                |
| `registered` | Switch | Whether the extension is registered (ON/OFF)                 |

### Ring Group Channels

| Channel       | Type   | Description                                          |
|---------------|--------|------------------------------------------------------|
| `rgName`      | String | Ring group display name                              |
| `rgStrategy`  | String | Ring strategy: `Hunt`, `RingAll`, `Paging`, etc.     |
| `rgMembers`   | String | Comma-separated list of members                      |
| `rgMemberCount` | Number | Number of members in the group                    |
| `rgRegistered`| Switch | Whether the ring group is registered                 |

### Queue Channels

| Channel          | Type   | Description                                       |
|------------------|--------|---------------------------------------------------|
| `qName`          | String | Queue display name                                |
| `qStrategy`      | String | Polling strategy: `RingAll`, `Hunt`, `LongestWaiting`, etc. |
| `qAgents`        | String | Comma-separated list of agents                    |
| `qAgentCount`    | Number | Number of agents in the queue                     |
| `qWaitingCallers` | Number | Number of callers currently waiting in queue      |
| `qRegistered`    | Switch | Whether the queue is registered                   |

---

## Items

Example items file (`items/3cx_pbx.items`):

```java
Group gPBX "3CX Telefon" <phone> (gAll)

// Call state
String PBX_Call_State      "Call Status [%s]"    <phone>    (gPBX) { channel="pbx3cx:server:main:callState" }
String PBX_Caller_Number   "Caller [%s]"         <phone>    (gPBX) { channel="pbx3cx:server:main:callerNumber" }
String PBX_Caller_Name     "Caller Name [%s]"    <man_3>    (gPBX) { channel="pbx3cx:server:main:callerName" }
String PBX_Called_Number    "Called [%s]"          <phone>    (gPBX) { channel="pbx3cx:server:main:calledNumber" }
String PBX_Call_Direction   "Direction [%s]"       <flow>     (gPBX) { channel="pbx3cx:server:main:callDirection" }
Number PBX_Call_Duration    "Duration [%d sec]"    <time>     (gPBX) { channel="pbx3cx:server:main:callDuration" }
String PBX_Call_Agent       "Agent [%s]"           <man_3>    (gPBX) { channel="pbx3cx:server:main:callAgent" }
DateTime PBX_Call_Timestamp "Time [%1$tH:%1$tM]"   <calendar> (gPBX) { channel="pbx3cx:server:main:callTimestamp" }

// Missed calls
String   PBX_Last_Missed_Caller "Last Missed [%s]"      <phone>      (gPBX) { channel="pbx3cx:server:main:lastMissedCaller" }
DateTime PBX_Last_Missed_Time   "Missed Time [%1$tH:%1$tM]" <calendar> (gPBX) { channel="pbx3cx:server:main:lastMissedTime" }
Number   PBX_Missed_Count_Today "Missed Today [%d]"      <returnpipe> (gPBX) { channel="pbx3cx:server:main:missedCount" }
Number   PBX_Total_Calls_Today  "Calls Today [%d]"       <phone>      (gPBX) { channel="pbx3cx:server:main:totalCalls" }

// JSON data (for dashboard)
Number PBX_Active_Calls             "Active Calls [%d]"          <phone> (gPBX) { channel="pbx3cx:server:main:activeCalls" }
String PBX_Active_Calls_Json        "Active Calls JSON [%s]"     <phone> (gPBX) { channel="pbx3cx:server:main:activeCallsJson" }
String PBX_Recent_Calls_Json        "Recent Calls JSON [%s]"     <phone> (gPBX) { channel="pbx3cx:server:main:recentCallsJson" }
String PBX_Recent_Missed_Calls_Json "Missed Calls JSON [%s]"     <phone> (gPBX) { channel="pbx3cx:server:main:recentMissedCallsJson" }

// SIP / Alarm calls
String PBX_Make_Call       "Make Call [%s]"      <phone>   (gPBX) { channel="pbx3cx:server:main:makeCall" }
String PBX_Alarm_Call      "Alarm Call [%s]"     <siren>   (gPBX) { channel="pbx3cx:server:main:alarmCall" }
String PBX_Alarm_Result    "Alarm Result [%s]"   <text>    (gPBX) { channel="pbx3cx:server:main:alarmCallResult" }

// System
String PBX_System_Status   "PBX Status [%s]"     <network> (gPBX) { channel="pbx3cx:server:main:systemStatus" }
String PBX_Trunk_Status    "Trunk Status [%s]"    <network> (gPBX) { channel="pbx3cx:server:main:trunkStatus" }
String PBX_Recording_URL   "Recording [%s]"       <recorder>(gPBX) { channel="pbx3cx:server:main:recordingUrl" }
```

---

## Dashboard

The binding includes a live HTML dashboard (`3cx_dashboard.html`) that displays:

- **Stats bar** — Active calls, total calls today, missed today, extensions online, ring groups, queues
- **Active calls** — Real-time cards with caller/callee, direction, duration, agent badge
- **Recent calls** — Table of last N completed calls with time, direction, duration, status
- **Missed calls** — Table of unanswered calls
- **Extensions** — Collapsible grid showing all extensions with status indicators
- **Ring groups** — Cards with strategy badge and full member list
- **Queues** — Cards with strategy, agent list, and waiting caller count
- **Trunks** — SIP trunk online/offline status

### Installation

1. Copy `3cx_dashboard.html` to your openHAB HTML folder:

```bash
cp 3cx_dashboard.html /etc/openhab/html/
```

2. Add to your sitemap:

```java
sitemap myhouse label="My House" {
    Frame label="Phone System" {
        Webview url="/static/3cx_dashboard.html" height=22
    }
}
```

3. Or access directly at: `http://your-openhab:8080/static/3cx_dashboard.html`

The dashboard refreshes every 2 seconds and auto-discovers extensions, ring groups, and queues from the OpenHAB REST API.

---

## Rules Examples

### Send Notification on Missed Call

```java
rule "PBX Missed Call Notification"
when
    Item PBX_Missed_Count_Today changed
then
    val caller = PBX_Last_Missed_Caller.state.toString
    val time = PBX_Last_Missed_Time.state.toString
    logInfo("pbx", "Missed call from: {} at {}", caller, time)

    // Send push notification (requires a notification action binding)
    // sendNotification("user@email.com", "Missed call from " + caller)

    // Or use Telegram
    // val telegramAction = getActions("telegram", "telegram:telegramBot:mybot")
    // telegramAction.sendTelegram("📞 Ubesvaret opkald fra: " + caller)
end
```

### Log All Inbound Calls

```java
rule "PBX Inbound Call Alert"
when
    Item PBX_Call_State changed to "ringing"
then
    if (PBX_Call_Direction.state.toString == "inbound") {
        val caller = PBX_Caller_Number.state.toString
        val name = PBX_Caller_Name.state.toString
        logInfo("pbx", "Incoming call from {} ({})", caller, name)

        // Flash lights, play sound, etc.
        // Hue_LR_Color.sendCommand("0,100,100")
    }
end
```

### Flash Light for Specific Caller

Trigger on `PBX_Caller_Number changed` instead of `PBX_Call_State` — this catches both ringing and fast-answered calls that skip the ringing state in polling:

```java
var Timer callerFlashTimer = null
var boolean callerFlashRed = true
var boolean callerLightWasOff = true

rule "VIP Caller Flash"
when
    Item PBX_Caller_Number changed
then
    val callerNumber = PBX_Caller_Number.state.toString
    if (callerNumber.contains("22162460")) {  // VIP number
        if (callerFlashTimer !== null) return;
        callerFlashRed = true
        callerLightWasOff = (My_Light_Color.state.toString.endsWith(",0"))
        My_Light_Toggle.sendCommand(ON)
        callerFlashTimer = createTimer(now, [|
            if (callerFlashRed) {
                My_Light_Color.sendCommand("0,100,100")    // Red
            } else {
                My_Light_Color.sendCommand("240,100,100")  // Blue
            }
            callerFlashRed = !callerFlashRed
            callerFlashTimer.reschedule(now.plusSeconds(1))
        ])
    }
end

rule "VIP Caller Flash Stop"
when
    Item PBX_Call_State changed to "active" or
    Item PBX_Call_State changed to "idle"
then
    if (callerFlashTimer !== null) {
        callerFlashTimer.cancel
        callerFlashTimer = null
        if (callerLightWasOff) My_Light_Toggle.sendCommand(OFF)
    }
end
```

> **Why trigger on `PBX_Caller_Number changed`?** When a call is answered very quickly (before the 2-second poll catches the ringing state), the binding may detect it as already `"Talking"` (→ `active`). Triggering on caller number change ensures the rule fires for every new call regardless of answer speed. The flash stops on pickup (`active`) or call end (`idle`).

### Auto-DND After Hours

```java
rule "PBX After Hours DND"
when
    Time cron "0 0 17 ? * MON-FRI"  // 5 PM weekdays
then
    logInfo("pbx", "After hours — setting phones to DND")
    // Use 3CX management to set presence profiles
end

rule "PBX Morning Available"
when
    Time cron "0 0 8 ? * MON-FRI"   // 8 AM weekdays
then
    logInfo("pbx", "Morning — phones available")
end
```

### Track Extension Presence Changes

```java
rule "Extension 200 Status Changed"
when
    Item PBX_Ext_200_Status changed
then
    logInfo("pbx", "Ext 200 status: {}", PBX_Ext_200_Status.state.toString)
end
```

### Alarm Call — Vehicle Towing Alert

```java
rule "Vehicle Towing Alert"
when
    Item Vehicle_GPS_Towing received update ON
then
    logWarn("pbx", "Towing detected! Triggering alarm call")
    PBX_Alarm_Call.sendCommand("towing")  // plays towing_alert.wav, calls sipAlertExtensions
end

rule "Alarm Call Result"
when
    Item PBX_Alarm_Result changed
then
    val result = PBX_Alarm_Result.state.toString
    logInfo("pbx", "Alarm call result: {}", result)
    if (result == "CONFIRMED") {
        logInfo("pbx", "Alert acknowledged by user via DTMF")
    } else if (result == "NO_ANSWER") {
        logWarn("pbx", "No one confirmed the alarm call!")
        // Retry or escalate
    }
end
```

### Plain SIP Call

```java
rule "Call Extension on Event"
when
    Item Doorbell_Button received update ON
then
    PBX_Make_Call.sendCommand("+45XXXXXXXX")  // calls number for makeCallDuration seconds
end
```

### Missed Calls Daily Summary

```java
rule "PBX Daily Summary"
when
    Time cron "0 0 18 ? * MON-FRI"  // 6 PM weekdays
then
    val missed = PBX_Missed_Count_Today.state as Number
    val total = PBX_Total_Calls_Today.state as Number
    logInfo("pbx", "Daily summary: {} total calls, {} missed", total, missed)
end
```

### React to Queue Waiting Callers

```java
// Requires a queue item:
// Number PBX_Q_900_Waiting "Queue Waiting [%d]" { channel="pbx3cx:queue:main:q900:qWaitingCallers" }

rule "Queue High Wait Alert"
when
    Item PBX_Q_900_Waiting changed
then
    val waiting = (PBX_Q_900_Waiting.state as Number).intValue
    if (waiting > 3) {
        logWarn("pbx", "Queue 900 has {} callers waiting!", waiting)
        // Alert staff
    }
end
```

---

## JSON Formats

### Active Calls JSON (`activeCallsJson`)

```json
[
  {
    "id": 1234,
    "callerNumber": "53766211",
    "callerName": "1TEL.DK",
    "calledNumber": "200",
    "calledName": "John Doe",
    "status": "Talking",
    "direction": "inbound",
    "duration": 45,
    "agent": "200"
  }
]
```

### Recent Calls JSON (`recentCallsJson`)

```json
[
  {
    "callerNumber": "200",
    "callerName": "John Doe",
    "calledNumber": "304",
    "calledName": "Reception",
    "direction": "internal",
    "duration": 42,
    "answered": true,
    "endTime": "2026-03-03T13:06:30.225+01:00"
  }
]
```

### Recent Missed Calls JSON (`recentMissedCallsJson`)

```json
[
  {
    "callerNumber": "200",
    "callerName": "John Doe",
    "calledNumber": "",
    "calledName": "Routing...",
    "direction": "internal",
    "endTime": "2026-03-03T12:59:15.013+01:00"
  }
]
```

**Special callee resolution:**
- **ROUTER** — Displayed as `"Routing..."` when 3CX is routing the call
- **VoiceMail** — Resolved to the actual extension (e.g., `"VoiceMail of 200 John Doe"` → calledNumber=`200`, calledName=`John Doe`)
- **Trunk** — External number extracted from trunk string (e.g., `"10002 Provider (30684327)"` → `30684327`)

---

## Caller ID Resolution

For inbound calls via SIP trunks, 3CX's `ActiveCalls` API sometimes returns only the **DID number** (the trunk's own number) instead of the real external caller number. The binding resolves this automatically:

1. When a new inbound trunk call is detected, the binding queries `CallHistoryView` via OData to look up the `SrcCallerNumber` field
2. If found, the resolved external number is used instead of the DID
3. Falls back to extracting the number from the trunk name parentheses (e.g., `"1TEL.DK Trunk (22162460)"` → `22162460`)

### Caller Number Lifecycle

The `callerNumber` channel follows a specific lifecycle to ensure reliable rule triggers:

- **New call detected:** Caller number is **cleared to empty** then set to the resolved number. This guarantees a `changed` event even if the same person calls twice in a row.
- **Call ends (idle):** Caller number is cleared to empty.
- **Binding restart:** Caller number state is reset, so the next call will always trigger rules.

This design means rules using `Item PBX_Caller_Number changed` will fire reliably for every new call, regardless of polling timing or previous state.

---

## Call History Persistence

The binding persists call history to disk so it survives restarts:

- **File:** `<OPENHAB_USERDATA>/pbx3cx/call_history.json`
  - Typically: `/var/lib/openhab/pbx3cx/call_history.json`
- **Saved:** After every completed call and on binding shutdown
- **Restored:** On binding startup/reconnect
- **Contents:** Recent calls, missed calls, missed count today, total calls today
- **Midnight reset:** Both counters (`missedCountToday`, `totalCallsToday`) reset to 0 at midnight

The history size is controlled by the bridge config parameters `recentCallsMax` and `recentMissedCallsMax`.

---

## Webhook Support

The binding includes an optional webhook listener for 3CX CRM/CFD integration:

1. Set `webhookPort` in the bridge config (e.g., `5002`)
2. Configure 3CX CRM integration to POST to `http://your-openhab:5002/pbx3cx/webhook`
3. The binding will process incoming webhook events

Set `webhookPort=0` to disable the webhook listener.

---

## SIP / Alarm Calls

The binding includes a native SIP client powered by [baresip](https://github.com/baresip/baresip) for making outbound calls directly from openHAB — no external scripts required.

### Requirements

- **baresip** must be installed on the openHAB server:
  ```bash
  sudo apt install baresip
  ```

### How It Works

When SIP parameters are configured on the bridge, the binding initialises an embedded `BaresipSipClient` that manages the full SIP lifecycle:

1. **Writes baresip config** to `<OPENHAB_USERDATA>/pbx3cx/baresip/` with SIP account, audio settings, and transport
2. **Launches baresip** as a subprocess, registers with the SIP server
3. **Dials the destination** and monitors stdout for call events
4. **For alarm calls:** Plays a WAV audio file over SIP (RTP) and listens for DTMF key press (`1`) as acknowledgement

### Channels

| Channel           | Direction | Description |
|-------------------|-----------|-------------|
| `makeCall`        | Command   | Send a phone number to initiate a plain SIP call (rings for `makeCallDuration` seconds, then hangs up) |
| `alarmCall`       | Command   | Send an alert type (`towing`, `unplug`, or `generic`) to call all `sipAlertExtensions` with audio + DTMF confirmation |
| `alarmCallResult` | State     | Updated after alarm call completes: `CONFIRMED` (DTMF received), `NO_ANSWER` (no answer/no DTMF), or `FAILED` |

### Alert Types

The `alarmCall` channel accepts an alert type string that maps to a WAV file in `sipAudioPath`:

| Alert Type | Audio File          | Description                |
|------------|---------------------|----------------------------|
| `towing`   | `towing_alert.wav`  | Vehicle towing detection   |
| `unplug`   | `unplug_alert.wav`  | GPS tracker disconnected   |
| `generic`  | `towing_alert.wav`  | Fallback alert audio       |

The WAV file is automatically extended to ~60 seconds (looped) to maintain RTP keepalive during the call.

### Call Flow (Alarm Call)

```
alarmCall("towing") received
  → For each number in sipAlertExtensions:
      1. Start baresip with SIP account config
      2. Register with SIP server (wait for "100 Trying")
      3. Dial destination number
      4. Wait for answer (up to sipMaxRingTime seconds)
      5. Play audio over RTP (towing_alert.wav, extended to ~60s)
      6. Listen for DTMF digit "1" (confirmation)
      7. If DTMF "1" received → alarmCallResult = CONFIRMED, stop
      8. If no answer or no DTMF → try next number
  → If no number confirmed → alarmCallResult = NO_ANSWER
```

---

## Building from Source

**CRITICAL:** Build in the openHAB-Addons repository where all dependencies are available.

```bash
# Clone the openHAB addons repo (if not already)
git clone https://github.com/openhab/openhab-addons.git /path/to/openhab-addons

# Copy binding source into the addons repo
cp -r src/ /path/to/openhab-addons/bundles/org.openhab.binding.pbx3cx/src/
cp pom.xml /path/to/openhab-addons/bundles/org.openhab.binding.pbx3cx/pom.xml

# Build
cd /path/to/openhab-addons
mvn spotless:apply -pl bundles/org.openhab.binding.pbx3cx
mvn clean install -pl bundles/org.openhab.binding.pbx3cx -am -DskipTests

# Deploy (build takes ~3-4 minutes)
sudo cp bundles/org.openhab.binding.pbx3cx/target/org.openhab.binding.pbx3cx-*.jar \
    /usr/share/openhab/addons/
```

---

## Architecture

```
org.openhab.binding.pbx3cx/
├── internal/
│   ├── Pbx3cxBindingConstants.java      — Channel IDs, Thing type UIDs, defaults
│   ├── Pbx3cxHandlerFactory.java        — Creates handler instances for bridges/things
│   ├── Pbx3cxServerConfiguration.java   — Bridge config parameters (host, port, etc.)
│   ├── Pbx3cxServerHandler.java         — Main bridge handler (~1200 lines)
│   │   ├── Polling: active calls, presence, trunks, ring groups, queues
│   │   ├── Call state machine: new → ringing/talking → ended
│   │   ├── Missed/total call tracking with midnight reset
│   │   ├── JSON builders for active/recent/missed calls
│   │   ├── File-based call history persistence
│   │   ├── SIP makeCall / alarmCall via BaresipSipClient
│   │   └── Child handler notification for extensions/RG/queues
│   ├── Pbx3cxApiClient.java             — REST client for 3CX xAPI (~480 lines)
│   │   ├── JWT authentication with 60s auto-refresh
│   │   ├── Active calls, extensions, trunks, ring groups, queues
│   │   ├── Ring group members, queue agents
│   │   └── CallHistoryView query for real external caller ID resolution
│   ├── BaresipSipClient.java            — Native SIP client (~500 lines)
│   │   ├── Baresip process lifecycle management
│   │   ├── SIP registration and call dialing
│   │   ├── DTMF detection for alarm acknowledgement
│   │   ├── WAV audio extension (loop to ~60s for RTP keepalive)
│   │   └── Multi-number sequential retry for alarm calls
│   ├── Pbx3cxExtensionHandler.java      — Extension child handler
│   ├── Pbx3cxExtensionConfiguration.java
│   ├── Pbx3cxRingGroupHandler.java      — Ring group child handler
│   ├── Pbx3cxRingGroupConfiguration.java
│   ├── Pbx3cxQueueHandler.java          — Queue child handler
│   ├── Pbx3cxQueueConfiguration.java
│   ├── Pbx3cxWebhookServlet.java        — CRM/CFD webhook receiver
│   ├── TrustAllSslUtil.java             — SSL bypass for self-signed certs
│   ├── dto/
│   │   ├── ActiveCall.java              — Active call data model
│   │   ├── CallerInfo.java              — Caller/callee parser with ROUTER/VM detection
│   │   ├── CallQueue.java               — Queue + Agent models
│   │   ├── Extension.java               — Extension data model
│   │   └── RingGroup.java               — Ring group + Member models
│   └── discovery/
│       └── Pbx3cxExtensionDiscoveryService.java — Auto-discover extensions
└── resources/
    └── OH-INF/
        ├── binding/binding.xml          — Binding metadata
        └── thing/thing-types.xml        — Thing types, channels, config parameters
```

### Polling Schedule

| Job              | Default Interval | Purpose                              |
|------------------|------------------|--------------------------------------|
| `pollJob`        | 2 seconds        | Active calls, call state changes     |
| `presenceJob`    | 30 seconds       | Extension status and presence        |
| `trunkJob`       | 60 seconds       | SIP trunk status                     |
| `groupQueueJob`  | 60 seconds       | Ring groups, queues, members/agents  |
| `midnightReset`  | Daily at 00:00   | Reset missed + total call counters   |

### Call State Machine

```
NEW CALL → clear callerNumber to ""
        → set callerNumber to resolved external number
        → "Routing" (ROUTER) / "Ringing" / "Talking"
        → ENDED → clear callerNumber to ""
               → wasAnswered?
                    ├── YES → recentCalls list
                    └── NO  → recentMissedCalls list + increment missedCountToday
```

> **Note:** The caller number is always cleared before being set on new calls, ensuring a `changed` event fires even for consecutive calls from the same number.

**Special cases:**
- VoiceMail pickup (`"Talking"` status with VoiceMail callee) → counted as **unanswered**
- ROUTER destination (never resolved to real extension) → counted as **missed**
- All calls increment `totalCallsToday` regardless of answered status

---

## Troubleshooting

### Binding shows OFFLINE

- Check that 3CX hostname and port are reachable from the openHAB server
- Verify the username is an extension number with admin privileges
- Check openHAB logs: `tail -f /var/log/openhab/openhab.log | grep pbx3cx`

### Extensions not discovered

- Ensure the 3CX user has admin rights to query extensions
- Try manual scan in openHAB UI: **Settings → Things → 3CX PBX Server → Scan**

### SSL Connection Errors

- Set `verifySsl=false` if your 3CX uses self-signed certificates

### Call History Lost After Restart

- Check permissions on `/var/lib/openhab/pbx3cx/` — the openHAB user needs write access
- Check logs for `"Failed to save/load call history"` messages

### Dashboard Not Loading

- Ensure the items are created and linked to channels
- Check browser console for REST API errors
- Verify the dashboard URL: `http://your-openhab:8080/static/3cx_dashboard.html`

### Alarm Calls Not Working

- Verify `baresip` is installed: `which baresip`
- Check SIP credentials match your PBX extension configuration
- Ensure the SIP extension is not registered elsewhere (only one registration allowed)
- Verify WAV files exist in `sipAudioPath` directory
- Check logs: `grep -i "baresip\|alarm.*call\|sip.*client" /var/log/openhab/openhab.log`
- The binding logs SIP registration, dialing, DTMF events, and call results

---

## Credits

**Author:** Nanna Agesen — [Nanna@agesen.dk](mailto:Nanna@agesen.dk)
**GitHub:** [@Prinsessen](https://github.com/Prinsessen)
**Company:** [Agesen EL-Teknik](tel:+4598232010) — +45 98 23 20 10

---

## License

This binding is distributed under the [Eclipse Public License 2.0](https://www.eclipse.org/legal/epl-2.0/).

Copyright (c) 2026 Contributors to the openHAB project.
