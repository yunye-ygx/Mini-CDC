package com.yunye.mncdc.ops;

import org.springframework.stereotype.Component;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;

@Component
public class RecentEventBuffer {

    private static final int DEFAULT_CAPACITY = 50;

    private final int capacity;
    private final Deque<RecentEventSummary> events = new ArrayDeque<>();

    public RecentEventBuffer() {
        this(DEFAULT_CAPACITY);
    }

    public RecentEventBuffer(int capacity) {
        this.capacity = capacity;
    }

    public synchronized void append(RecentEventSummary event) {
        if (events.size() == capacity) {
            events.removeLast();
        }
        events.addFirst(event);
    }

    public synchronized List<RecentEventSummary> snapshot() {
        return List.copyOf(events);
    }
}
