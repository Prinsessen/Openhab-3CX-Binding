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
 * Handler for a 3CX Ring Group thing.
 *
 * @author openHAB Community - Initial contribution
 */
@NonNullByDefault
public class Pbx3cxRingGroupHandler extends BaseThingHandler {

    private final Logger logger = LoggerFactory.getLogger(Pbx3cxRingGroupHandler.class);
    private String ringGroupNumber = "";

    public Pbx3cxRingGroupHandler(Thing thing) {
        super(thing);
    }

    @Override
    public void initialize() {
        Pbx3cxRingGroupConfiguration config = getConfigAs(Pbx3cxRingGroupConfiguration.class);

        if (config.ringGroupNumber.isEmpty()) {
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.CONFIGURATION_ERROR, "Ring group number is required");
            return;
        }

        this.ringGroupNumber = config.ringGroupNumber;
        updateStatus(ThingStatus.UNKNOWN, ThingStatusDetail.NONE, "Waiting for ring group data from PBX");
    }

    @Override
    public void handleCommand(ChannelUID channelUID, Command command) {
        // Ring group channels are read-only
    }

    /**
     * Update ring group state from server handler polling.
     */
    public void updateRingGroupState(Pbx3cxServerHandler.RingGroupState state) {
        updateState(CHANNEL_RG_NAME, new StringType(state.name));
        updateState(CHANNEL_RG_STRATEGY, new StringType(state.strategy));
        updateState(CHANNEL_RG_MEMBERS, new StringType(state.members));
        updateState(CHANNEL_RG_MEMBER_COUNT, new DecimalType(state.memberCount));
        updateState(CHANNEL_RG_REGISTERED, state.registered ? OnOffType.ON : OnOffType.OFF);

        if (getThing().getStatus() != ThingStatus.ONLINE) {
            updateStatus(ThingStatus.ONLINE);
        }
    }

    public String getRingGroupNumber() {
        return ringGroupNumber;
    }
}
