package com.tpn.adbautoenable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;

/** Pure address selection logic, independent of Android APIs. */
final class LocalAdbAddresses {
    private LocalAdbAddresses() {}

    static String withoutScope(String address) {
        if (address == null) return null;
        int index = address.indexOf('%');
        return index < 0 ? address : address.substring(0, index);
    }

    static boolean matches(String host, Collection<String> localAddresses) {
        if (host == null || host.isEmpty()) return false;
        for (String local : localAddresses) {
            if (withoutScope(host).equalsIgnoreCase(withoutScope(local))) return true;
        }
        return false;
    }

    static List<String> candidates(String resolvedHost, Collection<String> lanAddresses) {
        LinkedHashSet<String> result = new LinkedHashSet<>();
        result.add("127.0.0.1");
        // Preserve IPv6 scopes for connecting; remove them only when comparing.
        if (matches(resolvedHost, lanAddresses)) result.add(resolvedHost);
        result.addAll(lanAddresses);
        return new ArrayList<>(result);
    }
}
