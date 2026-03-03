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
 * Data transfer object for an active call from xAPI.
 *
 * @author openHAB Community - Initial contribution
 */
@NonNullByDefault
public class ActiveCall {
    private int id;
    private @Nullable String caller;
    private @Nullable String callee;
    private @Nullable String status;
    private @Nullable String establishedAt;

    public int getId() {
        return id;
    }

    public @Nullable String getCaller() {
        return caller;
    }

    public @Nullable String getCallee() {
        return callee;
    }

    public @Nullable String getStatus() {
        return status;
    }

    public @Nullable String getEstablishedAt() {
        return establishedAt;
    }

    // Setters for Gson deserialization
    public void setId(int id) {
        this.id = id;
    }

    public void setCaller(@Nullable String caller) {
        this.caller = caller;
    }

    public void setCallee(@Nullable String callee) {
        this.callee = callee;
    }

    public void setStatus(@Nullable String status) {
        this.status = status;
    }

    public void setEstablishedAt(@Nullable String establishedAt) {
        this.establishedAt = establishedAt;
    }

    @Override
    public String toString() {
        return String.format("ActiveCall[id=%d, caller=%s, callee=%s, status=%s]", id, caller, callee, status);
    }
}
