package fr.backyard.tracker.comptes.infrastructure;

import fr.backyard.tracker.comptes.domaine.ReglageHorloge;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Horloge pilotable du profil e2e (4.3 RG2) : instant de l'horloge de base plus un décalage cumulé. Le temps continue
 * de s'écouler à la vitesse réelle ; le décalage est partagé par tout le processus (et par les vues
 * {@link #withZone(ZoneId)}) et modifié de façon atomique. Additionne sans autre règle (bornes : PiloterHorloge).
 */
public final class HorlogeDecalee extends Clock implements ReglageHorloge {

    private final Clock base;
    private final ZoneId fuseau;
    private final AtomicReference<Duration> decalage;

    public HorlogeDecalee(Clock base) {
        this(base, ZoneOffset.UTC, new AtomicReference<>(Duration.ZERO));
    }

    private HorlogeDecalee(Clock base, ZoneId fuseau, AtomicReference<Duration> decalage) {
        this.base = base;
        this.fuseau = fuseau;
        this.decalage = decalage;
    }

    @Override
    public ZoneId getZone() {
        return fuseau;
    }

    /** Vue dans un autre fuseau, qui partage la base et le décalage. */
    @Override
    public Clock withZone(ZoneId zone) {
        return new HorlogeDecalee(base, zone, decalage);
    }

    @Override
    public Instant instant() {
        return base.instant().plus(decalage.get());
    }

    @Override
    public Duration decalage() {
        return decalage.get();
    }

    @Override
    public Duration avancer(Duration duree) {
        return decalage.accumulateAndGet(duree, Duration::plus);
    }

    @Override
    public void reinitialiser() {
        decalage.set(Duration.ZERO);
    }
}
