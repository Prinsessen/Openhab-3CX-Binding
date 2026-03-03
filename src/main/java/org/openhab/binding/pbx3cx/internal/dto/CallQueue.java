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
 * DTO for a 3CX Call Queue.
 *
 * @author openHAB Community - Initial contribution
 */
@NonNullByDefault
public class CallQueue {
    private int id;
    private @Nullable String number;
    private @Nullable String name;
    private @Nullable String pollingStrategy;
    private boolean isRegistered;
    private int ringTimeout;
    private int masterTimeout;
    private List<Agent> agents = new ArrayList<>();

    public int getId() {
        return id;
    }

    public @Nullable String getNumber() {
        return number;
    }

    public @Nullable String getName() {
        return name;
    }

    public @Nullable String getPollingStrategy() {
        return pollingStrategy;
    }

    public boolean isRegistered() {
        return isRegistered;
    }

    public int getRingTimeout() {
        return ringTimeout;
    }

    public int getMasterTimeout() {
        return masterTimeout;
    }

    public List<Agent> getAgents() {
        return agents;
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

    public void setPollingStrategy(@Nullable String pollingStrategy) {
        this.pollingStrategy = pollingStrategy;
    }

    public void setIsRegistered(boolean isRegistered) {
        this.isRegistered = isRegistered;
    }

    public void setRingTimeout(int ringTimeout) {
        this.ringTimeout = ringTimeout;
    }

    public void setMasterTimeout(int masterTimeout) {
        this.masterTimeout = masterTimeout;
    }

    public void setAgents(List<Agent> agents) {
        this.agents = agents;
    }

    /**
     * An agent in a call queue.
     */
    @NonNullByDefault
    public static class Agent {
        private @Nullable String number;
        private @Nullable String name;
        private @Nullable String skillGroup;

        public @Nullable String getNumber() {
            return number;
        }

        public @Nullable String getName() {
            return name;
        }

        public @Nullable String getSkillGroup() {
            return skillGroup;
        }

        public void setNumber(@Nullable String number) {
            this.number = number;
        }

        public void setName(@Nullable String name) {
            this.name = name;
        }

        public void setSkillGroup(@Nullable String skillGroup) {
            this.skillGroup = skillGroup;
        }
    }
}
