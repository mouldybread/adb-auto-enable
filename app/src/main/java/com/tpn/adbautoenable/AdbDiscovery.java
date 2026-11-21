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
import java.util.concurrent.atomic.AtomicLong;

/** Android 14+ local-device discovery. Invoke only from a worker thread. */
final class AdbDiscovery {
    private static final String TAG = "ADBAutoEnable";
    private static final String TYPE = "_adb-tls-connect._tcp";
    private static final Object DISCOVERY_LOCK = new Object();
    private static final Executor DIRECT = Runnable::run;
    private static final AtomicLong IDS = new AtomicLong();
    private static final long COLLECTION_MS = 10000;
    private static final long RESOLUTION_MS = 5000;
    private static final long TOTAL_MS = 15000;
    private AdbDiscovery() {}

    static AdbEndpoint discover(Context context) {
        synchronized (DISCOVERY_LOCK) {
            Session session = new Session(context);
            WifiManager.MulticastLock multicast = null;
            boolean requested = false;
            try {
                session.log("starting; type=" + TYPE + "; collectionMs=" + COLLECTION_MS
                        + "; initialResolutionMs=" + RESOLUTION_MS + "; totalMs=" + TOTAL_MS);
                if (Thread.currentThread().isInterrupted()) { session.exit = "interrupted"; return null; }
                AdbPortDiagnostic.logTlsPort();
                if (Thread.currentThread().isInterrupted()) { session.exit = "interrupted"; return null; }
                session.manager = (NsdManager) context.getSystemService(Context.NSD_SERVICE);
                if (session.manager == null) { session.exit = "nsd_unavailable"; return null; }
                WifiManager wifi = (WifiManager) context.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
                if (wifi != null) {
                    try {
                        multicast = wifi.createMulticastLock("adb-auto-enable-discovery");
                        multicast.setReferenceCounted(false);
                        multicast.acquire();
                        session.log("multicast lock acquired");
                    } catch (RuntimeException e) { session.warn("multicast lock unavailable; continuing", e); }
                } else { session.log("Wi-Fi manager unavailable; continuing without multicast lock"); }
                session.log("local physical addresses=" + NetworkUtils.getPhysicalAddresses(context));
                session.log("discovery requested");
                session.manager.discoverServices(TYPE, NsdManager.PROTOCOL_DNS_SD, session);
                requested = true;
                session.await();
            } catch (InterruptedException e) {
                session.exit = "interrupted";
                Thread.currentThread().interrupt();
                session.log("worker interrupted; cancelling discovery");
            } catch (RuntimeException e) {
                session.exit = "discovery_exception";
                session.warn("discovery exception", e);
            } finally {
                try {
                    session.finish();
                    if (requested) {
                        try {
                            session.log("discovery stop requested");
                            session.manager.stopServiceDiscovery(session);
                        } catch (RuntimeException e) { session.warn("discovery stop request failed", e); }
                    }
                } finally {
                    try {
                        if (multicast != null && multicast.isHeld()) {
                            multicast.release();
                            session.log("multicast lock released");
                        }
                    } catch (RuntimeException e) { session.warn("multicast lock release failed", e); }
                    finally { session.summary(); }
                }
            }
            if (Thread.currentThread().isInterrupted()) return null;
            return session.result();
        }
    }

    private static String safe(String text) {
        if (text == null) return "null";
        String value = text.replace('\n', ' ').replace('\r', ' ');
        return value.length() > 240 ? value.substring(0, 240) + "..." : value;
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
        @Override public String toString() { return safe(name) + "/" + safe(type) + " network=" + network; }
    }

    private static final class Session implements NsdManager.DiscoveryListener {
        final Context context;
        final long id = IDS.incrementAndGet();
        final long started = SystemClock.elapsedRealtime();
        final Map<Key, Watch> watches = new LinkedHashMap<>();
        final List<Watch> registrations = new ArrayList<>();
        NsdManager manager;
        boolean active = true;
        boolean failed;
        boolean collectionClosed;
        boolean summarized;
        String exit = "collection_complete";
        int found, duplicates, lost, requests, updates, accepted, rejected, failures, timeouts, ignored;
        int stopRequests, stopConfirmed, stopFailures;
        Session(Context context) { this.context = context; }
        String prefix() { return "mDNS #" + id + " +" + (SystemClock.elapsedRealtime() - started) + "ms: "; }
        void log(String message) { Log.i(TAG, prefix() + message); }
        void warn(String message, Throwable error) { Log.w(TAG, prefix() + message, error); }

        int pending() {
            int count = 0;
            for (Watch watch : watches.values()) if (!watch.completed) count++;
            return count;
        }
        synchronized void await() throws InterruptedException {
            while (!failed) {
                long now = SystemClock.elapsedRealtime();
                if (now - started >= TOTAL_MS) {
                    exit = "overall_deadline";
                    log("overall deadline reached; pending=" + pending());
                    for (Watch watch : new ArrayList<>(watches.values()))
                        if (!watch.completed) watch.timeout("overall_deadline");
                    break;
                }
                if (!collectionClosed && now - started >= COLLECTION_MS) {
                    collectionClosed = true;
                    log("collection window closed; draining existing resolutions; pending=" + pending());
                }
                for (Watch watch : new ArrayList<>(watches.values()))
                    if (!watch.completed && now >= watch.deadline) watch.timeout("initial_resolution_deadline");
                if (collectionClosed && pending() == 0) break;
                wait(Math.min(100, TOTAL_MS - (now - started)));
            }
        }
        synchronized void finish() {
            active = false;
            log("session ending; pending=" + pending() + "; registrations=" + registrations.size());
            for (Watch watch : registrations) {
                watch.acceptUpdates = false;
                watch.stop("session_end");
            }
        }
        synchronized AdbEndpoint result() {
            AdbEndpoint result = null;
            for (Watch watch : watches.values()) if (watch.endpoint != null) result = watch.endpoint;
            return result;
        }
        synchronized void summary() {
            if (summarized) return;
            summarized = true;
            AdbEndpoint endpoint = result();
            String outcome;
            if ("interrupted".equals(exit)) outcome = "cancelled_interrupted";
            else if (failed || "discovery_exception".equals(exit) || "nsd_unavailable".equals(exit)) outcome = "discovery_failed";
            else if (endpoint != null) outcome = "local_endpoint_found";
            else if (found == 0) outcome = "no_services_discovered";
            else if (timeouts > 0 || failures > 0) outcome = "resolution_failed_or_timed_out";
            else if (rejected > 0 && accepted == 0) outcome = "resolved_services_rejected";
            else outcome = "no_current_local_endpoint";
            log("SUMMARY outcome=" + outcome + "; exit=" + exit + "; endpoint=" + endpoint
                    + "; foundEvents=" + found + "; duplicates=" + duplicates + "; lostEvents=" + lost
                    + "; resolutionRequests=" + requests + "; updates=" + updates
                    + "; acceptedUpdates=" + accepted + "; rejectedUpdates=" + rejected
                    + "; failures=" + failures + "; timeouts=" + timeouts + "; ignored=" + ignored
                    + "; pending=" + pending() + "; unregisterRequested=" + stopRequests
                    + "; unregisterConfirmed=" + stopConfirmed + "; unregisterFailed=" + stopFailures
                    + "; cleanup confirmations may arrive after this snapshot");
        }
        @Override public synchronized void onServiceFound(NsdServiceInfo info) {
            Key key = new Key(info);
            if (!active || failed || SystemClock.elapsedRealtime() - started >= COLLECTION_MS) {
                ignored++;
                log("service-found ignored; reason=" + (!active ? "session_ended" : failed ? "discovery_failed" : "collection_closed") + "; " + key);
                return;
            }
            found++;
            log("service found: " + key);
            if (watches.containsKey(key)) {
                duplicates++;
                log("duplicate merged; retaining existing registration/deadline: " + key);
                return;
            }
            Watch watch = new Watch(key, registrations.size() + 1);
            watches.put(key, watch);
            registrations.add(watch);
            watch.registered = true;
            requests++;
            log("resolution requested: " + watch.label() + "; pending=" + pending() + "; queue=none (independent callbacks)");
            try { manager.registerServiceInfoCallback(info, DIRECT, watch); }
            catch (RuntimeException e) {
                watch.registered = false;
                watch.completed = true;
                watch.acceptUpdates = false;
                failures++;
                warn("resolution registration threw: " + watch.label(), e);
            }
            notifyAll();
        }
        @Override public synchronized void onServiceLost(NsdServiceInfo info) {
            Key key = new Key(info);
            if (!active) { ignored++; log("service-lost ignored; reason=session_ended; " + key); return; }
            lost++;
            Watch watch = watches.remove(key);
            log("service lost: " + key + "; matched=" + (watch != null)
                    + "; invalidatedEndpoint=" + (watch == null ? null : watch.endpoint));
            if (watch != null) {
                watch.acceptUpdates = false;
                watch.endpoint = null;
                watch.completed = true;
                watch.stop("service_lost");
            }
            notifyAll();
        }
        @Override public void onDiscoveryStarted(String type) { log("discovery started; type=" + safe(type)); }
        @Override public void onDiscoveryStopped(String type) { log("discovery stop confirmed; type=" + safe(type)); }
        @Override public synchronized void onStartDiscoveryFailed(String type, int error) {
            failed = true;
            exit = "discovery_start_failed";
            log("discovery start failed; code=" + error + "; type=" + safe(type));
            notifyAll();
        }
        @Override public void onStopDiscoveryFailed(String type, int error) {
            log("discovery stop failed; code=" + error + "; type=" + safe(type));
        }

        private final class Watch implements NsdManager.ServiceInfoCallback {
            final Key key;
            final int number;
            final long requestedAt = SystemClock.elapsedRealtime();
            final long deadline = Math.min(started + TOTAL_MS, requestedAt + RESOLUTION_MS);
            boolean registered;
            boolean stopRequested;
            boolean completed;
            boolean acceptUpdates = true;
            AdbEndpoint endpoint;
            Watch(Key key, int number) { this.key = key; this.number = number; }
            String label() { return "registration=" + number + "; " + key; }
            boolean current(String event) {
                String reason = !active ? "session_ended" : watches.get(key) != this ? "stale_registration"
                        : !acceptUpdates ? "registration_retired" : null;
                if (reason == null) return true;
                ignored++;
                log(event + " ignored; reason=" + reason + "; " + label());
                return false;
            }
            void timeout(String reason) {
                completed = true;
                acceptUpdates = false;
                timeouts++;
                log("resolution timed out; reason=" + reason + "; elapsedMs="
                        + (SystemClock.elapsedRealtime() - requestedAt) + "; " + label());
                stop("resolution_timeout");
            }
            void stop(String reason) {
                if (!registered || stopRequested) return;
                stopRequested = true;
                stopRequests++;
                log("callback unregister requested; reason=" + reason + "; " + label());
                try { manager.unregisterServiceInfoCallback(this); }
                catch (RuntimeException e) {
                    stopRequested = false;
                    stopFailures++;
                    warn("callback unregister request failed: " + label(), e);
                }
            }
            @Override public void onServiceUpdated(NsdServiceInfo info) {
                synchronized (Session.this) {
                    if (!current("service update")) return;
                    if (SystemClock.elapsedRealtime() >= deadline && !completed) {
                        timeout("late_initial_update");
                        Session.this.notifyAll();
                        return;
                    }
                    completed = true;
                    updates++;
                    endpoint = null;
                    int port = info.getPort();
                    List<InetAddress> addresses = info.getHostAddresses();
                    log("resolution update: " + label() + "; returnedNetwork=" + info.getNetwork()
                            + "; port=" + port + "; addresses=" + addresses
                            + "; elapsedMs=" + (SystemClock.elapsedRealtime() - requestedAt));
                    if (port <= 0 || port > 65535) {
                        log("update rejected; reason=invalid_port; " + label());
                    } else if (addresses.isEmpty()) {
                        log("update rejected; reason=no_host_addresses; " + label());
                    } else {
                        List<String> locals = NetworkUtils.getPhysicalAddresses(context);
                        for (InetAddress address : addresses) {
                            String host = address.getHostAddress();
                            if (host == null) {
                                log("address rejected; reason=missing_numeric_address; " + label());
                            } else if (!LocalAdbAddresses.matches(host, locals)) {
                                log("address rejected; reason=not_local_physical_address; host=" + safe(host)
                                        + "; locals=" + locals + "; " + label());
                            } else if (endpoint == null) {
                                endpoint = new AdbEndpoint(host, port);
                                log("local endpoint accepted: " + endpoint + "; " + label());
                            } else {
                                log("additional local address available: " + safe(host) + "; " + label());
                            }
                        }
                    }
                    if (endpoint == null) rejected++; else accepted++;
                    Session.this.notifyAll();
                }
            }
            @Override public void onServiceLost() {
                synchronized (Session.this) {
                    if (!current("tracked service-lost")) return;
                    log("tracked service unavailable; invalidatedEndpoint=" + endpoint + "; " + label());
                    endpoint = null;
                    Session.this.notifyAll();
                }
            }
            @Override public void onServiceInfoCallbackRegistrationFailed(int error) {
                synchronized (Session.this) {
                    registered = false;
                    completed = true;
                    acceptUpdates = false;
                    endpoint = null;
                    failures++;
                    log("callback registration failed; code=" + error + "; afterSessionEnd=" + !active + "; " + label());
                    Session.this.notifyAll();
                }
            }
            @Override public void onServiceInfoCallbackUnregistered() {
                synchronized (Session.this) {
                    registered = false;
                    completed = true;
                    acceptUpdates = false;
                    stopConfirmed++;
                    log("callback unregister confirmed; afterSummary=" + summarized + "; " + label());
                    Session.this.notifyAll();
                }
            }
        }
    }
}
