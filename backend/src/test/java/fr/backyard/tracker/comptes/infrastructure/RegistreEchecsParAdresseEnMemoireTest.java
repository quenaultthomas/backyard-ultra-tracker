package fr.backyard.tracker.comptes.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import fr.backyard.tracker.comptes.domaine.AdresseSuivie;
import fr.backyard.tracker.comptes.domaine.EchecsParAdresse;
import fr.backyard.tracker.comptes.domaine.PlafondEchecsParAdresse;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Incrément 8.4, CA9 : registre mémoire borné et atomique, sans Spring (RG9, RG10). */
class RegistreEchecsParAdresseEnMemoireTest {

    private static final int CAPACITE = RegistreEchecsParAdresseEnMemoire.CAPACITE;
    private static final Instant T = Instant.parse("2026-10-26T10:00:00Z");
    private static final PlafondEchecsParAdresse TROIS_PAR_MINUTE = new PlafondEchecsParAdresse(3, 60);

    private final RegistreEchecsParAdresseEnMemoire registre = new RegistreEchecsParAdresseEnMemoire();

    private static AdresseSuivie adresse(int numero) {
        return AdresseSuivie.depuis("10." + ((numero >> 16) & 255) + "." + ((numero >> 8) & 255) + "." + (numero & 255));
    }

    private void echouer(int numeroAdresse, Instant instant, int fois) {
        for (int i = 0; i < fois; i++) {
            registre.enregistrerEchec(adresse(numeroAdresse), instant, TROIS_PAR_MINUTE);
        }
    }

    @Test
    @DisplayName("CA9 - 10 001 adresses distinctes qui échouent une fois : la taille reste au plus 10 000")
    void doit_borner_le_nombre_d_adresses_suivies() {
        for (int i = 0; i <= CAPACITE; i++) {
            echouer(i, T, 1);
        }

        assertThat(registre.taille()).isLessThanOrEqualTo(CAPACITE);
    }

    @Test
    @DisplayName("CA9 - à saturation, les adresses expirées sont purgées avant toute éviction d'adresse active")
    void doit_purger_les_adresses_expirees_avant_d_evincer() {
        Instant plusTard = T.plusSeconds(100);
        for (int i = 0; i < CAPACITE / 2; i++) {
            echouer(i, T, 1);
        }
        for (int i = CAPACITE / 2; i < CAPACITE; i++) {
            echouer(i, plusTard, 1);
        }
        assertThat(registre.taille()).isEqualTo(CAPACITE);

        echouer(CAPACITE, plusTard, 1);

        assertThat(registre.taille()).isEqualTo(CAPACITE / 2 + 1);
        assertThat(registre.constater(adresse(CAPACITE / 2), plusTard).instantsRetenus()).hasSize(1);
        assertThat(registre.constater(adresse(CAPACITE), plusTard).instantsRetenus()).hasSize(1);
        assertThat(registre.constater(adresse(0), plusTard).instantsRetenus()).isEmpty();
    }

    @Test
    @DisplayName("CA9 - à saturation sans entrée expirée, l'adresse au dernier échec le plus ancien est évincée")
    void doit_evincer_l_adresse_au_dernier_echec_le_plus_ancien() {
        for (int i = 0; i < CAPACITE; i++) {
            echouer(i, T.plusMillis(i), 1);
        }

        echouer(CAPACITE, T.plusSeconds(20), 1);

        assertThat(registre.taille()).isEqualTo(CAPACITE);
        assertThat(registre.constater(adresse(0), T.plusSeconds(20)).instantsRetenus()).isEmpty();
        assertThat(registre.constater(adresse(1), T.plusSeconds(20)).instantsRetenus()).hasSize(1);
        assertThat(registre.constater(adresse(CAPACITE), T.plusSeconds(20)).instantsRetenus()).hasSize(1);
    }

    @Test
    @DisplayName("CA9 - une adresse plafonnée, même la plus ancienne, n'est évincée que si aucune adresse non plafonnée n'existe")
    void doit_proteger_une_adresse_plafonnee_de_l_eviction() {
        echouer(0, T, 3);
        for (int i = 1; i < CAPACITE; i++) {
            echouer(i, T.plusSeconds(1).plusMillis(i), 1);
        }

        echouer(CAPACITE, T.plusSeconds(20), 1);

        assertThat(registre.taille()).isEqualTo(CAPACITE);
        assertThat(registre.constater(adresse(0), T.plusSeconds(20)).estPlafonnee(T.plusSeconds(20), TROIS_PAR_MINUTE))
                .isTrue();
        assertThat(registre.constater(adresse(1), T.plusSeconds(20)).instantsRetenus()).isEmpty();
    }

    @Test
    @DisplayName("CA9 - si toutes les adresses sont plafonnées, la plus ancienne est évincée en dernier recours")
    void doit_evincer_une_adresse_plafonnee_en_dernier_recours() {
        for (int i = 0; i < CAPACITE; i++) {
            echouer(i, T.plusMillis(i), 3);
        }

        echouer(CAPACITE, T.plusSeconds(20), 1);

        assertThat(registre.taille()).isEqualTo(CAPACITE);
        assertThat(registre.constater(adresse(0), T.plusSeconds(20)).instantsRetenus()).isEmpty();
        assertThat(registre.constater(adresse(1), T.plusSeconds(20)).instantsRetenus()).hasSize(3);
    }

    @Test
    @DisplayName("CA9 - constater sur une adresse dont tous les échecs sont expirés la supprime")
    void doit_supprimer_une_adresse_expiree_a_la_lecture() {
        echouer(1, T, 1);
        assertThat(registre.taille()).isEqualTo(1);

        EchecsParAdresse constate = registre.constater(adresse(1), T.plusSeconds(60));

        assertThat(constate.estPlafonnee(T.plusSeconds(60), TROIS_PAR_MINUTE)).isFalse();
        assertThat(registre.taille()).isZero();
    }

    @Test
    @DisplayName("CA9 - constater une adresse inconnue ne crée aucune entrée")
    void doit_ne_rien_creer_en_lecture() {
        registre.constater(adresse(1), T);

        assertThat(registre.taille()).isZero();
    }

    @Test
    @DisplayName("CA9 - vider() remet le registre à zéro")
    void doit_etre_vide_apres_vider() {
        echouer(1, T, 1);

        registre.vider();

        assertThat(registre.taille()).isZero();
    }

    @Test
    @DisplayName("CA9 - 8 fils x 50 échecs simultanés sur la même adresse : aucune exception, 3 instants retenus, plafonnée")
    void doit_rester_coherent_sous_acces_concurrents() throws Exception {
        int fils = 8;
        ExecutorService executeur = Executors.newFixedThreadPool(fils);
        CountDownLatch depart = new CountDownLatch(1);
        List<Future<?>> taches = new ArrayList<>();
        for (int f = 0; f < fils; f++) {
            taches.add(executeur.submit(() -> {
                depart.await();
                for (int i = 0; i < 50; i++) {
                    registre.enregistrerEchec(adresse(1), T, TROIS_PAR_MINUTE);
                }
                return null;
            }));
        }
        depart.countDown();
        for (Future<?> tache : taches) {
            tache.get(10, TimeUnit.SECONDS);
        }
        executeur.shutdown();

        EchecsParAdresse etat = registre.constater(adresse(1), T);

        assertThat(etat.instantsRetenus()).hasSize(3);
        assertThat(etat.estPlafonnee(T, TROIS_PAR_MINUTE)).isTrue();
        assertThat(registre.taille()).isEqualTo(1);
    }
}
