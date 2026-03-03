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

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.openhab.core.library.types.DecimalType;
import org.openhab.core.library.types.OnOffType;
import org.openhab.core.library.types.StringType;
import org.openhab.core.thing.ChannelUID;
import org.openhab.core.thing.Thing;
import org.openhab.core.thing.ThingStatus;
import org.openhab.core.thing.ThingStatusDetail;
import org.openhab.core.thing.binding.BaseThingHandler;
import org.openhab.core.types.Command;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Handler for a 3CX Call Queue thing.
 *
 * @author openHAB Community - Initial contribution
 */
@NonNullByDefault
public class Pbx3cxQueueHandler extends BaseThingHandler {

    private final Logger logger = LoggerFactory.getLogger(Pbx3cxQueueHandler.class);
    private String queueNumber = "";

    public Pbx3cxQueueHandler(Thing thing) {
        super(thing);
    }

    @Override
    public void initialize() {
        Pbx3cxQueueConfiguration config = getConfigAs(Pbx3cxQueueConfiguration.class);

        if (config.queueNumber.isEmpty()) {
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.CONFIGURATION_ERROR, "Queue number is required");
            return;
        }

        this.queueNumber = config.queueNumber;
        updateStatus(ThingStatus.UNKNOWN, ThingStatusDetail.NONE, "Waiting for queue data from PBX");
    }

    @Override
    public void handleCommand(ChannelUID channelUID, Command command) {
        // Queue channels are read-only
    }

    /**
     * Update queue state from server handler polling.
     */
    public void updateQueueState(Pbx3cxServerHandler.QueueState state) {
        updateState(CHANNEL_Q_NAME, new StringType(state.name));
        updateState(CHANNEL_Q_STRATEGY, new StringType(state.strategy));
        updateState(CHANNEL_Q_AGENTS, new StringType(state.agents));
        updateState(CHANNEL_Q_AGENT_COUNT, new DecimalType(state.agentCount));
        updateState(CHANNEL_Q_WAITING_CALLERS, new DecimalType(state.waitingCallers));
        updateState(CHANNEL_Q_REGISTERED, state.registered ? OnOffType.ON : OnOffType.OFF);

        if (getThing().getStatus() != ThingStatus.ONLINE) {
            updateStatus(ThingStatus.ONLINE);
        }
    }

    public String getQueueNumber() {
        return queueNumber;
    }
}
