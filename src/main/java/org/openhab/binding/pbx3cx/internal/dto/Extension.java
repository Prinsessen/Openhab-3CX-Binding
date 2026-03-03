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
 * Data transfer object for a 3CX user/extension from xAPI Users endpoint.
 *
 * @author openHAB Community - Initial contribution
 */
@NonNullByDefault
public class Extension {
    private @Nullable String number;
    private @Nullable String firstName;
    private @Nullable String lastName;
    private @Nullable String currentProfileName;
    private @Nullable String queueStatus;
    private boolean isRegistered;

    public @Nullable String getNumber() {
        return number;
    }

    public @Nullable String getFirstName() {
        return firstName;
    }

    public @Nullable String getLastName() {
        return lastName;
    }

    public String getDisplayName() {
        String first = firstName != null ? firstName : "";
        String last = lastName != null ? lastName : "";
        return (first + " " + last).trim();
    }

    public @Nullable String getCurrentProfileName() {
        return currentProfileName;
    }

    public @Nullable String getQueueStatus() {
        return queueStatus;
    }

    public boolean isRegistered() {
        return isRegistered;
    }

    // Setters for Gson (field names match xAPI JSON: Number, FirstName, etc.)
    public void setNumber(@Nullable String number) {
        this.number = number;
    }

    public void setFirstName(@Nullable String firstName) {
        this.firstName = firstName;
    }

    public void setLastName(@Nullable String lastName) {
        this.lastName = lastName;
    }

    public void setCurrentProfileName(@Nullable String currentProfileName) {
        this.currentProfileName = currentProfileName;
    }

    public void setQueueStatus(@Nullable String queueStatus) {
        this.queueStatus = queueStatus;
    }

    public void setIsRegistered(boolean isRegistered) {
        this.isRegistered = isRegistered;
    }
}
