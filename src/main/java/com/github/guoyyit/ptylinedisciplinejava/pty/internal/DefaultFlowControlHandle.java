package com.github.guoyyit.ptylinedisciplinejava.pty.internal;

import com.github.guoyyit.ptylinedisciplinejava.pty.FlowControlEvent;
import com.github.guoyyit.ptylinedisciplinejava.pty.FlowControlHandle;

import java.util.ArrayDeque;
import java.util.Objects;

/**
 * Default {@link FlowControlHandle} backed by a bounded STOP/START event FIFO.
 */
public final class DefaultFlowControlHandle implements FlowControlHandle {

    private final String writerId;
    private final int eventQueueCapacity;
    private final Object lock = new Object();
    private final ArrayDeque<FlowControlEvent> pending = new ArrayDeque<>();

    private int dropped;
    private boolean stopped;
    private boolean registered = true;

    public DefaultFlowControlHandle(String writerId, int eventQueueCapacity) {
        this.writerId = Objects.requireNonNull(writerId, "writerId");
        if (eventQueueCapacity <= 0) {
            throw new IllegalArgumentException(
                    "eventQueueCapacity must be positive: " + eventQueueCapacity);
        }
        this.eventQueueCapacity = eventQueueCapacity;
    }

    @Override
    public FlowControlEvent pollEvent() {
        synchronized (lock) {
            return pending.pollFirst();
        }
    }

    @Override
    public FlowControlEvent awaitEvent(long timeoutMillis) throws InterruptedException {
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
    public boolean isStopped() {
        synchronized (lock) {
            return stopped;
        }
    }

    @Override
    public int droppedEvents() {
        synchronized (lock) {
            return dropped;
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

    // ---- internals (package private hooks used by the session) ----

    void notifyEvent(FlowControlEvent event) {
        Objects.requireNonNull(event, "event");
        synchronized (lock) {
            if (!registered) {
                return;
            }
            stopped = event.stopped();
            if (pending.size() < eventQueueCapacity) {
                pending.addLast(event);
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
