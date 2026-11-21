package com.tpn.adbautoenable;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.junit.After;
import org.junit.Test;
import static org.junit.Assert.*;

/** Tests the actual scanner verifier boundary; short budgets are injected without changing production constants. */
public class AdbScannerRegressionTest {
    private final CountDownLatch release = new CountDownLatch(1);
    private Semaphore slots() throws Exception {
        Field field = AdbPortScanner.class.getDeclaredField("VERIFICATION_SLOTS");
        field.setAccessible(true);
        return (Semaphore) field.get(null);
    }
    private Object scan(long millis) throws Exception {
        Class<?> type = Class.forName("com.tpn.adbautoenable.AdbPortScanner$Scan");
        Constructor<?> ctor = type.getDeclaredConstructor();
        ctor.setAccessible(true);
        Object scan = ctor.newInstance();
        Field deadline = type.getDeclaredField("deadline");
        deadline.setAccessible(true);
        deadline.setLong(scan, System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(millis));
        return scan;
    }
    private Object value(Object scan, String name) throws Exception {
        Field field = scan.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(scan);
    }
    private boolean verify(Object scan, AdbEndpoint endpoint, AdbPortScanner.Verifier verifier) throws Exception {
        Method method = scan.getClass().getDeclaredMethod("verify", AdbEndpoint.class, AdbPortScanner.Verifier.class);
        method.setAccessible(true);
        try { return (Boolean) method.invoke(scan, endpoint, verifier); }
        catch (InvocationTargetException e) {
            Throwable cause = e.getCause();
            if (cause instanceof Exception) throw (Exception) cause;
            throw e;
        }
    }
    @After public void cleanup() throws Exception {
        Thread.interrupted();
        release.countDown();
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (slots().availablePermits() != 2 && System.nanoTime() < end) Thread.sleep(5);
        assertEquals("Verification workers must release slots after their actual exit", 2, slots().availablePermits());
    }
    @Test public void trueAndFalseResultsAreHonoured() throws Exception {
        AdbEndpoint endpoint = new AdbEndpoint("127.0.0.1", 40001);
        assertTrue(verify(scan(2000), endpoint, e -> true));
        assertFalse(verify(scan(2000), endpoint, e -> false));
    }
    @Test public void verifierExceptionIsFailure() throws Exception {
        Object s = scan(2000);
        assertFalse(verify(s, new AdbEndpoint("127.0.0.1", 40002), e -> { throw new IllegalStateException("test"); }));
        assertEquals(1, value(s, "verificationFailed"));
    }
    @Test(timeout=5000) public void tcpListenerThatStallsCannotDefeatOverallDeadline() throws Exception {
        try (ServerSocket listener = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
            Object s = scan(250);
            long start = System.nanoTime();
            try {
                verify(s, new AdbEndpoint("127.0.0.1", listener.getLocalPort()), endpoint -> {
                    try (Socket socket = new Socket(endpoint.host, endpoint.port)) {
                        while (release.getCount() != 0) {
                            try { release.await(); } catch (InterruptedException ignored) { }
                        }
                        return false;
                    } catch (Exception e) { return false; }
                });
                fail("Expected overall timeout");
            } catch (TimeoutException expected) { }
            try (Socket accepted = listener.accept()) { assertTrue(accepted.isConnected()); }
            assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start) < 2000);
            assertEquals(1, value(s, "verificationTimedOut"));
            assertEquals("Cancel must not release an executing worker's slot", 1, slots().availablePermits());
        }
    }
    @Test(timeout=5000) public void stuckWorkersRemainBoundedAcrossScans() throws Exception {
        AdbPortScanner.Verifier stuck = endpoint -> {
            while (release.getCount() != 0) {
                try { release.await(); } catch (InterruptedException ignored) { }
            }
            return false;
        };
        AdbEndpoint endpoint = new AdbEndpoint("127.0.0.1", 40003);
        for (int i = 0; i < 2; i++) {
            try { verify(scan(200), endpoint, stuck); fail("Expected timeout"); }
            catch (TimeoutException expected) { }
        }
        Object third = scan(2000);
        assertFalse(verify(third, endpoint, e -> { fail("No third worker may start"); return true; }));
        assertEquals("verification_capacity_exhausted", value(third, "outcome"));
        assertEquals(0, slots().availablePermits());
    }
    @Test public void alreadyInterruptedControllerStartsNoWorker() throws Exception {
        Object s = scan(2000);
        Thread.currentThread().interrupt();
        try {
            verify(s, new AdbEndpoint("127.0.0.1", 40004), e -> { fail("Should not run"); return false; });
            fail("Expected interruption");
        } catch (InterruptedException expected) { }
        finally { Thread.interrupted(); }
        assertEquals(0, value(s, "verificationStarted"));
    }
    @Test(timeout=10000) public void candidateTimeoutAppliesBeforeOverallDeadline() throws Exception {
        Object s = scan(15000);
        assertFalse(verify(s, new AdbEndpoint("127.0.0.1", 40005), e -> {
            try { release.await(); } catch (InterruptedException expected) { }
            return false;
        }));
        assertEquals(1, value(s, "verificationTimedOut"));
        assertEquals(0, value(s, "verificationCompleted"));
    }
}
