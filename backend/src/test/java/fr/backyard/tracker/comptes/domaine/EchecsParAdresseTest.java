package fr.backyard.tracker.comptes.domaine;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Incrément 8.4, CA1 et CA2 : fenêtre glissante des échecs par adresse (RG1, RG6, RG7, RG9, RG10). */
class EchecsParAdresseTest {

    private static final Instant T = Instant.parse("2026-10-26T10:00:00Z");
    private static final PlafondEchecsParAdresse TROIS_PAR_MINUTE = new PlafondEchecsParAdresse(3, 60);

    private static EchecsParAdresse apresEchecsA(PlafondEchecsParAdresse plafond, Instant... instants) {
        EchecsParAdresse echecs = EchecsParAdresse.aucun();
        for (Instant instant : instants) {
            echecs = echecs.apresEchec(instant, plafond);
        }
        return echecs;
    }

    private static EchecsParAdresse nEchecsA(PlafondEchecsParAdresse plafond, int nombre, Instant instant) {
        EchecsParAdresse echecs = EchecsParAdresse.aucun();
        for (int i = 0; i < nombre; i++) {
            echecs = echecs.apresEchec(instant, plafond);
        }
        return echecs;
    }

    @Test
    @DisplayName("CA1 - une adresse sans échec n'est pas plafonnée et son temps restant est nul")
    void doit_ne_pas_plafonner_une_adresse_sans_echec() {
        EchecsParAdresse aucun = EchecsParAdresse.aucun();

        assertThat(aucun.estPlafonnee(T, TROIS_PAR_MINUTE)).isFalse();
        assertThat(aucun.tempsRestant(T, TROIS_PAR_MINUTE)).isEqualTo(Duration.ZERO);
    }

    @Test
    @DisplayName("CA1 - (3, 60) : 2 échecs ne plafonnent pas, le 3e plafonne avec 40 s restantes")
    void doit_plafonner_au_troisieme_echec_avec_le_temps_restant_du_plus_ancien() {
        EchecsParAdresse apresDeux = apresEchecsA(TROIS_PAR_MINUTE, T, T.plusSeconds(10));
        EchecsParAdresse apresTrois = apresEchecsA(TROIS_PAR_MINUTE, T, T.plusSeconds(10), T.plusSeconds(20));

        assertThat(apresDeux.estPlafonnee(T.plusSeconds(10), TROIS_PAR_MINUTE)).isFalse();
        assertThat(apresTrois.estPlafonnee(T.plusSeconds(20), TROIS_PAR_MINUTE)).isTrue();
        assertThat(apresTrois.tempsRestant(T.plusSeconds(20), TROIS_PAR_MINUTE)).isEqualTo(Duration.ofSeconds(40));
    }

    @Test
    @DisplayName("CA1 - (3, 60) : à T+59 s il reste 1 s ; à T+60 s le premier échec a expiré (2 actifs), plus plafonnée")
    void doit_liberer_l_adresse_a_l_instant_exact_d_expiration_du_plus_ancien_echec() {
        EchecsParAdresse echecs = apresEchecsA(TROIS_PAR_MINUTE, T, T.plusSeconds(10), T.plusSeconds(20));

        assertThat(echecs.estPlafonnee(T.plusSeconds(59), TROIS_PAR_MINUTE)).isTrue();
        assertThat(echecs.tempsRestant(T.plusSeconds(59), TROIS_PAR_MINUTE)).isEqualTo(Duration.ofSeconds(1));
        assertThat(echecs.estPlafonnee(T.plusSeconds(60), TROIS_PAR_MINUTE)).isFalse();
        assertThat(echecs.tempsRestant(T.plusSeconds(60), TROIS_PAR_MINUTE)).isEqualTo(Duration.ZERO);
        assertThat(echecs.actifs(T.plusSeconds(60), TROIS_PAR_MINUTE)).isEqualTo(2);
    }

    @Test
    @DisplayName("CA1 - plafond par défaut : 19 échecs à T ne plafonnent pas")
    void doit_admettre_les_dix_neuf_premiers_echecs_du_plafond_par_defaut() {
        EchecsParAdresse echecs = nEchecsA(PlafondEchecsParAdresse.parDefaut(), 19, T);

        assertThat(echecs.estPlafonnee(T, PlafondEchecsParAdresse.parDefaut())).isFalse();
    }

    @Test
    @DisplayName("CA1 - plafond par défaut : 20 échecs à T plafonnent à T (60 s) et à T+59 s, plus à T+60 s")
    void doit_plafonner_au_vingtieme_echec_du_plafond_par_defaut() {
        PlafondEchecsParAdresse defaut = PlafondEchecsParAdresse.parDefaut();
        EchecsParAdresse echecs = nEchecsA(defaut, 20, T);

        assertThat(echecs.estPlafonnee(T, defaut)).isTrue();
        assertThat(echecs.tempsRestant(T, defaut)).isEqualTo(Duration.ofSeconds(60));
        assertThat(echecs.estPlafonnee(T.plusSeconds(59), defaut)).isTrue();
        assertThat(echecs.estPlafonnee(T.plusSeconds(60), defaut)).isFalse();
    }

    @Test
    @DisplayName("CA1 - un échec isolé à T puis un à T+100 s : un seul échec actif")
    void doit_oublier_un_echec_plus_ancien_que_la_fenetre() {
        PlafondEchecsParAdresse defaut = PlafondEchecsParAdresse.parDefaut();
        EchecsParAdresse echecs = apresEchecsA(defaut, T, T.plusSeconds(100));

        assertThat(echecs.actifs(T.plusSeconds(100), defaut)).isEqualTo(1);
    }

    @Test
    @DisplayName("CA1 - dix échecs à T et dix à T+30 s : plafonnée à T+30 s (30 s restantes), admise à T+60 s")
    void doit_calculer_le_temps_restant_sur_l_echec_le_plus_ancien_des_echecs_repartis() {
        PlafondEchecsParAdresse defaut = PlafondEchecsParAdresse.parDefaut();
        EchecsParAdresse echecs = nEchecsA(defaut, 10, T);
        for (int i = 0; i < 10; i++) {
            echecs = echecs.apresEchec(T.plusSeconds(30), defaut);
        }

        assertThat(echecs.estPlafonnee(T.plusSeconds(30), defaut)).isTrue();
        assertThat(echecs.tempsRestant(T.plusSeconds(30), defaut)).isEqualTo(Duration.ofSeconds(30));
        assertThat(echecs.estPlafonnee(T.plusSeconds(60), defaut)).isFalse();
    }

    @Test
    @DisplayName("CA1 - apresEchec ne mute pas l'instance d'origine")
    void doit_etre_immuable() {
        EchecsParAdresse origine = apresEchecsA(TROIS_PAR_MINUTE, T, T.plusSeconds(1));

        EchecsParAdresse suivante = origine.apresEchec(T.plusSeconds(2), TROIS_PAR_MINUTE);

        assertThat(origine.estPlafonnee(T.plusSeconds(2), TROIS_PAR_MINUTE)).isFalse();
        assertThat(origine.instantsRetenus()).hasSize(2);
        assertThat(suivante.estPlafonnee(T.plusSeconds(2), TROIS_PAR_MINUTE)).isTrue();
        assertThat(EchecsParAdresse.aucun().instantsRetenus()).isEmpty();
    }

    @Test
    @DisplayName("CA1 - atteintLePlafondA est vrai pour l'échec qui fait passer les échecs actifs à echecsMax seulement")
    void doit_signaler_l_echec_qui_atteint_le_plafond() {
        EchecsParAdresse apresDeux = apresEchecsA(TROIS_PAR_MINUTE, T, T.plusSeconds(1));
        EchecsParAdresse apresTrois = apresDeux.apresEchec(T.plusSeconds(2), TROIS_PAR_MINUTE);

        assertThat(apresDeux.atteintLePlafondA(T.plusSeconds(1), TROIS_PAR_MINUTE)).isFalse();
        assertThat(apresTrois.atteintLePlafondA(T.plusSeconds(2), TROIS_PAR_MINUTE)).isTrue();
    }

    @Test
    @DisplayName("CA2 - 5 échecs avec (3, 60) : seuls les 3 plus récents sont retenus")
    void doit_retenir_seulement_les_echecs_max_plus_recents() {
        EchecsParAdresse echecs = apresEchecsA(TROIS_PAR_MINUTE,
                T, T.plusSeconds(1), T.plusSeconds(2), T.plusSeconds(3), T.plusSeconds(4));

        assertThat(echecs.instantsRetenus())
                .containsExactlyInAnyOrder(T.plusSeconds(2), T.plusSeconds(3), T.plusSeconds(4));
    }

    @Test
    @DisplayName("CA2 - 5 échecs avec (3, 60) : à T+4 s il reste 58 s ; à T+62 s (borne exclue) plus plafonnée, 2 actifs")
    void doit_calculer_le_temps_restant_sur_le_plus_ancien_des_echecs_retenus() {
        EchecsParAdresse echecs = apresEchecsA(TROIS_PAR_MINUTE,
                T, T.plusSeconds(1), T.plusSeconds(2), T.plusSeconds(3), T.plusSeconds(4));

        assertThat(echecs.estPlafonnee(T.plusSeconds(4), TROIS_PAR_MINUTE)).isTrue();
        assertThat(echecs.tempsRestant(T.plusSeconds(4), TROIS_PAR_MINUTE)).isEqualTo(Duration.ofSeconds(58));
        assertThat(echecs.estPlafonnee(T.plusSeconds(62), TROIS_PAR_MINUTE)).isFalse();
        assertThat(echecs.actifs(T.plusSeconds(62), TROIS_PAR_MINUTE)).isEqualTo(2);
    }

    @Test
    @DisplayName("CA2 - estOubliee est faux à T+62 s et vrai à T+64 s, quand le dernier échec expire")
    void doit_etre_oubliee_quand_tous_les_echecs_retenus_ont_expire() {
        EchecsParAdresse echecs = apresEchecsA(TROIS_PAR_MINUTE,
                T, T.plusSeconds(1), T.plusSeconds(2), T.plusSeconds(3), T.plusSeconds(4));

        assertThat(echecs.estOubliee(T.plusSeconds(62), TROIS_PAR_MINUTE)).isFalse();
        assertThat(echecs.estOubliee(T.plusSeconds(64), TROIS_PAR_MINUTE)).isTrue();
    }

    @Test
    @DisplayName("CA2 - 1000 échecs avec (3, 60) : 3 instants retenus au plus, adresse plafonnée")
    void doit_borner_la_memoire_a_echecs_max_instants() {
        EchecsParAdresse echecs = EchecsParAdresse.aucun();
        for (int i = 0; i < 1000; i++) {
            echecs = echecs.apresEchec(T.plusMillis(i), TROIS_PAR_MINUTE);
        }

        assertThat(echecs.instantsRetenus()).hasSize(3);
        assertThat(echecs.estPlafonnee(T.plusMillis(999), TROIS_PAR_MINUTE)).isTrue();
    }
}
