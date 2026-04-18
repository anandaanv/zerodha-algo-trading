package com.dtech.kitecon.simulation;

import lombok.Getter;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;

public class SimulationClock {
    private final AtomicReference<Instant> currentTime = new AtomicReference<>(Instant.now());
    @Getter private final Instant startTime;
    @Getter private final Instant endTime;
    @Getter private final int stepMinutes;
    @Getter private boolean running = false;
    @Getter private boolean completed = false;

    public SimulationClock(Instant start, Instant end, int stepMinutes) {
        this.startTime = start;
        this.endTime = end;
        this.stepMinutes = stepMinutes;
        this.currentTime.set(start);
    }

    public Instant getCurrentTime() { return currentTime.get(); }

    public boolean advance() {
        Instant next = currentTime.get().plusSeconds(stepMinutes * 60L);
        if (next.isAfter(endTime)) { completed = true; running = false; return false; }
        currentTime.set(next);
        return true;
    }

    public void start() { running = true; completed = false; }
    public void stop() { running = false; }
}
