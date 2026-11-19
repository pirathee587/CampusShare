package com.campusshare.config;

public final class Config {
    private Config() {}

    public static String value(String name, String fallback) {
        return System.getenv().getOrDefault("CAMPUSSHARE_" + name, fallback);
    }

    public static int number(String name, int fallback) {
        return Integer.parseInt(value(name, Integer.toString(fallback)));
    }

    public static int discoveryPort() { return number("UDP_PORT", 42000); }
    public static int tlsPort() { return number("TLS_PORT", 42001); }
    public static int eventPort() { return number("EVENT_PORT", 42002); }
    public static int agentPort() { return number("AGENT_PORT", 8085); }
    public static long maxFile() { return Long.parseLong(value("MAX_FILE_BYTES", "1073741824")); }
    public static int workers() { return number("WORKERS", 5); }
    public static int queue() { return number("QUEUE", 16); }
}
