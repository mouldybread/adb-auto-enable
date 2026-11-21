package com.tpn.adbautoenable;

import android.content.Context;
import android.util.Log;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.FutureTask;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/** Parallel TCP probes and independently bounded, connection-owning verification. */
final class AdbPortScanner {
    private static final String TAG = "ADBAutoEnable";
    private static final int MIN_PORT = 32768, MAX_PORT = 60999, WORKERS = 64;
    private static final int CONNECT_TIMEOUT_MS = 50;
    private static final long TOTAL_NS = TimeUnit.SECONDS.toNanos(30);
    private static final long VERIFY_NS = TimeUnit.SECONDS.toNanos(5);
    private static final Semaphore VERIFICATION_SLOTS = new Semaphore(2);
    private static final AtomicLong IDS = new AtomicLong();
    private AdbPortScanner() {}

    interface Verifier {
        /** Own and close the attempt's connection before returning. Honour interruption where possible. */
        boolean isAdb(AdbEndpoint endpoint);
    }

    static AdbEndpoint find(Context context, int cachedPort, Verifier verifier) {
        Scan scan = new Scan();
        AdbEndpoint result = null;
        List<String> hosts = null;
        try {
            hosts = NetworkUtils.getAdbCandidateHosts(context, null);
            scan.expectedCoverage = (long) hosts.size() * (MAX_PORT - MIN_PORT + 1);
            scan.log("starting; hosts=" + hosts + "; cachedPort=" + cachedPort
                    + "; totalMs=30000; candidateMs=5000; maxOutstandingVerifiers=2");
            if (cachedPort > 0 && cachedPort <= 65535) {
                for (String host : hosts) {
                    scan.check();
                    scan.log("host transition; phase=cached; host=" + host);
                    AdbEndpoint candidate = new AdbEndpoint(host, cachedPort);
                    try (Socket socket = new Socket()) {
                        scan.cachedAttempted++;
                        socket.connect(new InetSocketAddress(host, cachedPort), scan.connectTimeout());
                        scan.open.incrementAndGet();
                        scan.log("TCP candidate open; phase=cached; endpoint=" + candidate);
                    } catch (java.io.IOException e) { continue; }
                    if (scan.verify(candidate, verifier)) { result = candidate; scan.outcome = "verified"; return result; }
                    if (scan.exhausted) return null;
                }
            }
            for (String host : hosts) {
                scan.check();
                scan.log("host transition; phase=sweep; host=" + host);
                result = sweep(host, verifier, scan);
                if (result != null) { scan.outcome = "verified"; return result; }
                if (scan.exhausted) return null;
            }
            scan.outcome = "no_verified_endpoint";
        } catch (TimeoutException e) {
            scan.outcome = "overall_timeout";
        } catch (InterruptedException e) {
            scan.outcome = "interrupted";
            Thread.currentThread().interrupt();
        } catch (RuntimeException e) {
            scan.outcome = "scan_error";
            Log.w(TAG, scan.prefix() + "scan error", e);
        } finally {
            scan.log("SUMMARY outcome=" + scan.outcome + "; endpoint=" + result
                    + "; sweepPortsAttempted=" + scan.attempted + "; sweepProbesCompleted=" + scan.completed
                    + "; expectedSweepProbes=" + scan.expectedCoverage + "; cachedAttempted=" + scan.cachedAttempted
                    + "; tcpOpen=" + scan.open + "; verificationStarted=" + scan.verificationStarted
                    + "; verificationCompleted=" + scan.verificationCompleted + "; verificationFailed=" + scan.verificationFailed
                    + "; verificationTimedOut=" + scan.verificationTimedOut
                    + "; tcpCoverageComplete=" + (scan.completed.get() == scan.expectedCoverage)
                    + "; allOpenCandidatesVerified=" + (scan.open.get() == scan.verificationCompleted)
                    + "; activeVerificationWorkers=" + (2 - VERIFICATION_SLOTS.availablePermits())
                    + "; cleanup is best-effort; late worker exits retain this scan ID");
        }
        return null;
    }

    private static AdbEndpoint sweep(String host, Verifier verifier, Scan scan)
            throws InterruptedException, TimeoutException {
        Sweep state = new Sweep();
        AtomicInteger next = new AtomicInteger(MIN_PORT);
        LinkedBlockingQueue<AdbEndpoint> candidates = new LinkedBlockingQueue<>();
        ExecutorService executor = Executors.newFixedThreadPool(WORKERS);
        try {
            for (int worker = 0; worker < WORKERS; worker++) {
                executor.submit(() -> {
                    while (!Thread.currentThread().isInterrupted() && !state.stopped && scan.remaining() > 0) {
                        int port = next.getAndIncrement();
                        if (port > MAX_PORT) return;
                        Socket socket = new Socket();
                        if (!state.register(socket)) { close(socket); return; }
                        scan.attempted.incrementAndGet();
                        boolean completed = false;
                        try {
                            socket.connect(new InetSocketAddress(host, port), scan.connectTimeout());
                            AdbEndpoint candidate = new AdbEndpoint(host, port);
                            scan.open.incrementAndGet();
                            scan.log("TCP candidate open; phase=sweep; endpoint=" + candidate);
                            candidates.offer(candidate);
                            completed = true;
                        } catch (java.io.IOException e) {
                            completed = !state.stopped && !Thread.currentThread().isInterrupted();
                        } catch (RuntimeException e) {
                            Log.w(TAG, scan.prefix() + "TCP probe error; host=" + host + "; port=" + port, e);
                        } finally {
                            if (completed) scan.completed.incrementAndGet();
                            state.remove(socket);
                            close(socket);
                        }
                    }
                });
            }
            executor.shutdown();
            while (true) {
                scan.check();
                AdbEndpoint candidate = candidates.poll(Math.min(scan.remaining(), TimeUnit.MILLISECONDS.toNanos(100)), TimeUnit.NANOSECONDS);
                if (candidate != null) {
                    if (scan.verify(candidate, verifier)) return candidate;
                    if (scan.exhausted) return null;
                } else if (executor.isTerminated() && candidates.isEmpty()) {
                    scan.log("host sweep finished; host=" + host);
                    return null;
                }
            }
        } finally {
            state.stop();
            executor.shutdownNow();
            boolean interrupted = Thread.interrupted();
            boolean terminated = false;
            try { terminated = executor.awaitTermination(200, TimeUnit.MILLISECONDS); }
            catch (InterruptedException e) { interrupted = true; }
            finally { if (interrupted) Thread.currentThread().interrupt(); }
            scan.log("TCP cleanup; host=" + host + "; workersStopped=" + terminated
                    + "; queuedCandidatesNotVerified=" + candidates.size());
        }
    }

    private static final class Scan {
        final long id = IDS.incrementAndGet();
        final long started = System.nanoTime();
        final long deadline = started + TOTAL_NS;
        final AtomicInteger attempted = new AtomicInteger(), completed = new AtomicInteger(), open = new AtomicInteger();
        int cachedAttempted, verificationStarted, verificationCompleted, verificationFailed, verificationTimedOut;
        long expectedCoverage;
        boolean exhausted;
        String outcome = "incomplete";
        String prefix() { return "ADB scan #" + id + " +" + TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) + "ms: "; }
        void log(String text) { Log.i(TAG, prefix() + text); }
        long remaining() { return deadline - System.nanoTime(); }
        void check() throws InterruptedException, TimeoutException {
            if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
            if (remaining() <= 0) throw new TimeoutException();
        }
        int connectTimeout() { return (int) Math.max(1, Math.min(CONNECT_TIMEOUT_MS, TimeUnit.NANOSECONDS.toMillis(Math.max(0, remaining())))); }
        boolean verify(AdbEndpoint candidate, Verifier verifier) throws InterruptedException, TimeoutException {
            check();
            if (!VERIFICATION_SLOTS.tryAcquire()) {
                exhausted = true;
                outcome = "verification_capacity_exhausted";
                log("verification not started; all worker slots still occupied; endpoint=" + candidate);
                return false;
            }
            FutureTask<Boolean> task = new FutureTask<>(() -> verifier.isAdb(candidate));
            Thread worker = new Thread(() -> {
                try { task.run(); }
                finally {
                    // Future.cancel() is NOT evidence this worker or its cleanup has finished.
                    VERIFICATION_SLOTS.release();
                    log("verification worker exited; verifier cleanup returned; endpoint=" + candidate);
                }
            }, "adb-scan-" + id + "-verify");
            worker.setDaemon(true);
            verificationStarted++;
            long began = System.nanoTime();
            log("verification starting; endpoint=" + candidate + "; budgetMs="
                    + TimeUnit.NANOSECONDS.toMillis(Math.min(VERIFY_NS, Math.max(0, remaining()))));
            boolean launched = false;
            try {
                worker.start();
                launched = true;
                long budget = Math.min(VERIFY_NS, remaining());
                if (budget <= 0) throw new TimeoutException();
                boolean success = task.get(budget, TimeUnit.NANOSECONDS);
                verificationCompleted++;
                if (!success) verificationFailed++;
                log("verification result=" + success + "; endpoint=" + candidate
                        + "; elapsedMs=" + TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - began));
                check();
                return success;
            } catch (TimeoutException e) {
                verificationTimedOut++;
                log("verification timed out; endpoint=" + candidate + "; cancellation requested; cleanup may still be running");
                if (remaining() <= 0) throw e;
                return false;
            } catch (ExecutionException e) {
                verificationCompleted++;
                verificationFailed++;
                Log.w(TAG, prefix() + "verification threw; endpoint=" + candidate, e.getCause());
                return false;
            } catch (InterruptedException e) {
                log("verification interrupted; endpoint=" + candidate + "; cancellation requested");
                throw e;
            } finally {
                if (!task.isDone()) task.cancel(true);
                if (!launched) VERIFICATION_SLOTS.release();
            }
        }
    }

    private static void close(Socket socket) { try { socket.close(); } catch (Exception ignored) {} }
    private static final class Sweep {
        volatile boolean stopped;
        final Set<Socket> sockets = new HashSet<>();
        synchronized boolean register(Socket socket) { if (stopped) return false; sockets.add(socket); return true; }
        synchronized void remove(Socket socket) { sockets.remove(socket); }
        synchronized void stop() {
            stopped = true;
            for (Socket socket : sockets) close(socket);
            sockets.clear();
        }
    }
}
