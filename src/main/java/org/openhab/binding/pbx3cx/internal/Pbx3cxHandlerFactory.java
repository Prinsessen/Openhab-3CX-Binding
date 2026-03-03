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

import java.util.Set;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.openhab.core.thing.Bridge;
import org.openhab.core.thing.Thing;
import org.openhab.core.thing.ThingTypeUID;
import org.openhab.core.thing.binding.BaseThingHandlerFactory;
import org.openhab.core.thing.binding.ThingHandler;
import org.openhab.core.thing.binding.ThingHandlerFactory;
import org.osgi.service.component.annotations.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The {@link Pbx3cxHandlerFactory} creates handlers for 3CX PBX things.
 *
 * @author openHAB Community - Initial contribution
 */
@Component(service = ThingHandlerFactory.class, configurationPid = "binding.pbx3cx")
@NonNullByDefault
public class Pbx3cxHandlerFactory extends BaseThingHandlerFactory {

    private final Logger logger = LoggerFactory.getLogger(Pbx3cxHandlerFactory.class);

    private static final Set<ThingTypeUID> SUPPORTED_THING_TYPES = Set.of(Pbx3cxBindingConstants.THING_TYPE_SERVER,
            Pbx3cxBindingConstants.THING_TYPE_EXTENSION, Pbx3cxBindingConstants.THING_TYPE_RINGGROUP,
            Pbx3cxBindingConstants.THING_TYPE_QUEUE);

    @Override
    public boolean supportsThingType(ThingTypeUID thingTypeUID) {
        return SUPPORTED_THING_TYPES.contains(thingTypeUID);
    }

    @Override
    protected @Nullable ThingHandler createHandler(Thing thing) {
        ThingTypeUID thingTypeUID = thing.getThingTypeUID();

        if (Pbx3cxBindingConstants.THING_TYPE_SERVER.equals(thingTypeUID)) {
            logger.debug("Creating 3CX PBX Server handler");
            return new Pbx3cxServerHandler((Bridge) thing);
        } else if (Pbx3cxBindingConstants.THING_TYPE_EXTENSION.equals(thingTypeUID)) {
            logger.debug("Creating 3CX Extension handler");
            return new Pbx3cxExtensionHandler(thing);
        } else if (Pbx3cxBindingConstants.THING_TYPE_RINGGROUP.equals(thingTypeUID)) {
            logger.debug("Creating 3CX Ring Group handler");
            return new Pbx3cxRingGroupHandler(thing);
        } else if (Pbx3cxBindingConstants.THING_TYPE_QUEUE.equals(thingTypeUID)) {
            logger.debug("Creating 3CX Queue handler");
            return new Pbx3cxQueueHandler(thing);
        }

        return null;
    }
}
