package com.tpn.adbautoenable;

/** Immutable host and port, published together across discovery threads. */
final class AdbEndpoint {
    final String host;
    final int port;

    AdbEndpoint(String host, int port) {
        if (host == null || host.isEmpty() || port < 1 || port > 65535) {
            throw new IllegalArgumentException("Invalid ADB endpoint");
        }
        this.host = host;
        this.port = port;
    }

    @Override public String toString() {
        return (host.contains(":") ? "[" + host + "]" : host) + ":" + port;
    }
}
