package fr.backyard.tracker.courses.exposition;

import static org.assertj.core.api.Assertions.assertThat;

import fr.backyard.tracker.courses.application.ConsulterSuivi.CoureurSuivi;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Correctif audit jalon 10, RG12 (CA15, CA17 en complément) : l'adresse publique de la photo est construite par
 * l'exposition. Signature attendue : {@code SuiviCourseReponse.CoureurSuiviReponse.depuis(List<CoureurSuivi>)} (déjà
 * existante) construit {@code photoUrl} = {@code "/api/photos/" + jetonPhoto}, ou {@code null} si le jeton est null.
 */
class SuiviCourseReponseAdressePhotoTest {

    private static final String JETON = "3f9c0b7a5e1d4c28a6b9d0e1f2a3b4c5";

    @Test
    @DisplayName("CA15 - l'exposition construit /api/photos/<jeton> à partir du jeton, null sans jeton")
    void doit_construire_l_adresse_de_la_photo_dans_l_exposition() {
        var lignes = SuiviCourseReponse.CoureurSuiviReponse.depuis(List.of(
                new CoureurSuivi(1, "Alice", null),
                new CoureurSuivi(2, "Karim", JETON)));

        assertThat(lignes).extracting(l -> l.dossard(), l -> l.pseudo(), l -> l.photoUrl())
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(1, "Alice", null),
                        org.assertj.core.groups.Tuple.tuple(2, "Karim", "/api/photos/" + JETON));
    }
}
