package fr.backyard.tracker.comptes.domaine;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class TentativesConnexionTest {

    private static final Instant T = Instant.parse("2026-10-02T10:00:00Z");
    private static final PolitiqueBlocage POLITIQUE = new PolitiqueBlocage(5, 900);

    private static TentativesConnexion apresEchecs(int nombre, Instant instant, PolitiqueBlocage politique) {
        TentativesConnexion tentatives = TentativesConnexion.aucune();
        for (int i = 0; i < nombre; i++) {
            tentatives = tentatives.apresEchec(instant, politique);
        }
        return tentatives;
    }

    @Test
    @DisplayName("CA1 - 4 échecs ne bloquent pas, le 5e fixe le blocage à maintenant + 900 s")
    void doit_bloquer_au_cinquieme_echec_pour_la_duree_configuree() {
        assertThat(apresEchecs(4, T, POLITIQUE).estBloquee(T)).isFalse();

        TentativesConnexion bloquee = apresEchecs(5, T, POLITIQUE);

        assertThat(bloquee.estBloquee(T)).isTrue();
        assertThat(bloquee.bloqueJusqu()).contains(T.plusSeconds(900));
    }

    @Test
    @DisplayName("CA2 - bloquée à bloqueJusqu moins 0,5 s, débloquée à bloqueJusqu exactement")
    void doit_etre_debloquee_a_l_instant_exact_de_fin() {
        TentativesConnexion bloquee = apresEchecs(5, T, POLITIQUE);

        assertThat(bloquee.estBloquee(T.plusSeconds(900).minusMillis(500))).isTrue();
        assertThat(bloquee.estBloquee(T.plusSeconds(900))).isFalse();
        assertThat(bloquee.estBloquee(T.plusSeconds(901))).isFalse();
    }

    @Test
    @DisplayName("CA2 - le temps restant est la durée exacte jusqu'à bloqueJusqu")
    void doit_exposer_le_temps_restant() {
        TentativesConnexion bloquee = apresEchecs(5, T, POLITIQUE);

        assertThat(bloquee.tempsRestant(T.plusSeconds(10))).isEqualTo(Duration.ofSeconds(890));
    }

    @Test
    @DisplayName("CA2 - après l'expiration du blocage, le compteur repart de 0 : 4 échecs ne bloquent pas, le 5e bloque")
    void doit_repartir_de_zero_apres_expiration_du_blocage() {
        TentativesConnexion bloquee = apresEchecs(5, T, POLITIQUE);
        Instant apres = T.plusSeconds(900);

        TentativesConnexion quatre = bloquee;
        for (int i = 0; i < 4; i++) {
            quatre = quatre.apresEchec(apres, POLITIQUE);
        }
        assertThat(quatre.estBloquee(apres)).isFalse();
        assertThat(quatre.apresEchec(apres, POLITIQUE).estBloquee(apres)).isTrue();
    }

    @Test
    @DisplayName("CA6 - un 5e échec à T+899 s, après 4 échecs à T, déclenche le blocage")
    void doit_bloquer_quand_le_dernier_echec_partiel_date_de_moins_de_la_duree() {
        TentativesConnexion quatre = apresEchecs(4, T, POLITIQUE);
        Instant t899 = T.plusSeconds(899);

        TentativesConnexion apres = quatre.apresEchec(t899, POLITIQUE);

        assertThat(apres.estBloquee(t899)).isTrue();
        assertThat(apres.bloqueJusqu()).contains(t899.plusSeconds(900));
    }

    @Test
    @DisplayName("CA6 - un échec à T+900 s, après 4 échecs à T, oublie les échecs partiels : compteur 1")
    void doit_oublier_les_echecs_partiels_apres_la_duree_de_blocage() {
        TentativesConnexion quatre = apresEchecs(4, T, POLITIQUE);
        Instant t900 = T.plusSeconds(900);

        TentativesConnexion apres = quatre.apresEchec(t900, POLITIQUE);

        assertThat(apres.echecs()).isEqualTo(1);
        assertThat(apres.estBloquee(t900)).isFalse();
        TentativesConnexion encoreTrois = apres.apresEchec(t900, POLITIQUE)
                .apresEchec(t900, POLITIQUE).apresEchec(t900, POLITIQUE);
        assertThat(encoreTrois.estBloquee(t900)).isFalse();
    }

    @Test
    @DisplayName("CA10 - avec echecsMax = 1, le premier échec bloque")
    void doit_bloquer_des_le_premier_echec_quand_echecs_max_vaut_1() {
        TentativesConnexion apres = TentativesConnexion.aucune().apresEchec(T, new PolitiqueBlocage(1, 1));

        assertThat(apres.estBloquee(T)).isTrue();
        assertThat(apres.estBloquee(T.plusSeconds(1))).isFalse();
    }

    @Test
    @DisplayName("CA7 - une entrée vierge n'est pas bloquée et n'a aucun échec")
    void doit_ne_pas_etre_bloquee_sans_echec() {
        TentativesConnexion aucune = TentativesConnexion.aucune();

        assertThat(aucune.estBloquee(T)).isFalse();
        assertThat(aucune.echecs()).isZero();
        assertThat(aucune.bloqueJusqu()).isEmpty();
    }
}
