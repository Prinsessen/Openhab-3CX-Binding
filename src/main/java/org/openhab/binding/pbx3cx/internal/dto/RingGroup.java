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

import java.util.ArrayList;
import java.util.List;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;

/**
 * DTO for a 3CX Ring Group.
 *
 * @author openHAB Community - Initial contribution
 */
@NonNullByDefault
public class RingGroup {
    private int id;
    private @Nullable String number;
    private @Nullable String name;
    private @Nullable String ringStrategy;
    private boolean isRegistered;
    private int ringTime;
    private List<Member> members = new ArrayList<>();

    public int getId() {
        return id;
    }

    public @Nullable String getNumber() {
        return number;
    }

    public @Nullable String getName() {
        return name;
    }

    public @Nullable String getRingStrategy() {
        return ringStrategy;
    }

    public boolean isRegistered() {
        return isRegistered;
    }

    public int getRingTime() {
        return ringTime;
    }

    public List<Member> getMembers() {
        return members;
    }

    public void setId(int id) {
        this.id = id;
    }

    public void setNumber(@Nullable String number) {
        this.number = number;
    }

    public void setName(@Nullable String name) {
        this.name = name;
    }

    public void setRingStrategy(@Nullable String ringStrategy) {
        this.ringStrategy = ringStrategy;
    }

    public void setIsRegistered(boolean isRegistered) {
        this.isRegistered = isRegistered;
    }

    public void setRingTime(int ringTime) {
        this.ringTime = ringTime;
    }

    public void setMembers(List<Member> members) {
        this.members = members;
    }

    /**
     * A member of a ring group.
     */
    @NonNullByDefault
    public static class Member {
        private @Nullable String number;
        private @Nullable String name;

        public @Nullable String getNumber() {
            return number;
        }

        public @Nullable String getName() {
            return name;
        }

        public void setNumber(@Nullable String number) {
            this.number = number;
        }

        public void setName(@Nullable String name) {
            this.name = name;
        }
    }
}
