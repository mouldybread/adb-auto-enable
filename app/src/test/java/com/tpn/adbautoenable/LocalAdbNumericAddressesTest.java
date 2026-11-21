package com.tpn.adbautoenable;

import org.junit.Test;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import static org.junit.Assert.*;

public class LocalAdbNumericAddressesTest {
    @Test public void equivalentIpv6RepresentationsMatch() {
        assertTrue(LocalAdbAddresses.matches("2001:DB8::1",
                Collections.singletonList("2001:db8:0:0:0:0:0:1")));
    }

    @Test public void scopeIsIgnoredOnlyForNumericComparison() {
        assertTrue(LocalAdbAddresses.matches("fe80::abcd%wlan0",
                Collections.singletonList("fe80:0:0:0:0:0:0:abcd%7")));
        List<String> candidates = LocalAdbAddresses.candidates("fe80::abcd%wlan0",
                Collections.singletonList("fe80:0:0:0:0:0:0:abcd%7"));
        assertTrue(candidates.contains("fe80::abcd%wlan0"));
        assertTrue(candidates.contains("fe80:0:0:0:0:0:0:abcd%7"));
    }

    @Test public void differentAddressesDoNotMatch() {
        assertFalse(LocalAdbAddresses.matches("2001:db8::2", Collections.singletonList("2001:db8::1")));
        assertFalse(LocalAdbAddresses.matches("192.168.1.2", Collections.singletonList("192.168.1.3")));
    }

    @Test public void hostnamesAndMalformedLiteralsAreRejected() {
        for (String invalid : Arrays.asList("localhost", "device.local", "127.1", "2130706433",
                "192.168.001.1", "256.1.1.1", "fe80::1%", "fe80::1%a%b", "2001:::1", "", null)) {
            assertFalse(String.valueOf(invalid), LocalAdbAddresses.matches(invalid,
                    Arrays.asList("127.0.0.1", "192.168.1.1", "fe80::1", invalid)));
        }
    }

    @Test public void nonLocalResolvedAddressIsNotAConnectionCandidate() {
        assertEquals(Arrays.asList("127.0.0.1", "192.168.1.3"),
                LocalAdbAddresses.candidates("192.168.1.2", Collections.singletonList("192.168.1.3")));
    }

    @Test public void candidatesKeepLoopbackFirstAndRejectHostnames() {
        assertEquals(Arrays.asList("127.0.0.1", "192.168.1.3"),
                LocalAdbAddresses.candidates(null, Arrays.asList("device.local", "192.168.1.3", "192.168.1.3")));
        assertEquals(Collections.singletonList("127.0.0.1"), LocalAdbAddresses.candidates(null, null));
    }
}
