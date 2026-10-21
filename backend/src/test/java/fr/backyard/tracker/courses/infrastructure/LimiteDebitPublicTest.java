package fr.backyard.tracker.courses.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import fr.backyard.tracker.courses.HorlogeReglableDeTest;
import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Incrément 7.2 : limite de débit par IP des routes publiques (CA8, CA9 ; RG4, RG5).
 * Signatures supposées : {@code LimiteDebitPublic(int limiteParMinute, Clock horloge)} ;
 * {@code LimiteDebitPublic.Decision verifier(String methodeHttp, String chemin, String ip)} ;
 * record {@code Decision(boolean autorisee, int reessayerDansSecondes)} (secondes jusqu'à la fin de la minute,
 * arrondies au-dessus, >= 1 quand refusée).
 */
class LimiteDebitPublicTest {

    private static final String IP = "203.0.113.7";
    private static final String SUIVI = "/api/courses/00000000-0000-0000-0000-000000000001/suivi";

    private final HorlogeReglableDeTest horloge = new HorlogeReglableDeTest(Instant.parse("2026-10-21T10:00:20Z"));

    @Test
    @DisplayName("CA8 - L = 3 : les 3 premières requêtes passent, la 4e est refusée avec les secondes restantes dans la minute")
    void doit_refuser_la_requete_qui_depasse_la_limite() {
        LimiteDebitPublic limite = new LimiteDebitPublic(3, horloge);

        for (int i = 0; i < 3; i++) {
            assertThat(limite.verifier("GET", SUIVI, IP).autorisee()).isTrue();
        }
        var quatrieme = limite.verifier("GET", SUIVI, IP);

        assertThat(quatrieme.autorisee()).isFalse();
        assertThat(quatrieme.reessayerDansSecondes()).isEqualTo(40);
    }

    @Test
    @DisplayName("CA8 - à 10:00:59.500 le Retry-After est arrondi à 1 seconde, jamais 0")
    void doit_annoncer_au_moins_une_seconde_en_fin_de_minute() {
        LimiteDebitPublic limite = new LimiteDebitPublic(1, horloge);
        limite.verifier("GET", SUIVI, IP);
        horloge.placerA(Instant.parse("2026-10-21T10:00:59.500Z"));

        var refusee = limite.verifier("GET", SUIVI, IP);

        assertThat(refusee.autorisee()).isFalse();
        assertThat(refusee.reessayerDansSecondes()).isEqualTo(1);
    }

    @Test
    @DisplayName("CA8 - à la minute suivante, le compteur repart de zéro")
    void doit_remettre_le_compteur_a_zero_a_la_minute_suivante() {
        LimiteDebitPublic limite = new LimiteDebitPublic(3, horloge);
        for (int i = 0; i < 4; i++) {
            limite.verifier("GET", SUIVI, IP);
        }
        horloge.placerA(Instant.parse("2026-10-21T10:01:00Z"));

        assertThat(limite.verifier("GET", SUIVI, IP).autorisee()).isTrue();
    }

    @Test
    @DisplayName("CA8 - une autre IP n'est pas affectée par le dépassement d'une IP")
    void doit_compter_chaque_ip_separement() {
        LimiteDebitPublic limite = new LimiteDebitPublic(3, horloge);
        for (int i = 0; i < 4; i++) {
            limite.verifier("GET", SUIVI, IP);
        }

        assertThat(limite.verifier("GET", SUIVI, "198.51.100.9").autorisee()).isTrue();
    }

    @Test
    @DisplayName("CA8 - L = 0 désactive la limite : jamais de refus")
    void doit_ne_jamais_refuser_quand_la_limite_est_zero() {
        LimiteDebitPublic limite = new LimiteDebitPublic(0, horloge);

        for (int i = 0; i < 5000; i++) {
            assertThat(limite.verifier("GET", SUIVI, IP).autorisee()).isTrue();
        }
    }

    @Test
    @DisplayName("CA9 - sante, version, POST sur courses, routes coureur et bénévole ne sont pas comptées")
    void doit_ignorer_les_routes_non_publiques_et_les_methodes_autres_que_get() {
        LimiteDebitPublic limite = new LimiteDebitPublic(3, horloge);

        for (int i = 0; i < 10; i++) {
            assertThat(limite.verifier("GET", "/api/sante", IP).autorisee()).isTrue();
            assertThat(limite.verifier("GET", "/api/version", IP).autorisee()).isTrue();
            assertThat(limite.verifier("POST", "/api/courses/x", IP).autorisee()).isTrue();
            assertThat(limite.verifier("GET", "/api/coureur/courses", IP).autorisee()).isTrue();
            assertThat(limite.verifier("GET", "/api/benevole/courses", IP).autorisee()).isTrue();
        }
        for (int i = 0; i < 3; i++) {
            assertThat(limite.verifier("GET", "/api/courses", IP).autorisee()).isTrue();
        }
        assertThat(limite.verifier("GET", "/api/courses", IP).autorisee()).isFalse();
    }

    @Test
    @DisplayName("CA9 - GET /api/courses, suivi et logo sont comptés avec le même compteur")
    void doit_compter_liste_suivi_et_logo_dans_le_meme_compteur() {
        LimiteDebitPublic limite = new LimiteDebitPublic(3, horloge);

        assertThat(limite.verifier("GET", "/api/courses", IP).autorisee()).isTrue();
        assertThat(limite.verifier("GET", SUIVI, IP).autorisee()).isTrue();
        assertThat(limite.verifier("GET", "/api/courses/00000000-0000-0000-0000-000000000001/logo", IP).autorisee())
                .isTrue();
        assertThat(limite.verifier("GET", "/api/courses", IP).autorisee()).isFalse();
        assertThat(limite.verifier("GET", SUIVI, IP).autorisee()).isFalse();
    }
}
