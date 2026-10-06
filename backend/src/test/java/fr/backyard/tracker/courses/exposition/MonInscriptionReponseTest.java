package fr.backyard.tracker.courses.exposition;

import static org.assertj.core.api.Assertions.assertThat;

import fr.backyard.tracker.courses.domaine.StatutCourse;
import fr.backyard.tracker.courses.domaine.StatutInscription;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tests de l'incrément 3.3 : DTO MonInscriptionReponse (CA2 ; RG3).
 *
 * <p>Signature supposée : {@code record MonInscriptionReponse(UUID id, UUID courseId, String courseNom,
 * String courseDate, StatutCourse courseStatut, String logoUrl, int dossard, StatutInscription statut,
 * String jetonQr)}, constructeur canonique public ou visible du paquet.
 */
class MonInscriptionReponseTest {

    private static final UUID ID = UUID.fromString("00000000-0000-0000-0000-0000000000f1");
    private static final String JETON = "AbCdEfGhIjKlMnOpQrStUvWxYz0123456789-_AbCde";

    private final MonInscriptionReponse reponse = new MonInscriptionReponse(ID,
            UUID.fromString("00000000-0000-0000-0000-0000000000c1"), "Backyard des Crêtes", "2026-10-10",
            StatutCourse.EN_PREPARATION, null, 17, StatutInscription.EN_COURSE, JETON);

    @Test
    @DisplayName("CA2 - toString vaut MonInscriptionReponse[id=<id>]")
    void doit_ne_montrer_que_l_identifiant_dans_toString() {
        assertThat(reponse.toString()).isEqualTo("MonInscriptionReponse[id=" + ID + "]");
    }

    @Test
    @DisplayName("CA2 - toString ne contient ni jeton, ni nom de Course, ni dossard")
    void doit_masquer_jeton_nom_et_dossard_dans_toString() {
        assertThat(reponse.toString()).doesNotContain(JETON).doesNotContain("Crêtes").doesNotContain("17");
    }
}
