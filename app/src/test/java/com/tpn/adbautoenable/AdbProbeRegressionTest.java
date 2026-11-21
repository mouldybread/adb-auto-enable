package com.tpn.adbautoenable;

import io.github.muntashirakon.adb.AbsAdbConnectionManager;
import java.util.concurrent.TimeUnit;
import org.junit.After;
import org.junit.Test;
import org.mockito.MockedConstruction;
import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** The scanner probe must not report a false connect result as authentication success. */
@SuppressWarnings({"rawtypes", "unchecked"})
public class AdbProbeRegressionTest {
    private Class managerClass() throws Exception {
        return Class.forName("com.tpn.adbautoenable.AdbHelper$SimpleAdbManager");
    }
    private AdbHelper helper() { return mock(AdbHelper.class, CALLS_REAL_METHODS); }
    private AbsAdbConnectionManager manager(MockedConstruction construction) {
        return (AbsAdbConnectionManager) construction.constructed().get(0);
    }
    @After public void clearInterrupt() { Thread.interrupted(); }

    @Test public void falseConnectResultIsFailureAndOwnedManagerIsClosed() throws Exception {
        try (MockedConstruction construction = mockConstruction(managerClass(), (mock, context) -> {
            when(((AbsAdbConnectionManager) mock).connect(anyString(), anyInt())).thenReturn(false);
        })) {
            assertFalse(helper().connect("127.0.0.1", 40001));
            verify(manager(construction)).setTimeout(4, TimeUnit.SECONDS);
            verify(manager(construction)).close();
        }
    }
    @Test public void authenticatedConnectionIsClosedBeforeReturningSuccess() throws Exception {
        try (MockedConstruction construction = mockConstruction(managerClass(), (mock, context) -> {
            AbsAdbConnectionManager manager = (AbsAdbConnectionManager) mock;
            when(manager.connect(anyString(), anyInt())).thenReturn(true);
            when(manager.isConnected()).thenReturn(true);
        })) {
            assertTrue(helper().connect("127.0.0.1", 40002));
            verify(manager(construction)).close();
        }
    }
    @Test public void interruptionClosesOnlyOwnedManagerAndRestoresFlag() throws Exception {
        try (MockedConstruction construction = mockConstruction(managerClass(), (mock, context) -> {
            when(((AbsAdbConnectionManager) mock).connect(anyString(), anyInt())).thenThrow(new InterruptedException());
        })) {
            assertFalse(helper().connect("127.0.0.1", 40003));
            assertTrue(Thread.currentThread().isInterrupted());
            Thread.interrupted();
            verify(manager(construction)).close();
        }
    }
    @Test public void cleanupFailureDoesNotReportSuccess() throws Exception {
        try (MockedConstruction construction = mockConstruction(managerClass(), (mock, context) -> {
            AbsAdbConnectionManager manager = (AbsAdbConnectionManager) mock;
            when(manager.connect(anyString(), anyInt())).thenReturn(true);
            when(manager.isConnected()).thenReturn(true);
            doThrow(new java.io.IOException("test")).when(manager).close();
        })) {
            assertFalse(helper().connect("127.0.0.1", 40004));
        }
    }
}
