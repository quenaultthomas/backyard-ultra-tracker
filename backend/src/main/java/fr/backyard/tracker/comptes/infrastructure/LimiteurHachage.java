package fr.backyard.tracker.comptes.infrastructure;

import fr.backyard.tracker.comptes.domaine.HachageSatureException;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Plafonds de concurrence des calculs Argon2id (SEC-06, 7.3 RG3), dans cet ordre : au plus
 * {@code concurrenceMaxParAdresse} calculs simultanés par adresse cliente, puis au plus {@code concurrenceMax} au
 * total (environ {@code concurrenceMax x 19 Mio} de mémoire). Une demande n'attend une place globale qu'une fois sa
 * place d'adresse obtenue : une adresse n'occupe jamais plus de {@code concurrenceMaxParAdresse} places globales.
 * Chaque attente dure au plus {@code attenteMax}, puis {@link HachageSatureException} (503 côté API), le même refus
 * pour les deux plafonds. La décision ne dépend que de l'adresse et des places, jamais du compte. Adresse inconnue
 * ({@code null}) : plafond global seul. L'état d'une adresse n'existe que tant qu'une de ses demandes tient ou attend
 * une place.
 */
final class LimiteurHachage {

    private static final Logger JOURNAL = LoggerFactory.getLogger(LimiteurHachage.class);

    private final Semaphore placesGlobales;
    private final int concurrenceMaxParAdresse;
    private final Duration attenteMax;
    private final Map<String, PlacesAdresse> placesParAdresse = new ConcurrentHashMap<>();

    /** Places d'une adresse et nombre de demandes qui les tiennent ou les attendent (modifié sous verrou de la map). */
    private static final class PlacesAdresse {
        private final Semaphore places;
        private int demandes;

        private PlacesAdresse(int concurrenceMax) {
            this.places = new Semaphore(concurrenceMax, true);
        }
    }

    LimiteurHachage(int concurrenceMax, int concurrenceMaxParAdresse, Duration attenteMax) {
        if (concurrenceMax < 1 || concurrenceMaxParAdresse < 1) {
            throw new IllegalArgumentException("Les concurrences maximales du hachage doivent être au moins 1.");
        }
        if (attenteMax.isNegative()) {
            throw new IllegalArgumentException("L'attente maximale du hachage ne peut pas être négative.");
        }
        this.placesGlobales = new Semaphore(concurrenceMax, true);
        this.concurrenceMaxParAdresse = concurrenceMaxParAdresse;
        this.attenteMax = attenteMax;
    }

    /** Adresse inconnue : plafond global seul. */
    <T> T executer(Supplier<T> calcul) {
        return executer(null, calcul);
    }

    /**
     * @param adresse adresse cliente, {@code null} si inconnue
     * @throws HachageSatureException aucune place libérée dans le délai, ou attente interrompue
     */
    <T> T executer(String adresse, Supplier<T> calcul) {
        if (adresse == null) {
            return executerSousPlafondGlobal(calcul);
        }
        PlacesAdresse places = suivre(adresse);
        try {
            prendre(places.places, "plafond par adresse");
            try {
                return executerSousPlafondGlobal(calcul);
            } finally {
                places.places.release();
            }
        } finally {
            oublierSiInutilise(adresse);
        }
    }

    /** Nombre d'adresses dont l'état est conservé (tests). */
    int nombreAdressesSuivies() {
        return placesParAdresse.size();
    }

    private <T> T executerSousPlafondGlobal(Supplier<T> calcul) {
        prendre(placesGlobales, "plafond global");
        try {
            return calcul.get();
        } finally {
            placesGlobales.release();
        }
    }

    private PlacesAdresse suivre(String adresse) {
        return placesParAdresse.compute(adresse, (cle, existantes) -> {
            PlacesAdresse places = existantes != null ? existantes : new PlacesAdresse(concurrenceMaxParAdresse);
            places.demandes++;
            return places;
        });
    }

    private void oublierSiInutilise(String adresse) {
        placesParAdresse.computeIfPresent(adresse, (cle, places) -> --places.demandes == 0 ? null : places);
    }

    private void prendre(Semaphore places, String plafond) {
        try {
            if (!places.tryAcquire(attenteMax.toMillis(), TimeUnit.MILLISECONDS)) {
                JOURNAL.warn("Requête refusée : calculs d'empreinte saturés ({})", plafond);
                throw new HachageSatureException();
            }
        } catch (InterruptedException interruption) {
            Thread.currentThread().interrupt();
            throw new HachageSatureException(interruption);
        }
    }
}
