package fr.backyard.tracker.courses.exposition;

import static org.assertj.core.api.Assertions.assertThat;

import fr.backyard.tracker.courses.application.ConsulterSuivi.CoureurSuivi;
import fr.backyard.tracker.courses.domaine.StatutCourse;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Incrément 10.2 : confidentialité des affichages (CA6 ; RG4).
 * Signatures attendues : {@code ConsulterSuivi.CoureurSuivi(int dossard, String pseudo, String photoUrl)} ;
 * {@code SuiviCourseReponse.CoureurSuiviReponse(int dossard, String pseudo, String photoUrl)}.
 */
class SuiviCourseReponsePhotoToStringTest {

    private static final String JETON = "3f9c0b7a5e1d4c28a6b9d0e1f2a3b4c5";
    private static final String URL = "/api/photos/" + JETON;

    @Test
    @DisplayName("CA6 - toString d'une ligne du cas d'usage n'affiche ni pseudo, ni jeton, ni photoUrl")
    void doit_masquer_pseudo_et_photo_dans_le_to_string_de_la_ligne_du_cas_d_usage() {
        String texte = new CoureurSuivi(2, "Karim", URL).toString();

        assertThat(texte).doesNotContain("Karim", JETON, "/api/photos", "photoUrl");
    }

    @Test
    @DisplayName("CA6 - toString d'une ligne de la réponse n'affiche ni pseudo, ni jeton, ni photoUrl")
    void doit_masquer_pseudo_et_photo_dans_le_to_string_de_la_ligne_de_la_reponse() {
        String texte = new SuiviCourseReponse.CoureurSuiviReponse(2, "Karim", URL).toString();

        assertThat(texte).doesNotContain("Karim", JETON, "/api/photos", "photoUrl");
    }

    @Test
    @DisplayName("CA6 - toString de la réponse du suivi n'affiche ni pseudo, ni jeton, ni photoUrl")
    void doit_masquer_pseudo_et_photo_dans_le_to_string_de_la_reponse() {
        var ligne = new SuiviCourseReponse.CoureurSuiviReponse(2, "Karim", URL);
        var reponse = new SuiviCourseReponse(UUID.randomUUID(), "Backyard", null, StatutCourse.EN_COURS,
                "2026-10-09T14:03:27.000Z", 5, 1, false, false, null, null, null, List.of(ligne), List.of(),
                List.of(ligne), 400, 5, 0, 0L, 0L, null, null);

        assertThat(reponse.toString()).doesNotContain("Karim", JETON, "/api/photos", "photoUrl");
    }
}
