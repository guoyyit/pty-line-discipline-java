package com.github.guoyyit.ptylinedisciplinejava.pty;

import java.util.ArrayDeque;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

public final class BufferedControlParty implements ControlParty {

    public record SignalEvent(SignalType type, String sessionId) {
    }

    private final int capacity;
    private final ArrayDeque<SignalEvent> queue = new ArrayDeque<>();
    private final ReentrantLock lock = new ReentrantLock();
    private final Condition notEmpty = lock.newCondition();

    public BufferedControlParty(int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("capacity must be positive");
        }
        this.capacity = capacity;
    }

    @Override
    public SignalDeliveryResult signal(SignalType type, String sessionId) {
        lock.lock();
        try {
            if (lock.hasWaiters(notEmpty)) {
                queue.addLast(new SignalEvent(type, sessionId));
                notEmpty.signal();
                return SignalDeliveryResult.DELIVERED;
            }
            if (queue.size() < capacity) {
                queue.addLast(new SignalEvent(type, sessionId));
                notEmpty.signal();
                return SignalDeliveryResult.QUEUED;
            }
            return SignalDeliveryResult.DROPPED;
        } finally {
            lock.unlock();
        }
    }

    public SignalEvent poll() {
        lock.lock();
        try {
            return queue.pollFirst();
        } finally {
            lock.unlock();
        }
    }

    public SignalEvent take(long timeout, TimeUnit unit) throws InterruptedException {
        long remainingNanos = unit.toNanos(timeout);
        lock.lockInterruptibly();
        try {
            while (queue.isEmpty()) {
                if (remainingNanos <= 0L) {
                    return null;
                }
                remainingNanos = notEmpty.awaitNanos(remainingNanos);
            }
            return queue.pollFirst();
        } finally {
            lock.unlock();
        }
    }

    public int queuedCount() {
        lock.lock();
        try {
            return queue.size();
        } finally {
            lock.unlock();
        }
    }

    public boolean isReady() {
        lock.lock();
        try {
            return lock.hasWaiters(notEmpty);
        } finally {
            lock.unlock();
        }
    }
}
