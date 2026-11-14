package com.fatoora.api.config;

import jakarta.servlet.http.HttpServletRequest;

import java.net.InetAddress;

/**
 * The visitor's real address. The API is never published: requests reach it only through the portal's proxy
 * (and, for production, Caddy before that), and each hop appends the address it saw to X-Forwarded-For.
 * The right-most public address in that list is the one our own proxies recorded, so a client can't spoof it
 * by sending its own X-Forwarded-For (those entries end up further left).
 */
public final class ClientIp {

    private ClientIp() {}

    public static String of(HttpServletRequest req) {
        String xff = req.getHeader("X-Forwarded-For");
        if (xff != null && !xff.isBlank()) {
            String[] hops = xff.split(",");
            for (int i = hops.length - 1; i >= 0; i--) {
                String ip = hops[i].trim();
                if (!ip.isEmpty() && !internal(ip)) return ip;
            }
        }
        return req.getRemoteAddr();
    }

    /** Docker network, loopback and private ranges: our own proxies, never a visitor. */
    static boolean internal(String ip) {
        if (!ip.matches("[0-9a-fA-F:.]+")) return true; // not an address (no DNS lookups here)
        try {
            InetAddress a = InetAddress.getByName(ip);
            return a.isLoopbackAddress() || a.isSiteLocalAddress() || a.isLinkLocalAddress() || a.isAnyLocalAddress()
                    || ip.toLowerCase().startsWith("fc") || ip.toLowerCase().startsWith("fd");
        } catch (Exception e) {
            return true;
        }
    }
}
