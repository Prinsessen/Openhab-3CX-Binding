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

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.openhab.core.OpenHAB;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Java SIP client using baresip as the underlying VoIP engine.
 * <p>
 * Replaces the external sip_makecall.py Python script. Manages the full lifecycle
 * of baresip processes: configuration, SIP registration, dialing, audio playback,
 * DTMF detection, and cleanup.
 * <p>
 * Supports two modes:
 * <ul>
 * <li><b>Plain call</b> — dials a number and holds the line for a configured duration</li>
 * <li><b>Alarm call</b> — plays alert audio and waits for DTMF '1' human confirmation,
 * retrying across multiple destinations</li>
 * </ul>
 *
 * @author openHAB Community - Initial contribution
 */
@NonNullByDefault
public class BaresipSipClient {

    private final Logger logger = LoggerFactory.getLogger(BaresipSipClient.class);

    // SIP configuration
    private final String sipServer;
    private final String sipExtension;
    private final String sipAuthUser;
    private final String sipAuthPass;
    private final String[] alertExtensions;
    private final int maxRingTime;
    private final Path alertAudioPath;
    private final int makeCallDuration;

    // Baresip working directory (accounts + config files)
    private static final Path BARESIP_DIR = Path.of(OpenHAB.getUserDataFolder(), "pbx3cx", "baresip");

    // Only one baresip instance at a time
    private static final ReentrantLock CALL_LOCK = new ReentrantLock();

    // Alert type → WAV filename mapping
    private static final Map<String, String> ALERT_AUDIO = Map.of("towing", "towing_alert.wav", "unplug",
            "unplug_alert.wav", "generic", "towing_alert.wav");

    /** Result constants for channel state updates. */
    public static final String RESULT_CONFIRMED = "CONFIRMED";
    public static final String RESULT_NO_RESPONSE = "NO_RESPONSE";
    public static final String RESULT_CALL_SUCCESS = "CALL_SUCCESS";
    public static final String RESULT_CALL_FAILED = "CALL_FAILED";
    public static final String RESULT_ERROR = "ERROR";

    /**
     * Create a new SIP client from binding configuration.
     */
    public BaresipSipClient(Pbx3cxServerConfiguration config) {
        this.sipServer = config.sipServer;
        this.sipExtension = config.sipExtension;
        this.sipAuthUser = config.sipAuthUser;
        this.sipAuthPass = config.sipAuthPass;
        this.maxRingTime = config.sipMaxRingTime;
        this.alertAudioPath = Path.of(config.sipAudioPath);
        this.makeCallDuration = config.makeCallDuration;

        String raw = config.sipAlertExtensions;
        this.alertExtensions = raw.isEmpty() ? new String[0]
                : Arrays.stream(raw.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toArray(String[]::new);
    }

    /**
     * Check if the SIP client has the minimum required configuration.
     */
    public boolean isConfigured() {
        return !sipServer.isEmpty() && !sipExtension.isEmpty() && !sipAuthUser.isEmpty() && !sipAuthPass.isEmpty();
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Plain Call
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Place a plain SIP call (no audio, just connect and hold).
     *
     * @param destination Phone number or extension to dial
     * @return CALL_SUCCESS, CALL_FAILED, or ERROR
     */
    public String makePlainCall(String destination) {
        if (!CALL_LOCK.tryLock()) {
            logger.warn("MakeCall: another SIP call is already in progress");
            return RESULT_ERROR;
        }
        try {
            return executePlainCall(destination);
        } finally {
            CALL_LOCK.unlock();
        }
    }

    private String executePlainCall(String destination) {
        logger.info("Plain call to {} (duration: {}s)", destination, makeCallDuration);

        // Create a real silence WAV so baresip sends proper RTP (required for external calls through PBX)
        Path silenceWav = createSilenceWav(makeCallDuration + 30);
        writeBaresipConfig(silenceWav.toString());

        Process proc = startBaresip();
        if (proc == null) {
            deleteTempFile(silenceWav);
            return RESULT_CALL_FAILED;
        }

        BlockingQueue<String> lines = new LinkedBlockingQueue<>();
        Thread reader = startOutputReader(proc, lines);

        try {
            if (!waitForRegistration(lines, 15)) {
                return RESULT_CALL_FAILED;
            }

            Thread.sleep(500);

            String bridgeResult = dialAndWaitBridge(proc, lines, destination, maxRingTime);

            if ("bridged".equals(bridgeResult)) {
                logger.info("Call bridged — holding for {}s", makeCallDuration);
                long callStart = System.currentTimeMillis();

                while (System.currentTimeMillis() - callStart < makeCallDuration * 1000L) {
                    String line = lines.poll(1, TimeUnit.SECONDS);
                    if (line != null && containsAny(line.toLowerCase(), "closed", "terminated", "failed", "bye")) {
                        logger.info("Remote hangup: {}", line);
                        break;
                    }
                }

                long actual = (System.currentTimeMillis() - callStart) / 1000;
                sendCmd(proc, "/hangup");
                Thread.sleep(500);
                logger.info("Plain call {} completed ({}s)", destination, actual);
                return RESULT_CALL_SUCCESS;
            }

            if ("timeout".equals(bridgeResult)) {
                logger.warn("No answer after {}s — hanging up", maxRingTime);
                sendCmd(proc, "/hangup");
                Thread.sleep(1000);
            }

            return RESULT_CALL_FAILED;

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return RESULT_ERROR;
        } finally {
            shutdownBaresip(proc);
            reader.interrupt();
            deleteTempFile(silenceWav);
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Alarm Call — plays audio, waits for DTMF '1' confirmation
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Place an alarm call with audio playback and DTMF confirmation.
     * Loops through destinations until someone confirms or max time is reached.
     *
     * @param alertType Type of alert (towing, unplug, generic)
     * @param destination Optional specific destination (null = use configured alertExtensions)
     * @return CONFIRMED, NO_RESPONSE, or ERROR
     */
    public String makeAlarmCall(String alertType, @Nullable String destination) {
        if (!CALL_LOCK.tryLock()) {
            logger.warn("AlarmCall: another SIP call is already in progress");
            return RESULT_ERROR;
        }
        try {
            return executeAlarmCall(alertType, destination);
        } finally {
            CALL_LOCK.unlock();
        }
    }

    private String executeAlarmCall(String alertType, @Nullable String destination) {
        // Resolve audio file from alert type
        String audioFile = ALERT_AUDIO.getOrDefault(alertType, "towing_alert.wav");

        Path audioPath = alertAudioPath.resolve(audioFile);
        if (!Files.exists(audioPath)) {
            logger.error("Audio file not found: {}", audioPath);
            return RESULT_ERROR;
        }

        // Build destination list
        String[] destinations;
        if (destination != null && !destination.isEmpty()) {
            destinations = new String[] { destination };
        } else {
            destinations = alertExtensions.length > 0 ? alertExtensions : new String[] { "200" };
        }

        int maxTotalTime = 120;
        logger.info("=== ALARM CALL: {} -> {} (max {}s) ===", alertType.toUpperCase(), Arrays.toString(destinations),
                maxTotalTime);

        // Create extended audio (~60s) to keep RTP flowing past mobile ring timeout
        Path extendedAudio = createExtendedAudio(audioPath, 60, 2);

        // Write baresip config once with alarm audio source
        writeBaresipConfig(extendedAudio.toString());

        long startTime = System.currentTimeMillis();
        int roundNum = 0;

        try {
            while ((System.currentTimeMillis() - startTime) < maxTotalTime * 1000L) {
                roundNum++;

                for (String dest : destinations) {
                    long remaining = maxTotalTime * 1000L - (System.currentTimeMillis() - startTime);
                    if (remaining <= 0) {
                        break;
                    }

                    logger.info("--- Round {}: Alarm call to {} ({}s remaining) ---", roundNum, dest, remaining / 1000);

                    if (alarmSingleCall(dest)) {
                        long elapsed = (System.currentTimeMillis() - startTime) / 1000;
                        logger.info("=== ALARM CONFIRMED in round {} ({}s) ===", roundNum, elapsed);
                        return RESULT_CONFIRMED;
                    }

                    Thread.sleep(2000);
                }
            }

            long elapsed = (System.currentTimeMillis() - startTime) / 1000;
            logger.warn("=== ALARM NO RESPONSE after {} round(s) ({}s) ===", roundNum, elapsed);
            return RESULT_NO_RESPONSE;

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return RESULT_ERROR;
        } finally {
            // Clean up extended audio temp file
            if (!extendedAudio.equals(audioPath)) {
                try {
                    Files.deleteIfExists(extendedAudio);
                } catch (IOException ignored) {
                }
            }
        }
    }

    /**
     * Single alarm call attempt: start baresip, register, dial, play audio, detect DTMF.
     *
     * @return true if DTMF confirmation was received
     */
    private boolean alarmSingleCall(String destination) {
        Process proc = startBaresip();
        if (proc == null) {
            return false;
        }

        BlockingQueue<String> output = new LinkedBlockingQueue<>();
        Thread reader = startOutputReader(proc, output);

        try {
            if (!waitForRegistration(output, 15)) {
                return false;
            }

            Thread.sleep(500);

            String result = dialAndWaitBridge(proc, output, destination, maxRingTime);

            if ("bridged".equals(result)) {
                logger.info("Call bridged — playing audio, waiting for DTMF...");
                long playStart = System.currentTimeMillis();
                int listenTime = 65; // audio is ~60s + 5s margin

                while (System.currentTimeMillis() - playStart < listenTime * 1000L) {
                    String line = output.poll(500, TimeUnit.MILLISECONDS);
                    if (line == null) {
                        continue;
                    }

                    String ll = line.toLowerCase();

                    // Log interesting lines at INFO level
                    if (containsAny(ll, "dtmf", "key", "event", "closed", "terminated", "failed", "bye")) {
                        logger.info("  baresip> {}", line);
                    }

                    // Check for DTMF confirmation
                    if (ll.contains("dtmf") || (ll.contains("event") && containsAnyChar(line, "0123456789*#"))) {
                        long duration = (System.currentTimeMillis() - playStart) / 1000;
                        logger.info("CONFIRMED: DTMF detected after {}s", duration);
                        sendCmd(proc, "/hangup");
                        Thread.sleep(500);
                        return true;
                    }

                    // Check for call ended by remote
                    if (containsAny(ll, "closed", "terminated", "failed", "bye")) {
                        logger.info("Call ended without DTMF");
                        break;
                    }
                }

                logger.info("No DTMF after {}s", (System.currentTimeMillis() - playStart) / 1000);
                sendCmd(proc, "/hangup");
                Thread.sleep(500);

            } else if ("timeout".equals(result)) {
                logger.warn("No answer after {}s", maxRingTime);
                sendCmd(proc, "/hangup");
                Thread.sleep(1000);
            }

            return false;

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } finally {
            shutdownBaresip(proc);
            reader.interrupt();
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Baresip Process Management
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Write baresip account and config files.
     *
     * @param audioSource Path to WAV file for alarm mode, or /dev/null for silent calls
     */
    private void writeBaresipConfig(String audioSource) {
        try {
            Files.createDirectories(BARESIP_DIR);

            String accountLine = String.format(
                    "<sip:%s@%s;transport=tcp>;auth_user=%s;auth_pass=%s;answermode=manual;regint=60", sipExtension,
                    sipServer, sipAuthUser, sipAuthPass);

            Files.writeString(BARESIP_DIR.resolve("accounts"), accountLine + "\n", StandardCharsets.UTF_8);

            String config = String.join("\n", "# baresip config — auto-generated by pbx3cx binding",
                    "audio_player aufile,/dev/null", "audio_source aufile," + audioSource,
                    "audio_alert aufile,/dev/null", "sip_listen 0.0.0.0:5063", "sip_trans_def tcp", "video_enable no",
                    "ausrc_srate 8000", "auplay_srate 8000", "ausrc_channels 1", "auplay_channels 1",
                    "module_path /usr/lib/baresip/modules", "module account.so", "module stdio.so", "module menu.so",
                    "module aufile.so", "module g711.so", "module uuid.so", "module stun.so", "");

            Files.writeString(BARESIP_DIR.resolve("config"), config, StandardCharsets.UTF_8);

            String mode = "/dev/null".equals(audioSource) ? "silent" : "alarm audio";
            logger.info("Baresip configured: ext {} -> {} ({})", sipExtension, sipServer, mode);

        } catch (IOException e) {
            logger.error("Failed to write baresip config: {}", e.getMessage());
        }
    }

    /**
     * Launch a new baresip subprocess.
     */
    private @Nullable Process startBaresip() {
        try {
            ProcessBuilder pb = new ProcessBuilder("baresip", "-v", "-f", BARESIP_DIR.toString());
            pb.redirectErrorStream(true);
            return pb.start();
        } catch (IOException e) {
            logger.error("Failed to start baresip: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Start a daemon thread that reads baresip stdout and feeds lines into a queue.
     */
    private Thread startOutputReader(Process proc, BlockingQueue<String> queue) {
        Thread thread = new Thread(() -> {
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(proc.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    String trimmed = line.trim();
                    if (!trimmed.isEmpty()) {
                        queue.offer(trimmed);
                    }
                }
            } catch (IOException e) {
                // Process ended — expected
            }
        }, "baresip-reader");
        thread.setDaemon(true);
        thread.start();
        return thread;
    }

    /**
     * Wait for SIP 200 OK registration response.
     */
    private boolean waitForRegistration(BlockingQueue<String> lines, int timeoutSeconds) {
        logger.info("Waiting for SIP registration...");
        long deadline = System.currentTimeMillis() + timeoutSeconds * 1000L;

        while (System.currentTimeMillis() < deadline) {
            try {
                String line = lines.poll(1, TimeUnit.SECONDS);
                if (line != null && line.contains("200 OK")) {
                    logger.info("SIP registered OK");
                    return true;
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }

        logger.error("SIP registration failed after {}s", timeoutSeconds);
        return false;
    }

    /**
     * Dial a destination and wait for the call to be bridged (answered).
     *
     * @return "bridged", "failed", or "timeout"
     */
    private String dialAndWaitBridge(Process proc, BlockingQueue<String> lines, String destination, int maxRing) {
        sendCmd(proc, "/dial " + destination);
        logger.info("Dial command sent: {}", destination);

        long deadline = System.currentTimeMillis() + maxRing * 1000L;

        while (System.currentTimeMillis() < deadline) {
            try {
                String line = lines.poll(1, TimeUnit.SECONDS);
                if (line == null) {
                    continue;
                }

                String ll = line.toLowerCase();

                if (ll.contains("call established") || ll.contains("call answered")) {
                    return "bridged";
                }

                if (containsAny(ll, "closed", "terminated", "failed", "rejected", "busy", "486", "603", "404", "408")) {
                    logger.warn("Call failed: {}", line);
                    return "failed";
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return "failed";
            }
        }

        return "timeout";
    }

    /**
     * Send a command to baresip via stdin.
     */
    private void sendCmd(Process proc, String cmd) {
        try {
            OutputStream os = proc.getOutputStream();
            os.write((cmd + "\n").getBytes(StandardCharsets.UTF_8));
            os.flush();
        } catch (IOException e) {
            // Process may have ended
        }
    }

    /**
     * Cleanly shut down baresip: send /quit, wait, then force-kill if needed.
     */
    private void shutdownBaresip(Process proc) {
        if (proc.isAlive()) {
            sendCmd(proc, "/quit");
            try {
                if (!proc.waitFor(5, TimeUnit.SECONDS)) {
                    logger.warn("Killing baresip (did not exit after /quit)");
                    proc.destroyForcibly();
                    proc.waitFor(3, TimeUnit.SECONDS);
                }
            } catch (InterruptedException e) {
                proc.destroyForcibly();
                Thread.currentThread().interrupt();
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Audio Utilities
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Create an extended WAV file by repeating the source audio with silence gaps.
     * <p>
     * baresip's aufile module plays a WAV once then stops RTP, which kills the call
     * after ~13s. We need continuous RTP for ~60s so the call survives past the mobile
     * ring timeout (~25-30s) and the recipient has time to answer and press DTMF.
     *
     * @param audioPath Source WAV file
     * @param totalDuration Target duration in seconds
     * @param gapDuration Silence gap between repeats in seconds
     * @return Path to extended WAV (temp file), or original path on failure
     */
    private Path createExtendedAudio(Path audioPath, int totalDuration, int gapDuration) {
        try {
            byte[] wavData = Files.readAllBytes(audioPath);
            if (wavData.length < 44) {
                logger.warn("WAV file too small ({}B), using original", wavData.length);
                return audioPath;
            }

            // Parse PCM WAV format fields
            int numChannels = readInt16LE(wavData, 22);
            int sampleRate = readInt32LE(wavData, 24);
            int bitsPerSample = readInt16LE(wavData, 34);
            int blockAlign = numChannels * (bitsPerSample / 8);
            int byteRate = sampleRate * blockAlign;

            // Scan for the data chunk (handles files with extra metadata chunks)
            int pos = 12; // skip RIFF + WAVE header
            int dataOffset = -1;
            int dataSize = 0;

            while (pos < wavData.length - 8) {
                String chunkId = new String(wavData, pos, 4, StandardCharsets.US_ASCII);
                int chunkSize = readInt32LE(wavData, pos + 4);

                if ("data".equals(chunkId)) {
                    dataOffset = pos + 8;
                    dataSize = Math.min(chunkSize, wavData.length - dataOffset);
                    break;
                }

                pos += 8 + chunkSize;
                if (chunkSize % 2 != 0) {
                    pos++; // RIFF word-alignment padding
                }
            }

            if (dataOffset < 0 || dataSize == 0) {
                logger.warn("No data chunk found in WAV, using original");
                return audioPath;
            }

            byte[] audioFrames = Arrays.copyOfRange(wavData, dataOffset, dataOffset + dataSize);
            byte[] gapBytes = new byte[gapDuration * byteRate]; // silence
            double frameDuration = (double) dataSize / byteRate;

            // Calculate total output data size
            int totalDataSize = 0;
            double elapsed = 0;
            while (elapsed < totalDuration) {
                totalDataSize += audioFrames.length;
                elapsed += frameDuration;
                if (elapsed < totalDuration) {
                    totalDataSize += gapBytes.length;
                    elapsed += gapDuration;
                }
            }

            // Write extended WAV to temp file
            Path extPath = Files.createTempFile("sip_alarm_", ".wav");

            try (OutputStream out = Files.newOutputStream(extPath)) {
                writeWavHeader(out, totalDataSize, numChannels, sampleRate, bitsPerSample);

                elapsed = 0;
                while (elapsed < totalDuration) {
                    out.write(audioFrames);
                    elapsed += frameDuration;
                    if (elapsed < totalDuration) {
                        out.write(gapBytes);
                        elapsed += gapDuration;
                    }
                }
            }

            logger.info("Extended audio: {}s from {}", String.format("%.1f", (double) totalDataSize / byteRate),
                    audioPath.getFileName());
            return extPath;

        } catch (Exception e) {
            logger.warn("Extended audio creation failed: {} (using original)", e.getMessage());
            return audioPath;
        }
    }

    /**
     * Write a standard PCM WAV header.
     */
    private static void writeWavHeader(OutputStream out, int dataSize, int channels, int sampleRate, int bitsPerSample)
            throws IOException {
        int byteRate = sampleRate * channels * (bitsPerSample / 8);
        int blockAlign = channels * (bitsPerSample / 8);
        int fileSize = 36 + dataSize;

        out.write("RIFF".getBytes(StandardCharsets.US_ASCII));
        writeInt32LE(out, fileSize);
        out.write("WAVE".getBytes(StandardCharsets.US_ASCII));
        out.write("fmt ".getBytes(StandardCharsets.US_ASCII));
        writeInt32LE(out, 16); // fmt chunk size
        writeInt16LE(out, 1); // PCM format
        writeInt16LE(out, channels);
        writeInt32LE(out, sampleRate);
        writeInt32LE(out, byteRate);
        writeInt16LE(out, blockAlign);
        writeInt16LE(out, bitsPerSample);
        out.write("data".getBytes(StandardCharsets.US_ASCII));
        writeInt32LE(out, dataSize);
    }

    /**
     * Create a WAV file containing silence for the specified duration.
     * <p>
     * baresip's aufile module requires a real WAV file to send proper RTP.
     * Using /dev/null causes baresip to fail opening the audio source, which means
     * no RTP is sent and the PBX may route the call incorrectly (e.g. to voicemail).
     *
     * @param durationSeconds Duration of silence in seconds
     * @return Path to the silence WAV temp file
     */
    private Path createSilenceWav(int durationSeconds) {
        try {
            int sampleRate = 8000;
            int channels = 1;
            int bitsPerSample = 16;
            int dataSize = durationSeconds * sampleRate * channels * (bitsPerSample / 8);

            Path silencePath = Files.createTempFile("sip_silence_", ".wav");

            try (OutputStream out = Files.newOutputStream(silencePath)) {
                writeWavHeader(out, dataSize, channels, sampleRate, bitsPerSample);
                // Write silence in 8KB chunks to avoid huge allocations
                byte[] chunk = new byte[8192];
                int remaining = dataSize;
                while (remaining > 0) {
                    int toWrite = Math.min(chunk.length, remaining);
                    out.write(chunk, 0, toWrite);
                    remaining -= toWrite;
                }
            }

            logger.debug("Created silence WAV: {}s ({}KB)", durationSeconds, dataSize / 1024);
            return silencePath;

        } catch (IOException e) {
            logger.error("Failed to create silence WAV: {}", e.getMessage());
            return Path.of("/dev/null"); // fallback
        }
    }

    /**
     * Delete a temporary file, ignoring errors.
     */
    private void deleteTempFile(Path path) {
        try {
            if (path != null && path.toString().contains("sip_")) {
                Files.deleteIfExists(path);
            }
        } catch (IOException e) {
            logger.debug("Failed to delete temp file {}: {}", path, e.getMessage());
        }
    }

    // ─── Byte Utilities ────────────────────────────────────────────────────

    private static int readInt16LE(byte[] data, int offset) {
        return (data[offset] & 0xFF) | ((data[offset + 1] & 0xFF) << 8);
    }

    private static int readInt32LE(byte[] data, int offset) {
        return (data[offset] & 0xFF) | ((data[offset + 1] & 0xFF) << 8) | ((data[offset + 2] & 0xFF) << 16)
                | ((data[offset + 3] & 0xFF) << 24);
    }

    private static void writeInt16LE(OutputStream out, int value) throws IOException {
        out.write(value & 0xFF);
        out.write((value >> 8) & 0xFF);
    }

    private static void writeInt32LE(OutputStream out, int value) throws IOException {
        out.write(value & 0xFF);
        out.write((value >> 8) & 0xFF);
        out.write((value >> 16) & 0xFF);
        out.write((value >> 24) & 0xFF);
    }

    // ─── String Utilities ──────────────────────────────────────────────────

    private static boolean containsAny(String text, String... words) {
        for (String word : words) {
            if (text.contains(word)) {
                return true;
            }
        }
        return false;
    }

    private static boolean containsAnyChar(String text, String chars) {
        for (int i = 0; i < chars.length(); i++) {
            if (text.indexOf(chars.charAt(i)) >= 0) {
                return true;
            }
        }
        return false;
    }
}
