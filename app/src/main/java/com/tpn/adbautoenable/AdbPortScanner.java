package com.tpn.adbautoenable;

import android.content.Context;
import android.util.Log;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** Parallel TCP probes stream candidates to serial ADB verification. Worker-thread API. */
final class AdbPortScanner {
    private static final String TAG = "ADBAutoEnable";
    private static final int MIN_PORT = 32768;
    private static final int MAX_PORT = 60999;
    private static final int WORKERS = 64;
    private static final int CONNECT_TIMEOUT_MS = 50;
    private static final int SWEEP_TIMEOUT_SECONDS = 30;

    private AdbPortScanner() {}

    interface Verifier {
        /** Must perform a bounded authenticated ADB handshake, not just a TCP connection. */
        boolean isAdb(AdbEndpoint endpoint);
    }

    static AdbEndpoint find(Context context, int cachedPort, Verifier verifier) {
        List<String> hosts = NetworkUtils.getAdbCandidateHosts(context, null);
        if (cachedPort > 0 && cachedPort <= 65535) {
            for (String host : hosts) {
                if (Thread.currentThread().isInterrupted()) return null;
                AdbEndpoint candidate = new AdbEndpoint(host, cachedPort);
                if (isOpen(candidate) && verifier.isAdb(candidate)) return candidate;
            }
        }
        for (String host : hosts) {
            if (Thread.currentThread().isInterrupted()) return null;
            AdbEndpoint result = sweep(host, verifier);
            if (result != null) return result;
        }
        return null;
    }

    private static boolean isOpen(AdbEndpoint endpoint) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(endpoint.host, endpoint.port), CONNECT_TIMEOUT_MS);
            return true;
        } catch (Exception ignored) {
            return false;
        }
    }

    private static AdbEndpoint sweep(String host, Verifier verifier) {
        Log.i(TAG, "Scanning local address " + host + " ports " + MIN_PORT + "-" + MAX_PORT);
        Sweep state = new Sweep();
        AtomicInteger next = new AtomicInteger(MIN_PORT);
        LinkedBlockingQueue<AdbEndpoint> candidates = new LinkedBlockingQueue<>();
        ExecutorService executor = Executors.newFixedThreadPool(WORKERS);
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(SWEEP_TIMEOUT_SECONDS);
        try {
            // Only 64 tasks: each worker claims the next port instead of queuing 28k tasks.
            for (int worker = 0; worker < WORKERS; worker++) {
                executor.submit(() -> {
                    while (!Thread.currentThread().isInterrupted() && System.nanoTime() < deadline) {
                        int port = next.getAndIncrement();
                        if (port > MAX_PORT) return;
                        Socket socket = new Socket();
                        if (!state.register(socket)) {
                            close(socket);
                            return;
                        }
                        try {
                            socket.connect(new InetSocketAddress(host, port), CONNECT_TIMEOUT_MS);
                            candidates.offer(new AdbEndpoint(host, port));
                        } catch (Exception ignored) {
                        } finally {
                            state.remove(socket);
                            close(socket);
                        }
                    }
                });
            }
            executor.shutdown();
            while (!Thread.currentThread().isInterrupted()) {
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) {
                    Log.w(TAG, "Scan deadline reached for " + host + "; coverage/verification may be incomplete");
                    return null;
                }
                AdbEndpoint candidate = candidates.poll(
                        Math.min(remaining, TimeUnit.MILLISECONDS.toNanos(100)), TimeUnit.NANOSECONDS);
                if (candidate != null) {
                    // Verification runs immediately, concurrently with TCP probing.
                    if (verifier.isAdb(candidate)) {
                        Log.i(TAG, "Fallback scan verified ADB at " + candidate);
                        return candidate;
                    }
                } else if (executor.isTerminated() && candidates.isEmpty()) {
                    Log.i(TAG, "Scan finished without a verified ADB endpoint for " + host);
                    return null;
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            state.stop();
            executor.shutdownNow();
        }
        return null;
    }

    private static void close(Socket socket) {
        try { socket.close(); } catch (Exception ignored) {}
    }

    private static final class Sweep {
        private boolean stopped;
        private final Set<Socket> sockets = new HashSet<>();

        synchronized boolean register(Socket socket) {
            if (stopped) return false;
            sockets.add(socket);
            return true;
        }
        synchronized void remove(Socket socket) { sockets.remove(socket); }
        synchronized void stop() {
            stopped = true;
            for (Socket socket : sockets) close(socket);
            sockets.clear();
        }
    }
}
