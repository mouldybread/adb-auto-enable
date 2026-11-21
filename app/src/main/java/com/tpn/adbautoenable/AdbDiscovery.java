package com.tpn.adbautoenable;

import android.content.Context;
import android.net.Network;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.net.wifi.WifiManager;
import android.os.SystemClock;
import android.util.Log;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Executor;

/** Android 14+ local-device discovery. Invoke only from a worker thread. */
final class AdbDiscovery {
    private static final String TAG = "ADBAutoEnable";
    private static final String TYPE = "_adb-tls-connect._tcp";
    private static final Object DISCOVERY_LOCK = new Object();
    private static final Executor DIRECT = Runnable::run;
    private static final long COLLECTION_MS = 10000;
    private static final long RESOLUTION_MS = 5000;
    private static final long TOTAL_MS = 15000;
    private AdbDiscovery() {}

    static AdbEndpoint discover(Context context) {
        synchronized (DISCOVERY_LOCK) {
            if (Thread.currentThread().isInterrupted()) return null;
            AdbPortDiagnostic.logTlsPort();
            if (Thread.currentThread().isInterrupted()) return null;
            NsdManager manager = (NsdManager) context.getSystemService(Context.NSD_SERVICE);
            if (manager == null) return null;
            Session session = new Session(context, manager);
            WifiManager.MulticastLock multicast = null;
            boolean requested = false;
            try {
                WifiManager wifi = (WifiManager) context.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
                if (wifi != null) {
                    try {
                        multicast = wifi.createMulticastLock("adb-auto-enable-discovery");
                        multicast.setReferenceCounted(false);
                        multicast.acquire();
                    } catch (RuntimeException e) {
                        Log.w(TAG, "Could not acquire multicast lock; continuing NSD discovery", e);
                    }
                }
                Log.i(TAG, "Discovering local ADB; LAN addresses: " + NetworkUtils.getPhysicalAddresses(context));
                manager.discoverServices(TYPE, NsdManager.PROTOCOL_DNS_SD, session);
                requested = true;
                session.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (RuntimeException e) {
                Log.w(TAG, "mDNS discovery failed", e);
            } finally {
                session.finish();
                if (requested) {
                    try { manager.stopServiceDiscovery(session); }
                    catch (RuntimeException e) { Log.w(TAG, "Could not stop mDNS discovery", e); }
                }
                if (multicast != null && multicast.isHeld()) multicast.release();
            }
            if (Thread.currentThread().isInterrupted()) return null;
            AdbEndpoint result = session.result();
            Log.i(TAG, "mDNS collection finished: " + (result == null ? "no local endpoint" : result));
            return result;
        }
    }

    private static final class Key {
        final String name;
        final String type;
        final Network network;
        Key(NsdServiceInfo info) {
            name = info.getServiceName();
            String raw = info.getServiceType();
            type = raw == null ? null : raw.replaceAll("[.]+$", "");
            network = info.getNetwork();
        }
        @Override public boolean equals(Object other) {
            if (!(other instanceof Key)) return false;
            Key key = (Key) other;
            return Objects.equals(name, key.name) && Objects.equals(type, key.type)
                    && Objects.equals(network, key.network);
        }
        @Override public int hashCode() { return Objects.hash(name, type, network); }
        @Override public String toString() { return name + "/" + type + " network=" + network; }
    }

    private static final class Session implements NsdManager.DiscoveryListener {
        final Context context;
        final NsdManager manager;
        final long started = SystemClock.elapsedRealtime();
        final Map<Key, Watch> watches = new LinkedHashMap<>();
        final List<Watch> registrations = new ArrayList<>();
        boolean active = true;
        boolean failed;
        Session(Context context, NsdManager manager) { this.context = context; this.manager = manager; }

        synchronized void await() throws InterruptedException {
            while (!failed) {
                long now = SystemClock.elapsedRealtime();
                if (now - started >= TOTAL_MS) break;
                boolean pending = false;
                for (Watch watch : new ArrayList<>(watches.values())) {
                    if (!watch.completed && now >= watch.deadline) {
                        watch.completed = true;
                        watch.acceptUpdates = false;
                        Log.w(TAG, "mDNS resolution timed out: " + watch.key);
                        watch.stop();
                    }
                    pending |= !watch.completed;
                }
                if (now - started >= COLLECTION_MS && !pending) break;
                wait(Math.min(100, TOTAL_MS - (now - started)));
            }
        }
        synchronized void finish() {
            active = false;
            for (Watch watch : registrations) {
                watch.acceptUpdates = false;
                watch.stop();
            }
        }
        synchronized AdbEndpoint result() {
            AdbEndpoint result = null;
            for (Watch watch : watches.values()) if (watch.endpoint != null) result = watch.endpoint;
            return result;
        }
        @Override public synchronized void onServiceFound(NsdServiceInfo info) {
            if (!active || failed || SystemClock.elapsedRealtime() - started >= COLLECTION_MS) return;
            Key key = new Key(info);
            if (watches.containsKey(key)) {
                Log.d(TAG, "mDNS duplicate service merged: " + key);
                return;
            }
            Watch watch = new Watch(key);
            watches.put(key, watch);
            registrations.add(watch);
            Log.i(TAG, "mDNS service found; tracking: " + key);
            watch.registered = true;
            try { manager.registerServiceInfoCallback(info, DIRECT, watch); }
            catch (RuntimeException e) {
                watch.registered = false;
                watch.completed = true;
                watch.acceptUpdates = false;
                Log.w(TAG, "mDNS service tracking request failed: " + key, e);
            }
            notifyAll();
        }
        @Override public synchronized void onServiceLost(NsdServiceInfo info) {
            if (!active) return;
            Key key = new Key(info);
            Watch watch = watches.remove(key);
            if (watch != null) {
                watch.acceptUpdates = false;
                watch.endpoint = null;
                watch.completed = true;
                watch.stop();
            }
            Log.i(TAG, "mDNS service lost: " + key + "; matched=" + (watch != null));
            notifyAll();
        }
        @Override public void onDiscoveryStarted(String type) { Log.i(TAG, "mDNS discovery started: " + type); }
        @Override public void onDiscoveryStopped(String type) { Log.i(TAG, "mDNS discovery stopped: " + type); }
        @Override public synchronized void onStartDiscoveryFailed(String type, int error) {
            failed = true;
            Log.w(TAG, "mDNS discovery start failed: " + error);
            notifyAll();
        }
        @Override public void onStopDiscoveryFailed(String type, int error) { Log.w(TAG, "mDNS discovery stop failed: " + error); }

        private final class Watch implements NsdManager.ServiceInfoCallback {
            final Key key;
            final long deadline = Math.min(started + TOTAL_MS, SystemClock.elapsedRealtime() + RESOLUTION_MS);
            boolean registered;
            boolean stopRequested;
            boolean completed;
            boolean acceptUpdates = true;
            AdbEndpoint endpoint;
            Watch(Key key) { this.key = key; }
            boolean current() { return active && acceptUpdates && watches.get(key) == this; }
            void stop() {
                if (!registered || stopRequested) return;
                stopRequested = true;
                try { manager.unregisterServiceInfoCallback(this); }
                catch (RuntimeException e) {
                    stopRequested = false;
                    Log.w(TAG, "Could not unregister mDNS callback: " + key, e);
                }
            }
            @Override public void onServiceUpdated(NsdServiceInfo info) {
                synchronized (Session.this) {
                    if (!current()) { Log.d(TAG, "Ignored late mDNS update: " + key); return; }
                    if (SystemClock.elapsedRealtime() >= deadline && !completed) {
                        completed = true;
                        acceptUpdates = false;
                        stop();
                        Session.this.notifyAll();
                        return;
                    }
                    completed = true;
                    endpoint = null;
                    int port = info.getPort();
                    for (InetAddress address : info.getHostAddresses()) {
                        String host = address.getHostAddress();
                        if (host != null && port > 0 && port <= 65535
                                && NetworkUtils.isLocalPhysicalAddress(context, host)) {
                            endpoint = new AdbEndpoint(host, port);
                            break;
                        }
                    }
                    Log.i(TAG, "mDNS service updated: " + key + "; port=" + port
                            + "; addresses=" + info.getHostAddresses() + "; local endpoint=" + endpoint);
                    Session.this.notifyAll();
                }
            }
            @Override public void onServiceLost() {
                synchronized (Session.this) {
                    if (!current()) return;
                    endpoint = null;
                    Log.i(TAG, "mDNS tracked service unavailable: " + key);
                    Session.this.notifyAll();
                }
            }
            @Override public void onServiceInfoCallbackRegistrationFailed(int error) {
                synchronized (Session.this) {
                    registered = false;
                    completed = true;
                    acceptUpdates = false;
                    endpoint = null;
                    Log.w(TAG, "mDNS callback registration failed: " + key + " code=" + error);
                    Session.this.notifyAll();
                }
            }
            @Override public void onServiceInfoCallbackUnregistered() {
                synchronized (Session.this) {
                    registered = false;
                    completed = true;
                    acceptUpdates = false;
                    Log.d(TAG, "mDNS callback unregistered: " + key);
                    Session.this.notifyAll();
                }
            }
        }
    }
}
