package fr.backyard.tracker.comptes.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import fr.backyard.tracker.comptes.domaine.HachageSatureException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Incrément 7.3, RG3 (SEC-06) : plafond de hachage par adresse cliente en plus du plafond global (4 places, P = 1,
 * attente 200 ms). Signatures attendues : {@code new LimiteurHachage(int concurrenceMax, int concurrenceMaxParAdresse,
 * Duration attenteMax)}, {@code <T> T executer(String adresse, Supplier<T> calcul)} (adresse {@code null} = inconnue,
 * seul le plafond global s'applique), {@code int nombreAdressesSuivies()} (méthode de test, package-privée).
 */
class LimiteurHachageParAdresseTest {

    private static final String A = "203.0.113.1";
    private static final String B = "203.0.113.2";
    private static final String C = "203.0.113.3";
    private static final String D = "203.0.113.4";
    private static final String E = "203.0.113.5";

    private LimiteurHachage limiteur;
    private ExecutorService executeur;
    private CountDownLatch verrou;
    private final AtomicInteger simultanes = new AtomicInteger();
    private final AtomicInteger maxSimultanes = new AtomicInteger();

    @BeforeEach
    void preparer() {
        limiteur = new LimiteurHachage(4, 1, Duration.ofMillis(200));
        executeur = Executors.newCachedThreadPool();
        verrou = new CountDownLatch(1);
    }

    @AfterEach
    void nettoyer() {
        verrou.countDown();
        executeur.shutdownNow();
    }

    /** Lance un calcul bloqué par le verrou ; rend la main quand il a pris ses places (ou a été refusé). */
    private Future<String> lancerBloque(String adresse) {
        CountDownLatch demarre = new CountDownLatch(1);
        Future<String> futur = executeur.submit(() -> limiteur.executer(adresse, () -> {
            int courant = simultanes.incrementAndGet();
            maxSimultanes.accumulateAndGet(courant, Math::max);
            demarre.countDown();
            try {
                return verrou.await(10, TimeUnit.SECONDS) ? "ok" : "delai";
            } catch (InterruptedException interruption) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("interrompu", interruption);
            } finally {
                simultanes.decrementAndGet();
            }
        }));
        attendreDemarrageOuRefus(demarre, futur);
        return futur;
    }

    private static void attendreDemarrageOuRefus(CountDownLatch demarre, Future<String> futur) {
        try {
            long limite = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (demarre.getCount() > 0 && !futur.isDone() && System.nanoTime() < limite) {
                demarre.await(5, TimeUnit.MILLISECONDS);
            }
        } catch (InterruptedException interruption) {
            Thread.currentThread().interrupt();
        }
    }

    private static Throwable causeDe(Future<String> futur) {
        try {
            futur.get(5, TimeUnit.SECONDS);
            return null;
        } catch (ExecutionException echec) {
            return echec.getCause();
        } catch (Exception autre) {
            throw new AssertionError(autre);
        }
    }

    private long dureeMillisecondes(Runnable action) {
        long debut = System.nanoTime();
        action.run();
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - debut);
    }

    @Test
    @DisplayName("CA3 - une adresse qui lance 6 calculs simultanés n'en exécute qu'un, les 5 autres sont saturés après ~200 ms")
    void doit_limiter_une_adresse_a_un_calcul_simultane() {
        Future<String> premier = lancerBloque(A);
        List<Future<String>> autres = new ArrayList<>();
        long duree = dureeMillisecondes(() -> {
            for (int i = 0; i < 5; i++) {
                autres.add(executeur.submit(() -> limiteur.executer(A, () -> "refusé")));
            }
            autres.forEach(f -> assertThat(causeDe(f)).isInstanceOf(HachageSatureException.class));
        });

        assertThat(maxSimultanes.get()).isEqualTo(1);
        assertThat(duree).isGreaterThanOrEqualTo(150);
        assertThat(premier.isDone()).isFalse();
    }

    @Test
    @DisplayName("CA3 - pendant la rafale de A, un calcul de l'adresse B s'exécute sans attente (< 100 ms)")
    void doit_servir_une_autre_adresse_sans_attente_pendant_la_rafale() {
        lancerBloque(A);
        executeur.submit(() -> limiteur.executer(A, () -> "refusé"));

        long duree = dureeMillisecondes(() -> assertThat(limiteur.executer(B, () -> "servi")).isEqualTo("servi"));

        assertThat(duree).isLessThan(100);
    }

    @Test
    @DisplayName("CA3 - quatre adresses bloquées occupent toutes les places globales : l'adresse E est saturée")
    void doit_saturer_une_cinquieme_adresse_quand_les_quatre_places_globales_sont_prises() {
        lancerBloque(A);
        lancerBloque(B);
        lancerBloque(C);
        lancerBloque(D);

        assertThatThrownBy(() -> limiteur.executer(E, () -> "refusé")).isInstanceOf(HachageSatureException.class);
    }

    @Test
    @DisplayName("CA3 - même exception de saturation pour le plafond par adresse et le plafond global")
    void doit_lever_la_meme_exception_pour_les_deux_plafonds() {
        lancerBloque(A);
        Throwable parAdresse = catchThrowable(() -> limiteur.executer(A, () -> "refusé"));
        lancerBloque(B);
        lancerBloque(C);
        lancerBloque(D);
        Throwable global = catchThrowable(() -> limiteur.executer(E, () -> "refusé"));

        assertThat(parAdresse).isInstanceOf(HachageSatureException.class);
        assertThat(global).isInstanceOf(HachageSatureException.class);
        assertThat(global.getMessage()).isEqualTo(parAdresse.getMessage());
    }

    @Test
    @DisplayName("CA4 - les places d'adresse et globale sont rendues après un succès, une exception et une interruption")
    void doit_rendre_les_places_dans_tous_les_cas() {
        assertThat(limiteur.executer(A, () -> "succès")).isEqualTo("succès");
        assertThatThrownBy(() -> limiteur.executer(A, () -> {
            throw new IllegalStateException("panne");
        })).isInstanceOf(IllegalStateException.class);
        Thread.currentThread().interrupt();
        try {
            assertThatThrownBy(() -> limiteur.executer(A, () -> "jamais")).isInstanceOf(HachageSatureException.class);
        } finally {
            Thread.interrupted();
        }

        long duree = dureeMillisecondes(() -> assertThat(limiteur.executer(A, () -> "suivant")).isEqualTo("suivant"));
        assertThat(duree).isLessThan(100);
        assertThat(limiteur.nombreAdressesSuivies()).isZero();
    }

    @Test
    @DisplayName("CA4 - sans adresse (inconnue), seul le plafond global s'applique : le 5e calcul est saturé")
    void doit_appliquer_le_seul_plafond_global_sans_adresse() {
        for (int i = 0; i < 4; i++) {
            lancerBloque(null);
        }

        assertThat(maxSimultanes.get()).isEqualTo(4);
        assertThatThrownBy(() -> limiteur.executer(null, () -> "refusé")).isInstanceOf(HachageSatureException.class);
    }

    @Test
    @DisplayName("CA4 - après la fin de tous les calculs, l'état par adresse est vide")
    void doit_supprimer_l_etat_par_adresse_apres_les_calculs() throws Exception {
        Future<String> a = lancerBloque(A);
        Future<String> b = lancerBloque(B);
        assertThat(limiteur.nombreAdressesSuivies()).isEqualTo(2);

        verrou.countDown();
        a.get(5, TimeUnit.SECONDS);
        b.get(5, TimeUnit.SECONDS);

        assertThat(limiteur.nombreAdressesSuivies()).isZero();
    }

    @Test
    @DisplayName("CA4 - l'état d'une adresse refusée par saturation est lui aussi supprimé")
    void doit_supprimer_l_etat_d_une_adresse_refusee() throws Exception {
        Future<String> occupant = lancerBloque(A);
        assertThatThrownBy(() -> limiteur.executer(A, () -> "refusé")).isInstanceOf(HachageSatureException.class);

        verrou.countDown();
        occupant.get(5, TimeUnit.SECONDS);

        assertThat(limiteur.nombreAdressesSuivies()).isZero();
    }

    private static Throwable catchThrowable(Runnable action) {
        try {
            action.run();
            return null;
        } catch (Throwable levee) {
            return levee;
        }
    }
}
