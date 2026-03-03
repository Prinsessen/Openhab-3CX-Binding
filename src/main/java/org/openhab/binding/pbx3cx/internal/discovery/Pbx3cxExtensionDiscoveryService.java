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
package org.openhab.binding.pbx3cx.internal.discovery;

import java.util.Map;
import java.util.Set;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.openhab.binding.pbx3cx.internal.Pbx3cxBindingConstants;
import org.openhab.binding.pbx3cx.internal.Pbx3cxServerHandler;
import org.openhab.core.config.discovery.AbstractDiscoveryService;
import org.openhab.core.config.discovery.DiscoveryResult;
import org.openhab.core.config.discovery.DiscoveryResultBuilder;
import org.openhab.core.config.discovery.DiscoveryService;
import org.openhab.core.thing.ThingUID;
import org.openhab.core.thing.binding.ThingHandler;
import org.openhab.core.thing.binding.ThingHandlerService;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.ServiceScope;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Discovery service for 3CX extensions.
 * <p>
 * When a scan is initiated, queries the bridge's cached extension states
 * (populated by presence polling) and creates discovery results for each.
 *
 * @author openHAB Community - Initial contribution
 */
@Component(service = { DiscoveryService.class,
        ThingHandlerService.class }, configurationPid = "binding.pbx3cx", property = {
                "service.pid=org.openhab.binding.pbx3cx.discovery",
                "openhab.discovery.binding=" + Pbx3cxBindingConstants.BINDING_ID }, scope = ServiceScope.PROTOTYPE)
@NonNullByDefault
public class Pbx3cxExtensionDiscoveryService extends AbstractDiscoveryService implements ThingHandlerService {

    private final Logger logger = LoggerFactory.getLogger(Pbx3cxExtensionDiscoveryService.class);
    private static final int SEARCH_TIME = 10;

    private @Nullable Pbx3cxServerHandler bridgeHandler;

    public Pbx3cxExtensionDiscoveryService() {
        super(Set.of(Pbx3cxBindingConstants.THING_TYPE_EXTENSION, Pbx3cxBindingConstants.THING_TYPE_RINGGROUP,
                Pbx3cxBindingConstants.THING_TYPE_QUEUE), SEARCH_TIME, true);
    }

    @Override
    protected void startBackgroundDiscovery() {
        startScan();
    }

    @Override
    protected void stopBackgroundDiscovery() {
        // No background resources to clean up
    }

    @Override
    protected void startScan() {
        logger.debug("Starting 3CX extension discovery");

        Pbx3cxServerHandler handler = bridgeHandler;
        if (handler == null) {
            logger.warn("No bridge handler set; cannot discover extensions");
            return;
        }

        Map<String, Pbx3cxServerHandler.ExtensionState> extensions = handler.getExtensionStates();

        if (extensions.isEmpty()) {
            logger.info("No extension data available yet — presence poll may not have run. Try again in a moment.");
            return;
        }

        ThingUID bridgeUID = handler.getThing().getUID();

        for (Pbx3cxServerHandler.ExtensionState ext : extensions.values()) {
            String extNum = ext.number;
            String label = String.format("3CX Ext %s %s", extNum, ext.name).trim();

            ThingUID thingUID = new ThingUID(Pbx3cxBindingConstants.THING_TYPE_EXTENSION, bridgeUID, "ext" + extNum);

            DiscoveryResult result = DiscoveryResultBuilder.create(thingUID).withBridge(bridgeUID).withLabel(label)
                    .withProperty("extensionNumber", extNum).withRepresentationProperty("extensionNumber").build();

            thingDiscovered(result);
            logger.info("Discovered extension: {} ({})", extNum, ext.name);
        }

        logger.info("Discovery scan completed: {} extensions found", extensions.size());

        // Discover ring groups
        Map<String, Pbx3cxServerHandler.RingGroupState> ringGroups = handler.getRingGroupStates();
        for (Pbx3cxServerHandler.RingGroupState rg : ringGroups.values()) {
            String rgNum = rg.number;
            String label = String.format("3CX Ring Group %s %s", rgNum, rg.name).trim();

            ThingUID thingUID = new ThingUID(Pbx3cxBindingConstants.THING_TYPE_RINGGROUP, bridgeUID, "rg" + rgNum);

            DiscoveryResult result = DiscoveryResultBuilder.create(thingUID).withBridge(bridgeUID).withLabel(label)
                    .withProperty("ringGroupNumber", rgNum).withRepresentationProperty("ringGroupNumber").build();

            thingDiscovered(result);
            logger.info("Discovered ring group: {} ({})", rgNum, rg.name);
        }

        // Discover queues
        Map<String, Pbx3cxServerHandler.QueueState> queues = handler.getQueueStates();
        for (Pbx3cxServerHandler.QueueState q : queues.values()) {
            String qNum = q.number;
            String label = String.format("3CX Queue %s %s", qNum, q.name).trim();

            ThingUID thingUID = new ThingUID(Pbx3cxBindingConstants.THING_TYPE_QUEUE, bridgeUID, "q" + qNum);

            DiscoveryResult result = DiscoveryResultBuilder.create(thingUID).withBridge(bridgeUID).withLabel(label)
                    .withProperty("queueNumber", qNum).withRepresentationProperty("queueNumber").build();

            thingDiscovered(result);
            logger.info("Discovered queue: {} ({})", qNum, q.name);
        }

        logger.info("Full discovery completed: {} extensions, {} ring groups, {} queues", extensions.size(),
                ringGroups.size(), queues.size());
    }

    @Override
    public void setThingHandler(ThingHandler thingHandler) {
        if (thingHandler instanceof Pbx3cxServerHandler handler) {
            this.bridgeHandler = handler;
        }
    }

    @Override
    public @Nullable ThingHandler getThingHandler() {
        return bridgeHandler;
    }

    @Override
    public void deactivate() {
        bridgeHandler = null;
        super.deactivate();
    }
}
