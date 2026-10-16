package fr.backyard.tracker.comptes.infrastructure;

import fr.backyard.tracker.comptes.domaine.HachageSatureException;
import java.time.Duration;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * Plafond de concurrence des calculs Argon2id (SEC-06) : au plus {@code concurrenceMax} calculs simultanés, soit
 * environ {@code concurrenceMax x 19 Mio} de mémoire, quel que soit le nombre de requêtes reçues. Une demande attend
 * une place au plus {@code attenteMax}, puis est refusée par {@link HachageSatureException} (503 côté API).
 * Le plafond est global (toutes adresses confondues) : il protège la mémoire et le processeur, pas un compte.
 */
final class LimiteurHachage {

    private final Semaphore places;
    private final Duration attenteMax;

    LimiteurHachage(int concurrenceMax, Duration attenteMax) {
        if (concurrenceMax < 1) {
            throw new IllegalArgumentException("La concurrence maximale du hachage doit être au moins 1.");
        }
        if (attenteMax.isNegative()) {
            throw new IllegalArgumentException("L'attente maximale du hachage ne peut pas être négative.");
        }
        this.places = new Semaphore(concurrenceMax, true);
        this.attenteMax = attenteMax;
    }

    /** @throws HachageSatureException aucune place libérée dans le délai, ou attente interrompue */
    <T> T executer(Supplier<T> calcul) {
        prendrePlace();
        try {
            return calcul.get();
        } finally {
            places.release();
        }
    }

    private void prendrePlace() {
        try {
            if (!places.tryAcquire(attenteMax.toMillis(), TimeUnit.MILLISECONDS)) {
                throw new HachageSatureException();
            }
        } catch (InterruptedException interruption) {
            Thread.currentThread().interrupt();
            throw new HachageSatureException(interruption);
        }
    }
}
