package fr.backyard.tracker.courses.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** DEP-05 (audit 2026-10-16) : journal borné des échecs répétés du planificateur. Test unitaire sans Spring. */
class CompteurEchecsConsecutifsTest {

    @Test
    @DisplayName("DEP-05 : les échecs consécutifs sont comptés par clé et remis à zéro par une réussite")
    void doit_compter_par_cle_et_remettre_a_zero() {
        CompteurEchecsConsecutifs<String> compteur = new CompteurEchecsConsecutifs<>();

        assertThat(compteur.enregistrerEchec("a")).isEqualTo(1);
        assertThat(compteur.enregistrerEchec("a")).isEqualTo(2);
        assertThat(compteur.enregistrerEchec("b")).isEqualTo(1);
        assertThat(compteur.enregistrerReussite("a")).isEqualTo(2);
        assertThat(compteur.enregistrerReussite("a")).isZero();
        assertThat(compteur.enregistrerEchec("a")).isEqualTo(1);
    }

    @Test
    @DisplayName("DEP-05 : les clés devenues inactives sont oubliées")
    void doit_oublier_les_cles_inactives() {
        CompteurEchecsConsecutifs<String> compteur = new CompteurEchecsConsecutifs<>();
        compteur.enregistrerEchec("terminee");
        compteur.enregistrerEchec("active");

        compteur.conserverSeulement(List.of("active"));

        assertThat(compteur.enregistrerReussite("terminee")).isZero();
        assertThat(compteur.enregistrerReussite("active")).isEqualTo(1);
    }

    @Test
    @DisplayName("DEP-05 : journal aux rangs 1, 2, 4, 8... puis tous les 1024 échecs")
    void doit_journaliser_aux_puissances_de_deux_puis_par_palier() {
        List<Integer> rangsJournalises = IntStream.rangeClosed(1, 4 * CompteurEchecsConsecutifs.RANG_PALIER)
                .filter(CompteurEchecsConsecutifs::doitJournaliser).boxed().toList();

        assertThat(rangsJournalises).containsExactly(1, 2, 4, 8, 16, 32, 64, 128, 256, 512, 1024, 2048, 3072, 4096);
    }
}
