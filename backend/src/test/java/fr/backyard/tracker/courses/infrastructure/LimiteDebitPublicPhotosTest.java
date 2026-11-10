package fr.backyard.tracker.courses.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import fr.backyard.tracker.courses.HorlogeReglableDeTest;
import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Incrément 10.2 : plafond de débit paramétré par préfixe, compteurs séparés (CA5 ; RG6).
 * Signature attendue : {@code LimiteDebitPublic(String prefixe, int limiteParMinute, Clock horloge)}
 * (remplace {@code LimiteDebitPublic(int, Clock)} ; {@code verifier(methodeHttp, chemin, ip)} et
 * {@code Decision(boolean autorisee, int reessayerDansSecondes)} inchangés). Le préfixe vaut une route exacte ou
 * ses sous-chemins ({@code prefixe} ou {@code prefixe + "/..."}). Les deux instances (photos, courses) ont chacune
 * leur compteur.
 */
class LimiteDebitPublicPhotosTest {
    // Correctif audit jalon 10 (RG2) : le test « HEAD non compté » de 10.2 est remplacé par
    // LimiteDebitPublicMethodesComptees (HEAD compté sur /api/photos). Le constructeur à 3 arguments reste « GET seul ».

    private static final String IP_A = "203.0.113.7";
    private static final String IP_B = "198.51.100.9";
    private static final String PHOTO = "/api/photos/3f9c0b7a5e1d4c28a6b9d0e1f2a3b4c5";
    private static final String SUIVI = "/api/courses/00000000-0000-0000-0000-000000000001/suivi";

    private final HorlogeReglableDeTest horloge = new HorlogeReglableDeTest(Instant.parse("2026-10-21T10:00:20Z"));

    @Test
    @DisplayName("CA5 - limite 3 sur /api/photos : le 4e GET de la même IP est refusé avec au moins 1 seconde à attendre")
    void doit_refuser_le_quatrieme_get_photo_d_une_ip() {
        var photos = new LimiteDebitPublic("/api/photos", 3, horloge);

        for (int i = 0; i < 3; i++) {
            assertThat(photos.verifier("GET", PHOTO, IP_A).autorisee()).isTrue();
        }
        var quatrieme = photos.verifier("GET", PHOTO, IP_A);

        assertThat(quatrieme.autorisee()).isFalse();
        assertThat(quatrieme.reessayerDansSecondes()).isGreaterThanOrEqualTo(1);
    }

    @Test
    @DisplayName("CA5 - les compteurs photos et courses sont séparés : photos saturé, 2 GET /api/courses passent encore")
    void doit_garder_des_compteurs_separes_pour_photos_et_courses() {
        var photos = new LimiteDebitPublic("/api/photos", 3, horloge);
        var courses = new LimiteDebitPublic("/api/courses", 2, horloge);
        for (int i = 0; i < 4; i++) {
            photos.verifier("GET", PHOTO, IP_A);
        }

        assertThat(courses.verifier("GET", SUIVI, IP_A).autorisee()).isTrue();
        assertThat(courses.verifier("GET", SUIVI, IP_A).autorisee()).isTrue();
        assertThat(courses.verifier("GET", SUIVI, IP_A).autorisee()).isFalse();
    }

    @Test
    @DisplayName("CA5 - chaque limiteur ignore les chemins de l'autre préfixe")
    void doit_ignorer_les_chemins_hors_de_son_prefixe() {
        var photos = new LimiteDebitPublic("/api/photos", 1, horloge);
        var courses = new LimiteDebitPublic("/api/courses", 1, horloge);

        for (int i = 0; i < 5; i++) {
            assertThat(photos.verifier("GET", SUIVI, IP_A).autorisee()).isTrue();
            assertThat(courses.verifier("GET", PHOTO, IP_A).autorisee()).isTrue();
        }
    }

    @Test
    @DisplayName("CA5 - une autre IP n'est pas affectée par le dépassement de l'IP A")
    void doit_compter_chaque_ip_separement_pour_les_photos() {
        var photos = new LimiteDebitPublic("/api/photos", 3, horloge);
        for (int i = 0; i < 4; i++) {
            photos.verifier("GET", PHOTO, IP_A);
        }

        assertThat(photos.verifier("GET", PHOTO, IP_B).autorisee()).isTrue();
    }

    @Test
    @DisplayName("CA5 - une limite de 0 pour les photos n'interdit rien")
    void doit_ne_rien_interdire_avec_une_limite_photos_de_zero() {
        var photos = new LimiteDebitPublic("/api/photos", 0, horloge);

        for (int i = 0; i < 5000; i++) {
            assertThat(photos.verifier("GET", PHOTO, IP_A).autorisee()).isTrue();
        }
    }

    @Test
    @DisplayName("CA5 - à la minute suivante le compteur des photos repart de zéro")
    void doit_remettre_le_compteur_photos_a_zero_a_la_minute_suivante() {
        var photos = new LimiteDebitPublic("/api/photos", 3, horloge);
        for (int i = 0; i < 4; i++) {
            photos.verifier("GET", PHOTO, IP_A);
        }
        horloge.placerA(Instant.parse("2026-10-21T10:01:00Z"));

        assertThat(photos.verifier("GET", PHOTO, IP_A).autorisee()).isTrue();
    }

    @Test
    @DisplayName("CA5 - un préfixe voisin (/api/photosX) n'est pas compté comme /api/photos")
    void doit_ne_pas_confondre_un_prefixe_voisin() {
        var photos = new LimiteDebitPublic("/api/photos", 1, horloge);

        for (int i = 0; i < 3; i++) {
            assertThat(photos.verifier("GET", "/api/photosX/abc", IP_A).autorisee()).isTrue();
        }
    }
}
