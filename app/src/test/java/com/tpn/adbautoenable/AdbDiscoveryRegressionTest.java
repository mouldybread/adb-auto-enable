package com.tpn.adbautoenable;

import android.content.Context;
import android.net.Network;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.os.SystemClock;
import android.util.Log;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.MockedStatic;
import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Exercises the production session directly, without real Android services or wall-clock sleeps. */
public class AdbDiscoveryRegressionTest {
    private final AtomicLong now = new AtomicLong();
    private final List<NsdManager.ServiceInfoCallback> callbacks = new ArrayList<>();
    private final List<Object> sessions = new ArrayList<>();
    private final List<String> logs = new ArrayList<>();
    private MockedStatic<SystemClock> clock;
    private MockedStatic<NetworkUtils> networks;
    private MockedStatic<Log> logging;
    private NsdManager manager;
    private Context context;
    private Network network;

    @Before public void setup() throws Exception {
        clock = mockStatic(SystemClock.class);
        clock.when(SystemClock::elapsedRealtime).thenAnswer(inv -> now.get());
        networks = mockStatic(NetworkUtils.class);
        networks.when(() -> NetworkUtils.getPhysicalAddresses(any(Context.class)))
                .thenReturn(Arrays.asList("192.168.1.10", "fe80::1234%7"));
        logging = mockStatic(Log.class);
        logging.when(() -> Log.i(anyString(), anyString())).thenAnswer(inv -> { logs.add(inv.getArgument(1)); return 0; });
        context = mock(Context.class);
        manager = mock(NsdManager.class);
        network = mock(Network.class);
        doAnswer(inv -> { callbacks.add(inv.getArgument(2)); return null; })
                .when(manager).registerServiceInfoCallback(any(NsdServiceInfo.class), any(Executor.class), any(NsdManager.ServiceInfoCallback.class));
    }
    @After public void teardown() throws Exception {
        try { for (Object session : sessions) call(session, "finish"); }
        finally { logging.close(); networks.close(); clock.close(); }
    }
    private Object session() throws Exception {
        Class<?> type = Class.forName("com.tpn.adbautoenable.AdbDiscovery$Session");
        Constructor<?> constructor = type.getDeclaredConstructor(Context.class);
        constructor.setAccessible(true);
        Object session = constructor.newInstance(context);
        Field field = type.getDeclaredField("manager");
        field.setAccessible(true);
        field.set(session, manager);
        sessions.add(session);
        return session;
    }
    private Object call(Object session, String name) throws Exception {
        Method method = session.getClass().getDeclaredMethod(name);
        method.setAccessible(true);
        return method.invoke(session);
    }
    private NsdManager.DiscoveryListener listener(Object session) { return (NsdManager.DiscoveryListener) session; }
    private AdbEndpoint result(Object session) throws Exception { return (AdbEndpoint) call(session, "result"); }
    private NsdServiceInfo service(Network net, String type, int port, String... addresses) throws Exception {
        NsdServiceInfo info = mock(NsdServiceInfo.class);
        when(info.getServiceName()).thenReturn("adb-self");
        when(info.getServiceType()).thenReturn(type);
        when(info.getNetwork()).thenReturn(net);
        when(info.getPort()).thenReturn(port);
        List<InetAddress> hosts = new ArrayList<>();
        for (String address : addresses) hosts.add(InetAddress.getByName(address));
        when(info.getHostAddresses()).thenReturn(hosts);
        return info;
    }
    private NsdServiceInfo service(int port, String... addresses) throws Exception {
        return service(network, "_adb-tls-connect._tcp", port, addresses);
    }
    private boolean logged(String fragment) { return logs.stream().anyMatch(s -> s.contains(fragment)); }

    @Test public void duplicateFoundDoesNotReplaceInflightRegistration() throws Exception {
        Object s = session();
        listener(s).onServiceFound(service(0));
        listener(s).onServiceFound(service(network, "_adb-tls-connect._tcp.", 0));
        assertEquals(1, callbacks.size());
        callbacks.get(0).onServiceUpdated(service(41001, "192.168.1.10"));
        assertEquals(41001, result(s).port);
        assertTrue(logged("duplicate merged"));
    }
    @Test public void neverReturningResolutionIsTimedOutAndUnregistered() throws Exception {
        Object s = session();
        listener(s).onServiceFound(service(0));
        now.set(15000);
        call(s, "await");
        verify(manager).unregisterServiceInfoCallback(callbacks.get(0));
        assertNull(result(s));
        call(s, "summary");
        assertTrue(logged("outcome=resolution_failed_or_timed_out"));
    }
    @Test public void updateJustBeforeInitialDeadlineIsAccepted() throws Exception {
        Object s = session();
        listener(s).onServiceFound(service(0));
        now.set(4999);
        callbacks.get(0).onServiceUpdated(service(41002, "192.168.1.10"));
        assertEquals(41002, result(s).port);
    }
    @Test public void updateAtInitialDeadlineIsRejected() throws Exception {
        Object s = session();
        listener(s).onServiceFound(service(0));
        now.set(5000);
        callbacks.get(0).onServiceUpdated(service(41002, "192.168.1.10"));
        assertNull(result(s));
        assertTrue(logged("late_initial_update"));
    }
    @Test public void lateFoundServiceCanResolveAfterCollectionWindow() throws Exception {
        Object s = session();
        now.set(9999);
        listener(s).onServiceFound(service(0));
        now.set(12000);
        callbacks.get(0).onServiceUpdated(service(41003, "192.168.1.10"));
        call(s, "await");
        assertEquals(41003, result(s).port);
    }
    @Test public void newServicesAfterCollectionAreNotRegistered() throws Exception {
        Object s = session();
        now.set(10000);
        listener(s).onServiceFound(service(0));
        assertTrue(callbacks.isEmpty());
        assertTrue(logged("reason=collection_closed"));
    }
    @Test public void oldCallbackCannotPopulateLaterSession() throws Exception {
        Object old = session();
        listener(old).onServiceFound(service(0));
        call(old, "finish");
        Object next = session();
        listener(next).onServiceFound(service(0));
        callbacks.get(0).onServiceUpdated(service(41004, "192.168.1.10"));
        assertNull(result(old));
        assertNull(result(next));
        callbacks.get(1).onServiceUpdated(service(41005, "192.168.1.10"));
        assertEquals(41005, result(next).port);
        assertTrue(logged("reason=session_ended"));
    }
    @Test public void sameNameOnTwoNetworksAndLossAreIndependent() throws Exception {
        Object s = session();
        Network other = mock(Network.class);
        NsdServiceInfo first = service(0);
        NsdServiceInfo second = service(other, "_adb-tls-connect._tcp", 0);
        listener(s).onServiceFound(first);
        listener(s).onServiceFound(second);
        assertEquals(2, callbacks.size());
        callbacks.get(0).onServiceUpdated(service(41006, "192.168.1.10"));
        callbacks.get(1).onServiceUpdated(service(other, "_adb-tls-connect._tcp", 41007, "192.168.1.10"));
        listener(s).onServiceLost(second);
        assertEquals(41006, result(s).port);
        verify(manager).unregisterServiceInfoCallback(callbacks.get(1));
    }
    @Test public void refoundServiceRejectsOldCallbackInstance() throws Exception {
        Object s = session();
        NsdServiceInfo info = service(0);
        listener(s).onServiceFound(info);
        listener(s).onServiceLost(info);
        listener(s).onServiceFound(info);
        callbacks.get(0).onServiceUpdated(service(41008, "192.168.1.10"));
        assertNull(result(s));
        callbacks.get(1).onServiceUpdated(service(41009, "192.168.1.10"));
        assertEquals(41009, result(s).port);
        assertTrue(logged("reason=stale_registration"));
    }
    @Test public void allAddressesAreConsideredAndIpv6ScopeSurvives() throws Exception {
        Object s = session();
        listener(s).onServiceFound(service(0));
        NsdServiceInfo update = service(41010, "192.168.1.99", "2001:db8::99");
        InetAddress scoped = mock(InetAddress.class);
        when(scoped.getHostAddress()).thenReturn("fe80:0:0:0:0:0:0:1234%7");
        List<InetAddress> addresses = new ArrayList<>(update.getHostAddresses());
        addresses.add(scoped);
        when(update.getHostAddresses()).thenReturn(addresses);
        callbacks.get(0).onServiceUpdated(update);
        assertEquals("fe80:0:0:0:0:0:0:1234%7", result(s).host);
    }
    @Test public void rejectionReasonsAndSummaryAreVisible() throws Exception {
        Object s = session();
        listener(s).onServiceFound(service(0));
        callbacks.get(0).onServiceUpdated(service(0, "192.168.1.10"));
        callbacks.get(0).onServiceUpdated(service(41011));
        callbacks.get(0).onServiceUpdated(service(41011, "192.168.1.99"));
        assertNull(result(s));
        call(s, "summary");
        assertTrue(logged("reason=invalid_port"));
        assertTrue(logged("reason=no_host_addresses"));
        assertTrue(logged("reason=not_local_physical_address"));
        assertTrue(logged("outcome=resolved_services_rejected"));
    }
    @Test public void registrationFailureDoesNotBlockOtherServices() throws Exception {
        Object s = session();
        listener(s).onServiceFound(service(0));
        callbacks.get(0).onServiceInfoCallbackRegistrationFailed(3);
        listener(s).onServiceFound(service(mock(Network.class), "_adb-tls-connect._tcp", 0));
        callbacks.get(1).onServiceUpdated(service(41012, "192.168.1.10"));
        assertEquals(41012, result(s).port);
    }
    @Test public void summaryOnceAndCleanupAcknowledgedSeparately() throws Exception {
        Object s = session();
        listener(s).onServiceFound(service(0));
        call(s, "finish");
        call(s, "summary");
        call(s, "summary");
        callbacks.get(0).onServiceInfoCallbackUnregistered();
        assertEquals(1, logs.stream().filter(v -> v.contains("SUMMARY outcome=")).count());
        assertTrue(logged("unregister confirmed; afterSummary=true"));
    }
    @Test public void noServicesHasDistinctOutcome() throws Exception {
        Object s = session();
        now.set(10000);
        call(s, "await");
        call(s, "summary");
        assertTrue(logged("outcome=no_services_discovered"));
    }
    @Test public void unregisterFailureIsLoggedAndRetriedAtFinish() throws Exception {
        Object s = session();
        listener(s).onServiceFound(service(0));
        doThrow(new IllegalArgumentException("test")).doNothing()
                .when(manager).unregisterServiceInfoCallback(callbacks.get(0));
        now.set(5000);
        callbacks.get(0).onServiceUpdated(service(41013, "192.168.1.10"));
        call(s, "finish");
        verify(manager, times(2)).unregisterServiceInfoCallback(callbacks.get(0));
    }
}
