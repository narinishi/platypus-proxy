package com.example.proxy.handler;

import java.io.Closeable;
import java.io.IOException;
import java.net.Socket;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Iterator;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

import com.example.proxy.dialer.ContextDialer;

class ProviderConnectionPool implements Closeable {
    private final ContextDialer dialer;

    // Wraps socket with the time it was returned to the pool (System.nanoTime for monotonicity)
    private record IdleSocket(Socket socket, long returnTimeNanos) {}

    private final Deque<IdleSocket> idle = new ArrayDeque<>();
    private final Lock lock = new ReentrantLock();
    private final ScheduledExecutorService cleaner;

    // Maximum time a socket can remain idle before being evicted (in milliseconds)
    private static final long POOL_MAX_IDLE_MS = 60_000L; // 1 minute

    ProviderConnectionPool(ContextDialer dialer) {
        this.dialer = dialer;
        this.cleaner = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "proxy-pool-cleaner");
            t.setDaemon(true);
            return t;
        });
        cleaner.scheduleWithFixedDelay(this::evictExpired,
                30_000, 30_000, TimeUnit.MILLISECONDS);
    }

    @Override
    public void close() {
        // Graceful shutdown: wait for running tasks then cancel pending ones
        cleaner.shutdown();
        try {
            if (!cleaner.awaitTermination(5, TimeUnit.SECONDS)) {
                cleaner.shutdownNow();
            }
        } catch (InterruptedException e) {
            cleaner.shutdownNow();
            Thread.currentThread().interrupt();
        }
        lock.lock();
        try {
            for (IdleSocket is : idle) closeQuietly(is.socket());
            idle.clear();
        } finally {
            lock.unlock();
        }
    }

    Socket borrow() throws IOException {
        lock.lock();
        try {
            while (!idle.isEmpty()) {
                IdleSocket is = idle.pollLast();
                Socket s = is.socket();
                if (!s.isClosed() && !isExpired(is)) {
                    return s;
                }
                closeQuietly(s);
            }
        } finally {
            lock.unlock();
        }
        return dialFresh();
    }

    Socket borrowFresh() throws IOException {
        return dialFresh();
    }

    void release(Socket socket) {
        if (socket == null || socket.isClosed()) return;
        lock.lock();
        try {
            if (idle.size() < ProxyHandler.MAX_IDLE_PROVIDER_SOCKETS) {
                idle.addLast(new IdleSocket(socket, System.nanoTime()));
            } else {
                closeQuietly(socket);
            }
        } finally {
            lock.unlock();
        }
    }

    void invalidate(Socket socket) {
        if (socket == null) return;
        lock.lock();
        try {
            // Remove any wrapper containing this socket
            idle.removeIf(is -> is.socket().equals(socket));
        } finally {
            lock.unlock();
        }
        closeQuietly(socket);
    }

    private Socket dialFresh() throws IOException {
        Socket s = dialer.dial("tcp", "dummy");
        ProxyHandler.tuneSocket(s);
        return s;
    }

    private void evictExpired() {
        long now = System.nanoTime();
        lock.lock();
        try {
            Iterator<IdleSocket> it = idle.iterator();
            while (it.hasNext()) {
                IdleSocket is = it.next();
                if (isExpired(is, now)) {
                    it.remove();
                    closeQuietly(is.socket());
                }
            }
        } finally {
            lock.unlock();
        }
    }

    // Check if the socket has been idle longer than POOL_MAX_IDLE_MS
    private static boolean isExpired(IdleSocket is) {
        return isExpired(is, System.nanoTime());
    }

    private static boolean isExpired(IdleSocket is, long nowNanos) {
        long elapsedNanos = nowNanos - is.returnTimeNanos();
        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(elapsedNanos);
        return elapsedMs > POOL_MAX_IDLE_MS;
    }

    private static void closeQuietly(Socket s) {
        // implementation unchanged (assume existing)
        try {
            s.close();
        } catch (IOException ignored) {
        }
    }
}