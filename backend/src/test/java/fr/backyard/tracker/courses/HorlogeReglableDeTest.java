package fr.backyard.tracker.courses;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

/** Horloge de test déplaçable à volonté (en avant comme en arrière), fixe entre deux déplacements (7.2). */
public final class HorlogeReglableDeTest extends Clock {

    private Instant instant;

    public HorlogeReglableDeTest(Instant instant) {
        this.instant = instant;
    }

    public void placerA(Instant nouvelInstant) {
        this.instant = nouvelInstant;
    }

    @Override
    public ZoneId getZone() {
        return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
        return this;
    }

    @Override
    public Instant instant() {
        return instant;
    }
}
