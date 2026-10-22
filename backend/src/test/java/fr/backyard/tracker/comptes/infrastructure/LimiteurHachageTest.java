package fr.backyard.tracker.comptes.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import fr.backyard.tracker.comptes.domaine.HachageSatureException;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** SEC-06 (audit 2026-10-16) : plafond de concurrence des calculs d'empreinte. Test unitaire sans Spring. */
class LimiteurHachageTest {

    @Test
    @DisplayName("SEC-06 : un calcul sous le plafond renvoie son résultat et libère sa place")
    void doit_executer_le_calcul_et_liberer_la_place() {
        LimiteurHachage limiteur = new LimiteurHachage(1, 1, Duration.ZERO);

        assertThat(limiteur.executer(() -> "empreinte")).isEqualTo("empreinte");
        assertThat(limiteur.executer(() -> "seconde")).isEqualTo("seconde");
    }

    @Test
    @DisplayName("SEC-06 : la place est libérée même si le calcul échoue")
    void doit_liberer_la_place_quand_le_calcul_echoue() {
        LimiteurHachage limiteur = new LimiteurHachage(1, 1, Duration.ZERO);

        assertThatThrownBy(() -> limiteur.executer(() -> {
            throw new IllegalStateException("panne");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(limiteur.executer(() -> true)).isTrue();
    }

    @Test
    @DisplayName("SEC-06 : au-delà du plafond, la demande est refusée après l'attente maximale")
    void doit_refuser_une_demande_au_dela_du_plafond() throws Exception {
        LimiteurHachage limiteur = new LimiteurHachage(1, 1, Duration.ofMillis(50));
        CountDownLatch enCours = new CountDownLatch(1);
        CountDownLatch liberer = new CountDownLatch(1);
        ExecutorService executeur = Executors.newSingleThreadExecutor();
        try {
            Future<Boolean> occupant = executeur.submit(() -> limiteur.executer(() -> {
                enCours.countDown();
                return attendre(liberer);
            }));
            assertThat(enCours.await(5, TimeUnit.SECONDS)).isTrue();

            assertThatThrownBy(() -> limiteur.executer(() -> "refusé"))
                    .isInstanceOf(HachageSatureException.class)
                    .hasMessage("Le service est momentanément saturé. Réessayez dans quelques secondes.");

            liberer.countDown();
            assertThat(occupant.get(5, TimeUnit.SECONDS)).isTrue();
            assertThat(limiteur.executer(() -> "accepté")).isEqualTo("accepté");
        } finally {
            executeur.shutdownNow();
        }
    }

    @Test
    @DisplayName("SEC-06 : une attente interrompue est refusée en conservant l'interruption et sa cause")
    void doit_refuser_une_attente_interrompue() {
        LimiteurHachage limiteur = new LimiteurHachage(1, 1, Duration.ofSeconds(5));
        Thread.currentThread().interrupt();
        try {
            assertThatThrownBy(() -> limiteur.executer(() -> "jamais"))
                    .isInstanceOf(HachageSatureException.class)
                    .hasCauseInstanceOf(InterruptedException.class);
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    @DisplayName("SEC-06 : une configuration invalide est refusée")
    void doit_refuser_une_configuration_invalide() {
        assertThatThrownBy(() -> new LimiteurHachage(0, 1, Duration.ZERO)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new LimiteurHachage(1, 1, Duration.ofMillis(-1)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static boolean attendre(CountDownLatch verrou) {
        try {
            return verrou.await(5, TimeUnit.SECONDS);
        } catch (InterruptedException interruption) {
            Thread.currentThread().interrupt();
            return false;
        }
    }
}
