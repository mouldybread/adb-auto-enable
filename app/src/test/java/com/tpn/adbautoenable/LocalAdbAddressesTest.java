package com.tpn.adbautoenable;

import org.junit.Test;
import java.util.Arrays;
import java.util.Collections;
import static org.junit.Assert.*;

public class LocalAdbAddressesTest {
    @Test public void loopbackPrecedesResolvedLanAddress() {
        assertEquals(Arrays.asList("127.0.0.1", "10.0.0.8", "192.168.1.8"),
                LocalAdbAddresses.candidates("10.0.0.8", Arrays.asList("192.168.1.8", "10.0.0.8")));
    }
    @Test public void remoteOrVpnHostIsNotAdded() {
        assertEquals(Arrays.asList("127.0.0.1", "10.0.0.8"),
                LocalAdbAddresses.candidates("100.101.1.8", Collections.singletonList("10.0.0.8")));
    }
    @Test public void matchingIgnoresScopeButConnectionsKeepIt() {
        assertTrue(LocalAdbAddresses.matches("fe80::1%wlan0", Collections.singletonList("fe80::1%7")));
        assertEquals(Arrays.asList("127.0.0.1", "fe80::1%7"),
                LocalAdbAddresses.candidates(null, Collections.singletonList("fe80::1%7")));
    }
    @Test public void noLanStillAllowsLoopback() {
        assertEquals(Collections.singletonList("127.0.0.1"),
                LocalAdbAddresses.candidates(null, Collections.emptyList()));
    }
    @Test public void unrelatedLocalSubnetDeviceIsRejected() {
        assertFalse(LocalAdbAddresses.matches("10.0.0.9", Collections.singletonList("10.0.0.8")));
        assertFalse(LocalAdbAddresses.matches(null, Collections.singletonList("10.0.0.8")));
    }
}
