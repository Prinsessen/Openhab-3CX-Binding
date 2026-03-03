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

import org.eclipse.jdt.annotation.NonNullByDefault;

/**
 * Configuration class for the 3CX Server bridge.
 *
 * @author openHAB Community - Initial contribution
 */
@NonNullByDefault
public class Pbx3cxServerConfiguration {
    public String hostname = "";
    public int port = 5001;
    public String username = "";
    public String password = "";
    public int pollInterval = 2;
    public int presenceInterval = 30;
    public int trunkInterval = 60;
    public boolean verifySsl = false;
    public int webhookPort = 5002;
    public int recentCallsMax = 10;
    public int recentMissedCallsMax = 10;
}
