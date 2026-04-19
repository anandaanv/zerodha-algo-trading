package com.dtech.kitecon.simulation;

import lombok.Getter;
import java.time.*;
import java.util.concurrent.atomic.AtomicReference;

public class SimulationClock {
    private final AtomicReference<Instant> currentTime = new AtomicReference<>(Instant.now());
    @Getter private final Instant startTime;
    @Getter private final Instant endTime;
    @Getter private final int stepMinutes;
    @Getter private boolean running = false;
    @Getter private boolean completed = false;

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final LocalTime MARKET_OPEN = LocalTime.of(9, 15);
    private static final LocalTime MARKET_CLOSE = LocalTime.of(15, 30);

    public SimulationClock(Instant start, Instant end, int stepMinutes) {
        this.startTime = start;
        this.endTime = end;
        this.stepMinutes = stepMinutes;
        this.currentTime.set(snapToMarketTime(start));
    }

    public Instant getCurrentTime() { return currentTime.get(); }

    public boolean advance() {
        Instant next = currentTime.get().plusSeconds(stepMinutes * 60L);
        next = snapToMarketTime(next);
        if (next.isAfter(endTime)) { completed = true; running = false; return false; }
        currentTime.set(next);
        return true;
    }

    /**
     * If the given instant falls outside market hours (9:15 AM - 3:30 PM IST, Mon-Fri),
     * snap forward to the next market open.
     */
    private Instant snapToMarketTime(Instant instant) {
        ZonedDateTime zdt = instant.atZone(IST);

        // Skip weekends
        while (zdt.getDayOfWeek() == DayOfWeek.SATURDAY || zdt.getDayOfWeek() == DayOfWeek.SUNDAY) {
            zdt = zdt.toLocalDate().plusDays(1).atTime(MARKET_OPEN).atZone(IST);
        }

        LocalTime time = zdt.toLocalTime();

        // Before market open → snap to open
        if (time.isBefore(MARKET_OPEN)) {
            zdt = zdt.toLocalDate().atTime(MARKET_OPEN).atZone(IST);
        }
        // After market close → snap to next trading day open
        else if (time.isAfter(MARKET_CLOSE)) {
            zdt = zdt.toLocalDate().plusDays(1).atTime(MARKET_OPEN).atZone(IST);
            // Skip weekends again
            while (zdt.getDayOfWeek() == DayOfWeek.SATURDAY || zdt.getDayOfWeek() == DayOfWeek.SUNDAY) {
                zdt = zdt.toLocalDate().plusDays(1).atTime(MARKET_OPEN).atZone(IST);
            }
        }

        return zdt.toInstant();
    }

    public void start() { running = true; completed = false; }
    public void stop() { running = false; }
}
