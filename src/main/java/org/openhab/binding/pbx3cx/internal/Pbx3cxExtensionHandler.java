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
 * The {@link Pbx3cxExtensionHandler} handles a single 3CX extension.
 * <p>
 * It receives state updates from the bridge handler's presence poller
 * and updates its channels accordingly.
 *
 * @author openHAB Community - Initial contribution
 */
@NonNullByDefault
public class Pbx3cxExtensionHandler extends BaseThingHandler {

    private final Logger logger = LoggerFactory.getLogger(Pbx3cxExtensionHandler.class);
    private String extensionNumber = "";

    public Pbx3cxExtensionHandler(Thing thing) {
        super(thing);
    }

    @Override
    public void initialize() {
        Pbx3cxExtensionConfiguration config = getConfigAs(Pbx3cxExtensionConfiguration.class);

        if (config.extensionNumber.isEmpty()) {
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.CONFIGURATION_ERROR, "Extension number is required");
            return;
        }

        this.extensionNumber = config.extensionNumber;

        // We go ONLINE once the bridge gives us a status update
        updateStatus(ThingStatus.UNKNOWN, ThingStatusDetail.NONE, "Waiting for presence data from PBX");
    }

    @Override
    public void handleCommand(ChannelUID channelUID, Command command) {
        // Extension channels are read-only
    }

    /**
     * Called by the bridge handler when this extension's presence changes.
     */
    public void updateExtensionState(Pbx3cxServerHandler.ExtensionState state) {
        updateState(CHANNEL_EXT_STATUS, new StringType(state.status));
        updateState(CHANNEL_EXT_PRESENCE, new StringType(state.profile));
        updateState(CHANNEL_EXT_NAME, new StringType(state.name));
        updateState(CHANNEL_EXT_REGISTERED, state.registered ? OnOffType.ON : OnOffType.OFF);

        if (getThing().getStatus() != ThingStatus.ONLINE) {
            updateStatus(ThingStatus.ONLINE);
        }
    }

    public String getExtensionNumber() {
        return extensionNumber;
    }
}
