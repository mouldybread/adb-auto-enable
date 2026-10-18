package com.tpn.adbautoenable;

import org.junit.Test;
import static org.junit.Assert.*;

public class AdbEndpointTest {
    @Test public void retainsHostAndPort() {
        AdbEndpoint endpoint = new AdbEndpoint("10.0.0.8", 40179);
        assertEquals("10.0.0.8", endpoint.host);
        assertEquals(40179, endpoint.port);
        assertEquals("10.0.0.8:40179", endpoint.toString());
    }
    @Test public void formatsScopedIpv6() {
        assertEquals("[fe80::1%7]:40179", new AdbEndpoint("fe80::1%7", 40179).toString());
    }
    @Test(expected = IllegalArgumentException.class) public void rejectsMissingHost() {
        new AdbEndpoint(null, 40179);
    }
    @Test(expected = IllegalArgumentException.class) public void rejectsZeroPort() {
        new AdbEndpoint("127.0.0.1", 0);
    }
    @Test(expected = IllegalArgumentException.class) public void rejectsOutOfRangePort() {
        new AdbEndpoint("127.0.0.1", 65536);
    }
}
