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
package org.openhab.binding.pbx3cx.internal.dto;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;

/**
 * Parsed caller info: extension number and display name extracted from
 * the xAPI Caller/Callee string (e.g. "200 John Doe").
 *
 * @author openHAB Community - Initial contribution
 */
@NonNullByDefault
public class CallerInfo {
    private final String extension;
    private final String name;

    public CallerInfo(String extension, String name) {
        this.extension = extension;
        this.name = name;
    }

    public String getExtension() {
        return extension;
    }

    public String getName() {
        return name;
    }

    /**
     * Parse a raw caller/callee string like "200 John Doe" or "+4512345678 Company".
     */
    public static CallerInfo parse(@Nullable String raw) {
        if (raw == null || raw.isBlank()) {
            return new CallerInfo("", "");
        }
        String trimmed = raw.trim();
        // Pattern: "number name" or just "number"
        int spaceIdx = trimmed.indexOf(' ');
        if (spaceIdx > 0) {
            String num = trimmed.substring(0, spaceIdx);
            String name = trimmed.substring(spaceIdx + 1).trim();
            if (num.matches("[\\d+]+")) {
                return new CallerInfo(num, name);
            }
        }
        if (trimmed.matches("[\\d+]+")) {
            return new CallerInfo(trimmed, "");
        }
        return new CallerInfo("", trimmed);
    }

    /**
     * Check if a phone number looks like an internal extension (2-4 digits).
     */
    public static boolean isInternal(String number) {
        return number.matches("\\d{2,4}");
    }

    /**
     * Check if this caller/callee is a trunk (5-digit number with name containing "Trunk").
     * 3CX trunk callees look like: "10002 Provider Trunk (12345678)".
     */
    public boolean isTrunk() {
        return name.toLowerCase().contains("trunk") || extension.matches("\\d{5,}");
    }

    /**
     * Get display number: for trunks show name (provider info), for normal callers show extension.
     */
    public String getDisplayNumber() {
        if (isTrunk() && !name.isEmpty()) {
            return name;
        }
        return extension;
    }

    /**
     * For trunk callers, extract the external phone number from the trunk name.
     * E.g., "Provider Trunk (52517335)" → "52517335".
     * Falls back to extension if pattern doesn't match.
     */
    public String getExternalNumber() {
        if (isTrunk()) {
            int lastOpen = name.lastIndexOf('(');
            int lastClose = name.lastIndexOf(')');
            if (lastOpen >= 0 && lastClose > lastOpen) {
                String num = name.substring(lastOpen + 1, lastClose).trim();
                if (num.matches("[\\d+]+")) {
                    return num;
                }
            }
        }
        return extension;
    }

    /**
     * For trunk callers, extract just the provider name without the phone number.
     * E.g., "Provider Trunk (52517335)" → "Provider".
     * Falls back to full name.
     */
    public String getProviderName() {
        if (isTrunk()) {
            int trunkIdx = name.toLowerCase().indexOf("trunk");
            if (trunkIdx > 0) {
                return name.substring(0, trunkIdx).trim();
            }
        }
        return name;
    }

    /**
     * Check if this represents a ROUTER placeholder (used by 3CX during ring-group/queue routing).
     */
    public boolean isRouter() {
        return extension.isEmpty() && "ROUTER".equalsIgnoreCase(name);
    }

    /**
     * Check if this represents a VoiceMail destination.
     * E.g., "VoiceMail of 200 John Doe".
     */
    public boolean isVoiceMail() {
        return name.toLowerCase().startsWith("voicemail of ");
    }

    /**
     * For VoiceMail callees, extract the extension number.
     * E.g., "VoiceMail of 200 John Doe" → "200".
     */
    public String getVoiceMailExtension() {
        if (isVoiceMail()) {
            String after = name.substring("voicemail of ".length()).trim();
            int sp = after.indexOf(' ');
            String num = sp > 0 ? after.substring(0, sp) : after;
            if (num.matches("\\d{2,4}")) {
                return num;
            }
        }
        return extension;
    }

    /**
     * For VoiceMail callees, extract the person name.
     * E.g., "VoiceMail of 200 John Doe" → "John Doe".
     */
    public String getVoiceMailName() {
        if (isVoiceMail()) {
            String after = name.substring("voicemail of ".length()).trim();
            int sp = after.indexOf(' ');
            if (sp > 0) {
                return after.substring(sp + 1).trim();
            }
        }
        return name;
    }

    /**
     * Determine call direction based on caller and callee extensions.
     */
    public static String determineDirection(String callerExt, String calleeExt) {
        boolean callerInternal = isInternal(callerExt);
        boolean calleeInternal = isInternal(calleeExt);
        if (callerInternal && calleeInternal) {
            return "internal";
        } else if (callerInternal) {
            return "outbound";
        } else if (calleeInternal) {
            return "inbound";
        }
        return "external";
    }
}
