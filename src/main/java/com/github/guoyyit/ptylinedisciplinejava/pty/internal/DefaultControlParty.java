package com.github.guoyyit.ptylinedisciplinejava.pty.internal;

import com.github.guoyyit.ptylinedisciplinejava.pty.ControlParty;
import com.github.guoyyit.ptylinedisciplinejava.pty.PtySignal;
import com.github.guoyyit.ptylinedisciplinejava.pty.SignalDeliveryPolicy;

import java.util.ArrayDeque;
import java.util.Objects;

/**
 * Default {@link ControlParty} backed by a bounded in-memory signal FIFO.
 */
public final class DefaultControlParty implements ControlParty {

    private final String id;
    private final SignalDeliveryPolicy policy;
    private final int queueCapacity;
    private final Object lock = new Object();
    private final ArrayDeque<PtySignal> pending = new ArrayDeque<>();

    private int dropped;
    private boolean registered = true;
    private boolean hungUp;

    public DefaultControlParty(String id, SignalDeliveryPolicy policy, int queueCapacity) {
        this.id = Objects.requireNonNull(id, "id");
        this.policy = Objects.requireNonNull(policy, "policy");
        if (queueCapacity <= 0) {
            throw new IllegalArgumentException(
                    "queueCapacity must be positive: " + queueCapacity);
        }
        this.queueCapacity = policy == SignalDeliveryPolicy.QUEUE ? queueCapacity : 1;
    }

    @Override
    public String id() {
        return id;
    }

    @Override
    public PtySignal pollSignal() {
        synchronized (lock) {
            return pending.pollFirst();
        }
    }

    @Override
    public PtySignal awaitSignal(long timeoutMillis) throws InterruptedException {
        if (timeoutMillis < 0) {
            throw new IllegalArgumentException("timeoutMillis must not be negative");
        }
        synchronized (lock) {
            if (timeoutMillis == 0) {
                return pending.pollFirst();
            }
            long deadline = System.nanoTime() + timeoutMillis * 1_000_000L;
            while (pending.isEmpty() && registered) {
                long remainingNanos = deadline - System.nanoTime();
                if (remainingNanos <= 0) {
                    break;
                }
                lock.wait(remainingNanos / 1_000_000L,
                        (int) (remainingNanos % 1_000_000L));
            }
            return pending.pollFirst();
        }
    }

    @Override
    public int droppedSignals() {
        synchronized (lock) {
            return dropped;
        }
    }

    @Override
    public boolean hungUp() {
        synchronized (lock) {
            return hungUp;
        }
    }

    @Override
    public void close() {
        synchronized (lock) {
            registered = false;
            pending.clear();
            lock.notifyAll();
        }
    }

    /**
     * Mark the party as detached from its session without discarding a
     * already-queued HANGUP signal. Used during session shutdown.
     */
    void detach() {
        synchronized (lock) {
            registered = false;
            lock.notifyAll();
        }
    }

    // ---- internals (package private hooks used by the session) ----

    void deliver(PtySignal signal) {
        Objects.requireNonNull(signal, "signal");
        synchronized (lock) {
            if (signal == PtySignal.HANGUP) {
                hungUp = true;
                registered = false;
                pending.clear();
                pending.addLast(PtySignal.HANGUP);
                lock.notifyAll();
                return;
            }
            if (!registered) {
                return;
            }
            if (hungUp) {
                dropped++;
                return;
            }
            if (pending.size() < queueCapacity) {
                pending.addLast(signal);
                lock.notifyAll();
            } else {
                dropped++;
            }
        }
    }

    boolean isRegistered() {
        synchronized (lock) {
            return registered;
        }
    }
}
