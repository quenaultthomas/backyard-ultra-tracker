package fr.backyard.tracker.comptes.integration;

import static org.assertj.core.api.Assertions.assertThat;

import fr.backyard.tracker.comptes.domaine.PolitiqueBlocage;
import fr.backyard.tracker.comptes.infrastructure.RegistreTentativesConnexionEnMemoire;
import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** CA22 (incrément 1.3) : adaptateur mémoire du registre, sans base de données. */
class RegistreTentativesConnexionEnMemoireIntegrationTest {

    static final int CAPACITE = RegistreTentativesConnexionEnMemoire.CAPACITE;
    static final Instant T = Instant.parse("2026-09-15T10:00:00Z");

    final RegistreTentativesConnexionEnMemoire registre = new RegistreTentativesConnexionEnMemoire();

    @Test
    @DisplayName("CA22 : 10 001 pseudos distincts en échec, le registre contient au plus 10 000 clés, la plus ancienne est évincée")
    void ca22_capacite_bornee() {
        PolitiqueBlocage politique = PolitiqueBlocage.parDefaut();

        for (int i = 0; i <= CAPACITE; i++) {
            registre.enregistrerEchec("pseudo" + i, T.plusMillis(i), politique);
        }

        assertThat(registre.taille()).isEqualTo(CAPACITE);
        assertThat(registre.constater("pseudo0", T.plusMillis(CAPACITE)).echecs()).isZero();
        assertThat(registre.constater("pseudo1", T.plusMillis(CAPACITE)).echecs()).isEqualTo(1);
        assertThat(registre.constater("pseudo" + CAPACITE, T.plusMillis(CAPACITE)).echecs()).isEqualTo(1);
    }

    @Test
    @DisplayName("CA22 : les entrées expirées sont purgées avant toute éviction")
    void ca22_purge_des_expirees_avant_eviction() {
        PolitiqueBlocage politique = PolitiqueBlocage.parDefaut();
        for (int i = 0; i < CAPACITE / 2; i++) {
            registre.enregistrerEchec("ancien" + i, T, politique);
        }
        Instant plusTard = T.plusSeconds(1000);
        for (int i = 0; i < CAPACITE / 2; i++) {
            registre.enregistrerEchec("recent" + i, plusTard, politique);
        }
        assertThat(registre.taille()).isEqualTo(CAPACITE);

        registre.enregistrerEchec("nouveau", plusTard, politique);

        // Les 5 000 anciennes (oubliées) sont purgées ; aucune entrée récente n'est évincée.
        assertThat(registre.taille()).isEqualTo(CAPACITE / 2 + 1);
        assertThat(registre.constater("recent0", plusTard).echecs()).isEqualTo(1);
        assertThat(registre.constater("nouveau", plusTard).echecs()).isEqualTo(1);
        assertThat(registre.constater("ancien0", plusTard).echecs()).isZero();
    }

    @Test
    @DisplayName("CA22 : une clé bloquée non expirée n'est pas évincée tant qu'une clé non bloquée existe")
    void ca22_cle_bloquee_evincee_en_dernier_recours_seulement() {
        PolitiqueBlocage politique = new PolitiqueBlocage(2, 900);
        registre.enregistrerEchec("bloquee", T, politique);
        registre.enregistrerEchec("bloquee", T, politique);
        for (int i = 1; i < CAPACITE; i++) {
            registre.enregistrerEchec("partiel" + i, T.plusMillis(i), politique);
        }
        Instant maintenant = T.plusSeconds(10);
        assertThat(registre.taille()).isEqualTo(CAPACITE);

        registre.enregistrerEchec("nouveau", maintenant, politique);

        assertThat(registre.taille()).isEqualTo(CAPACITE);
        assertThat(registre.constater("bloquee", maintenant).estBloquee(maintenant)).isTrue();
        assertThat(registre.constater("partiel1", maintenant).echecs()).isZero();
        assertThat(registre.constater("partiel2", maintenant).echecs()).isEqualTo(1);
    }

    @Test
    @DisplayName("CA22 : si toutes les clés sont bloquées, la plus ancienne est évincée en dernier recours")
    void ca22_toutes_bloquees_la_plus_ancienne_evincee() {
        PolitiqueBlocage politique = new PolitiqueBlocage(1, 900);
        for (int i = 0; i < CAPACITE; i++) {
            registre.enregistrerEchec("bloquee" + i, T.plusMillis(i), politique);
        }
        Instant maintenant = T.plusSeconds(10);

        registre.enregistrerEchec("nouveau", maintenant, politique);

        assertThat(registre.taille()).isEqualTo(CAPACITE);
        assertThat(registre.constater("bloquee0", maintenant).estBloquee(maintenant)).isFalse();
        assertThat(registre.constater("bloquee1", maintenant).estBloquee(maintenant)).isTrue();
        assertThat(registre.constater("nouveau", maintenant).estBloquee(maintenant)).isTrue();
    }
}
