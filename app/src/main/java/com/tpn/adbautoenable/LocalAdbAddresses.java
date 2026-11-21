package com.tpn.adbautoenable;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;

/** Numeric local-address matching. Never resolve advertised hostnames through DNS. */
final class LocalAdbAddresses {
    private LocalAdbAddresses() {}

    static String withoutScope(String address) {
        if (address == null) return null;
        int index = address.indexOf('%');
        return index < 0 ? address : address.substring(0, index);
    }

    private static byte[] numericBytes(String address) {
        if (address == null || address.isEmpty()) return null;
        int scope = address.indexOf('%');
        if (scope >= 0 && (scope == address.length() - 1
                || address.indexOf('%', scope + 1) >= 0)) return null;
        String literal = withoutScope(address);
        if (literal.indexOf(':') >= 0) {
            // A colon plus this restricted alphabet cannot be a DNS hostname.
            if (!literal.matches("[0-9a-fA-F:.]+")) return null;
            try { return InetAddress.getByName(literal).getAddress(); }
            catch (UnknownHostException e) { return null; }
        }
        if (scope >= 0) return null;
        String[] parts = literal.split("\\.", -1);
        if (parts.length != 4) return null;
        byte[] bytes = new byte[4];
        for (int i = 0; i < parts.length; i++) {
            String part = parts[i];
            if (!part.matches("[0-9]{1,3}") || (part.length() > 1 && part.charAt(0) == '0')) return null;
            int value = Integer.parseInt(part);
            if (value > 255) return null;
            bytes[i] = (byte) value;
        }
        return bytes;
    }

    static boolean matches(String host, Collection<String> localAddresses) {
        byte[] candidate = numericBytes(host);
        if (candidate == null || localAddresses == null) return false;
        for (String local : localAddresses) {
            byte[] address = numericBytes(local);
            // Scope identifies a connection interface, not the numeric address.
            if (address != null && Arrays.equals(candidate, address)) return true;
        }
        return false;
    }

    static List<String> candidates(String resolvedHost, Collection<String> lanAddresses) {
        LinkedHashSet<String> result = new LinkedHashSet<>();
        result.add("127.0.0.1");
        // Keep original scope information in every connection candidate.
        if (matches(resolvedHost, lanAddresses)) result.add(resolvedHost);
        if (lanAddresses != null) {
            for (String local : lanAddresses) if (numericBytes(local) != null) result.add(local);
        }
        return new ArrayList<>(result);
    }
}
