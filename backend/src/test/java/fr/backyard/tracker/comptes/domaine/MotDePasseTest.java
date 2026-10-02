package fr.backyard.tracker.comptes.domaine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class MotDePasseTest {

    private static final String SECRET = "secret-de-test-123";

    @Test
    @DisplayName("CA3 - 11 caractères : refusé MOT_DE_PASSE_TROP_COURT")
    void doit_refuser_un_mot_de_passe_de_11_caracteres() {
        assertViolation("a".repeat(11), "MOT_DE_PASSE_TROP_COURT");
    }

    @Test
    @DisplayName("CA3 - 12 caractères : accepté")
    void doit_accepter_un_mot_de_passe_de_12_caracteres() {
        assertThat(new MotDePasse("a".repeat(12)).valeur()).hasSize(12);
    }

    @Test
    @DisplayName("CA3 - 128 caractères : accepté")
    void doit_accepter_un_mot_de_passe_de_128_caracteres() {
        assertThat(new MotDePasse("a".repeat(128)).valeur()).hasSize(128);
    }

    @Test
    @DisplayName("CA3 - 129 caractères : refusé MOT_DE_PASSE_TROP_LONG")
    void doit_refuser_un_mot_de_passe_de_129_caracteres() {
        assertViolation("a".repeat(129), "MOT_DE_PASSE_TROP_LONG");
    }

    @Test
    @DisplayName("CA3 - 12 caractères accentués (24 octets) : accepté, le comptage est en caractères")
    void doit_compter_les_caracteres_et_non_les_octets() {
        assertThat(new MotDePasse("é".repeat(12)).valeur()).isEqualTo("é".repeat(12));
    }

    @Test
    @DisplayName("CA3 - le comptage se fait en points de code Unicode (12 émojis acceptés)")
    void doit_compter_en_points_de_code_unicode() {
        String douzeEmojis = "😀".repeat(12);

        assertThat(new MotDePasse(douzeEmojis).valeur()).isEqualTo(douzeEmojis);
    }

    @Test
    @DisplayName("CA3 - 12 espaces : accepté, aucun trim ni règle de composition")
    void doit_accepter_douze_espaces_sans_trim() {
        assertThat(new MotDePasse(" ".repeat(12)).valeur()).isEqualTo(" ".repeat(12));
    }

    @Test
    @DisplayName("CA3 - null : refusé MOT_DE_PASSE_REQUIS")
    void doit_refuser_un_mot_de_passe_null_comme_requis() {
        assertViolation(null, "MOT_DE_PASSE_REQUIS");
    }

    @Test
    @DisplayName("CA4 - toString masque la valeur")
    void doit_masquer_la_valeur_dans_to_string() {
        String texte = new MotDePasse(SECRET).toString();

        assertThat(texte).isEqualTo("MotDePasse[masqué]").doesNotContain(SECRET);
    }

    @Test
    @DisplayName("CA4 - le message de l'exception de validation ne reprend pas la valeur saisie")
    void doit_ne_pas_reprendre_la_valeur_saisie_dans_le_message_d_exception() {
        String tropCourt = "mdp-secret"; // 10 caractères

        assertThatThrownBy(() -> new MotDePasse(tropCourt))
                .isInstanceOfSatisfying(DonneesCompteInvalidesException.class, e -> {
                    assertThat(e.getMessage()).doesNotContain(tropCourt);
                    assertThat(e.violations()).allSatisfy(v -> assertThat(v.message()).doesNotContain(tropCourt));
                });
    }

    private static void assertViolation(String saisie, String codeAttendu) {
        assertThatThrownBy(() -> new MotDePasse(saisie))
                .isInstanceOfSatisfying(DonneesCompteInvalidesException.class, e -> {
                    assertThat(e.violations()).hasSize(1);
                    assertThat(e.violations().get(0).champ()).isEqualTo("motDePasse");
                    assertThat(e.violations().get(0).code()).isEqualTo(codeAttendu);
                });
    }
}
