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

import java.io.IOException;
import java.io.PrintWriter;
import java.util.HashMap;
import java.util.Map;
import java.util.stream.Collectors;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * The {@link Pbx3cxWebhookServlet} receives incoming webhook calls from 3CX
 * CRM Integration and Call Flow Designer (CFD) voice apps.
 * <p>
 * Registered at /pbx3cx/* and forwards events to the bridge handler.
 * <p>
 * Endpoints:
 * <ul>
 * <li>POST /pbx3cx/ringing - incoming call ringing (CFD)</li>
 * <li>POST /pbx3cx/answered - call answered (CFD)</li>
 * <li>POST /pbx3cx/hangup - call ended (CFD/CRM)</li>
 * <li>POST /pbx3cx/callinfo - post-call journaling (CRM)</li>
 * <li>POST /pbx3cx/outgoing - outbound call started (CFD)</li>
 * <li>POST /pbx3cx/lookup - CRM lookup (instant ringing)</li>
 * <li>POST /pbx3cx/reset_missed - reset missed counter</li>
 * <li>GET /pbx3cx/status - health check</li>
 * </ul>
 *
 * @author openHAB Community - Initial contribution
 */
@NonNullByDefault
public class Pbx3cxWebhookServlet extends HttpServlet {

    private static final long serialVersionUID = 1L;
    private final Logger logger = LoggerFactory.getLogger(Pbx3cxWebhookServlet.class);

    private @Nullable Pbx3cxServerHandler bridgeHandler;

    public void setBridgeHandler(@Nullable Pbx3cxServerHandler handler) {
        this.bridgeHandler = handler;
    }

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
        String path = getEventPath(req);

        if ("status".equals(path)) {
            resp.setContentType("application/json");
            resp.setStatus(HttpServletResponse.SC_OK);
            PrintWriter out = resp.getWriter();
            out.print("{\"status\":\"ok\",\"service\":\"3CX PBX Binding Webhook\"}");
            out.flush();
            return;
        }

        resp.setStatus(HttpServletResponse.SC_NOT_FOUND);
    }

    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
        String path = getEventPath(req);

        // Add CORS headers
        resp.setHeader("Access-Control-Allow-Origin", "*");
        resp.setHeader("Access-Control-Allow-Methods", "GET, POST, OPTIONS");
        resp.setHeader("Access-Control-Allow-Headers", "Content-Type");
        resp.setContentType("application/json");

        Pbx3cxServerHandler handler = bridgeHandler;
        if (handler == null) {
            resp.setStatus(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
            resp.getWriter().print("{\"error\":\"Bridge handler not available\"}");
            return;
        }

        // Parse request body
        Map<String, String> data = parseRequestBody(req);

        logger.debug("Webhook received: {} with {} fields", path, data.size());

        try {
            handler.handleWebhookEvent(path, data);
            resp.setStatus(HttpServletResponse.SC_OK);
            resp.getWriter().print("{\"status\":\"ok\",\"event\":\"" + path + "\"}");
        } catch (Exception e) {
            logger.error("Error processing webhook {}: {}", path, e.getMessage());
            resp.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
            resp.getWriter().print("{\"error\":\"" + e.getMessage() + "\"}");
        }
    }

    @Override
    protected void doOptions(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
        resp.setHeader("Access-Control-Allow-Origin", "*");
        resp.setHeader("Access-Control-Allow-Methods", "GET, POST, OPTIONS");
        resp.setHeader("Access-Control-Allow-Headers", "Content-Type");
        resp.setStatus(HttpServletResponse.SC_OK);
    }

    /**
     * Extract the event name from the request path.
     * e.g. /pbx3cx/ringing → ringing
     */
    private String getEventPath(HttpServletRequest req) {
        String pathInfo = req.getPathInfo();
        if (pathInfo != null) {
            // Remove leading slash
            return pathInfo.startsWith("/") ? pathInfo.substring(1) : pathInfo;
        }
        // Fallback: parse from servlet path
        String uri = req.getRequestURI();
        int lastSlash = uri.lastIndexOf('/');
        return lastSlash >= 0 ? uri.substring(lastSlash + 1) : uri;
    }

    /**
     * Parse request body from JSON or form data into a flat Map.
     */
    private Map<String, String> parseRequestBody(HttpServletRequest req) {
        Map<String, String> result = new HashMap<>();

        try {
            String contentType = req.getContentType();

            if (contentType != null && contentType.contains("application/json")) {
                // Parse JSON body
                String body = req.getReader().lines().collect(Collectors.joining());
                if (!body.isEmpty()) {
                    try {
                        JsonObject json = JsonParser.parseString(body).getAsJsonObject();
                        for (Map.Entry<String, JsonElement> entry : json.entrySet()) {
                            JsonElement val = entry.getValue();
                            if (val.isJsonPrimitive()) {
                                result.put(entry.getKey(), val.getAsString());
                            }
                        }
                    } catch (Exception e) {
                        logger.debug("Failed to parse JSON body: {}", e.getMessage());
                    }
                }
            } else {
                // Form-encoded or query parameters
                for (Map.Entry<String, String[]> entry : req.getParameterMap().entrySet()) {
                    String[] values = entry.getValue();
                    if (values.length > 0) {
                        result.put(entry.getKey(), values[0]);
                    }
                }
            }

            // Also include query parameters
            if (req.getQueryString() != null) {
                for (Map.Entry<String, String[]> entry : req.getParameterMap().entrySet()) {
                    if (!result.containsKey(entry.getKey())) {
                        String[] values = entry.getValue();
                        if (values.length > 0) {
                            result.put(entry.getKey(), values[0]);
                        }
                    }
                }
            }
        } catch (Exception e) {
            logger.debug("Error parsing request body: {}", e.getMessage());
        }

        return result;
    }
}
