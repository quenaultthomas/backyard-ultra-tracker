package fr.backyard.tracker.courses.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import fr.backyard.tracker.courses.HorlogeReglableDeTest;
import java.time.Instant;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Correctif audit jalon 10, RG2 (CA2) : plafond de débit paramétré par méthodes comptées.
 * Signatures attendues : {@code LimiteDebitPublic(String prefixe, Set<String> methodesComptees, int limiteParMinute,
 * Clock horloge)} ; le constructeur {@code LimiteDebitPublic(String, int, Clock)} reste valable et équivaut à
 * {@code Set.of("GET")}. Les photos utilisent {@code Set.of("GET", "HEAD")}, les courses {@code Set.of("GET")}.
 */
class LimiteDebitPublicMethodesCompteesTest {

    private static final String IP_A = "203.0.113.7";
    private static final String IP_B = "198.51.100.9";
    private static final String PHOTO = "/api/photos/3f9c0b7a5e1d4c28a6b9d0e1f2a3b4c5";
    private static final String SUIVI = "/api/courses/00000000-0000-0000-0000-000000000001/suivi";
    private static final Set<String> GET_ET_HEAD = Set.of("GET", "HEAD");

    private final HorlogeReglableDeTest horloge = new HorlogeReglableDeTest(Instant.parse("2026-10-21T10:00:20Z"));

    private LimiteDebitPublic photos(int limite) {
        return new LimiteDebitPublic("/api/photos", GET_ET_HEAD, limite, horloge);
    }

    private LimiteDebitPublic courses(int limite) {
        return new LimiteDebitPublic("/api/courses", Set.of("GET"), limite, horloge);
    }

    @Test
    @DisplayName("CA2 - limite 3 : HEAD, HEAD, GET passent, le 4e appel (HEAD) est refusé avec au moins 1 seconde à attendre")
    void doit_compter_head_et_get_dans_le_meme_compteur_des_photos() {
        var photos = photos(3);

        assertThat(photos.verifier("HEAD", PHOTO, IP_A).autorisee()).isTrue();
        assertThat(photos.verifier("HEAD", PHOTO, IP_A).autorisee()).isTrue();
        assertThat(photos.verifier("GET", PHOTO, IP_A).autorisee()).isTrue();
        var quatrieme = photos.verifier("HEAD", PHOTO, IP_A);

        assertThat(quatrieme.autorisee()).isFalse();
        assertThat(quatrieme.reessayerDansSecondes()).isGreaterThanOrEqualTo(1);
    }

    @Test
    @DisplayName("CA2 - plafond de 5 : 3 HEAD + 2 GET passent, le 6e appel de n'importe quelle méthode est refusé")
    void doit_refuser_le_sixieme_appel_toutes_methodes_comptees_confondues() {
        var photos = photos(5);
        for (int i = 0; i < 3; i++) {
            assertThat(photos.verifier("HEAD", PHOTO, IP_A).autorisee()).isTrue();
        }
        for (int i = 0; i < 2; i++) {
            assertThat(photos.verifier("GET", PHOTO, IP_A).autorisee()).isTrue();
        }

        assertThat(photos.verifier("GET", PHOTO, IP_A).autorisee()).isFalse();
    }

    @Test
    @DisplayName("CA2 - /api/courses (GET seul) : 5 HEAD passent, puis 2 GET passent et le 3e est refusé")
    void doit_ne_pas_compter_les_head_sur_les_courses() {
        var courses = courses(2);

        for (int i = 0; i < 5; i++) {
            assertThat(courses.verifier("HEAD", SUIVI, IP_A).autorisee()).isTrue();
        }
        assertThat(courses.verifier("GET", SUIVI, IP_A).autorisee()).isTrue();
        assertThat(courses.verifier("GET", SUIVI, IP_A).autorisee()).isTrue();
        assertThat(courses.verifier("GET", SUIVI, IP_A).autorisee()).isFalse();
    }

    @Test
    @DisplayName("CA2 - le constructeur à trois arguments compte GET seul (comportement 7.2 conservé)")
    void doit_garder_get_seul_avec_le_constructeur_historique() {
        var courses = new LimiteDebitPublic("/api/courses", 1, horloge);

        for (int i = 0; i < 5; i++) {
            assertThat(courses.verifier("HEAD", SUIVI, IP_A).autorisee()).isTrue();
        }
        assertThat(courses.verifier("GET", SUIVI, IP_A).autorisee()).isTrue();
        assertThat(courses.verifier("GET", SUIVI, IP_A).autorisee()).isFalse();
    }

    @Test
    @DisplayName("CA2 - une autre IP n'est pas affectée par le dépassement de l'IP A, HEAD compris")
    void doit_compter_chaque_ip_separement_pour_les_head() {
        var photos = photos(3);
        for (int i = 0; i < 4; i++) {
            photos.verifier("HEAD", PHOTO, IP_A);
        }

        assertThat(photos.verifier("HEAD", PHOTO, IP_B).autorisee()).isTrue();
    }

    @Test
    @DisplayName("CA2 - limite 0 : 50 HEAD sont autorisés")
    void doit_ne_rien_refuser_avec_une_limite_de_zero_meme_pour_head() {
        var photos = photos(0);

        for (int i = 0; i < 50; i++) {
            assertThat(photos.verifier("HEAD", PHOTO, IP_A).autorisee()).isTrue();
        }
    }

    @Test
    @DisplayName("CA2 - à la minute suivante le compteur repart de zéro, HEAD compris")
    void doit_remettre_le_compteur_a_zero_a_la_minute_suivante() {
        var photos = photos(3);
        for (int i = 0; i < 4; i++) {
            photos.verifier("HEAD", PHOTO, IP_A);
        }
        horloge.placerA(Instant.parse("2026-10-21T10:01:00Z"));

        assertThat(photos.verifier("HEAD", PHOTO, IP_A).autorisee()).isTrue();
    }

    @Test
    @DisplayName("CA2 - OPTIONS et POST ne sont jamais comptés, même sur les photos")
    void doit_ne_compter_aucune_autre_methode() {
        var photos = photos(1);

        for (int i = 0; i < 10; i++) {
            assertThat(photos.verifier("OPTIONS", PHOTO, IP_A).autorisee()).isTrue();
            assertThat(photos.verifier("POST", PHOTO, IP_A).autorisee()).isTrue();
        }
        assertThat(photos.verifier("GET", PHOTO, IP_A).autorisee()).isTrue();
    }

    @Test
    @DisplayName("CA2 - un HEAD hors du préfixe des photos n'est pas compté par le limiteur des photos")
    void doit_ignorer_un_head_hors_prefixe() {
        var photos = photos(1);

        for (int i = 0; i < 5; i++) {
            assertThat(photos.verifier("HEAD", SUIVI, IP_A).autorisee()).isTrue();
        }
    }
}
