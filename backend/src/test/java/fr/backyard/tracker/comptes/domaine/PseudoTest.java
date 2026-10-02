package fr.backyard.tracker.comptes.domaine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class PseudoTest {

    @Test
    @DisplayName("CA1 - le pseudo est débarrassé de ses espaces de début et de fin, casse conservée")
    void doit_retirer_les_espaces_de_debut_et_de_fin_en_conservant_la_casse() {
        assertThat(new Pseudo("  Alice_01 ").valeur()).isEqualTo("Alice_01");
    }

    @ParameterizedTest(name = "CA1 - pseudo valide : {0}")
    @ValueSource(strings = {"Bob", "éloïse.89-x", "a.b_c-d"})
    void doit_accepter_un_pseudo_de_lettres_unicode_chiffres_point_tiret_et_souligne(String saisie) {
        assertThat(new Pseudo(saisie).valeur()).isEqualTo(saisie);
    }

    @Test
    @DisplayName("CA1 - un pseudo de 30 caractères est accepté")
    void doit_accepter_un_pseudo_de_30_caracteres() {
        String trenteCaracteres = "a".repeat(30);

        assertThat(new Pseudo(trenteCaracteres).valeur()).isEqualTo(trenteCaracteres);
    }

    @Test
    @DisplayName("CA1 - la longueur est contrôlée après trim")
    void doit_controler_la_longueur_apres_trim() {
        assertThat(new Pseudo("  Bob  ").valeur()).isEqualTo("Bob");
    }

    @ParameterizedTest(name = "CA2 - pseudo [{0}] refusé avec {1}")
    @CsvSource(delimiter = '|', quoteCharacter = '"', value = {
            "\"\"|PSEUDO_REQUIS",
            "\"   \"|PSEUDO_REQUIS",
            "ab|PSEUDO_LONGUEUR",
            "a b|PSEUDO_CARACTERES",
            "a@b|PSEUDO_CARACTERES",
            "<b>|PSEUDO_CARACTERES",
            "a/b|PSEUDO_CARACTERES"
    })
    void doit_refuser_un_pseudo_invalide_avec_le_code_de_violation_attendu(String saisie, String codeAttendu) {
        assertThatThrownBy(() -> new Pseudo(saisie))
                .isInstanceOfSatisfying(DonneesCompteInvalidesException.class, e -> {
                    assertThat(e.violations()).hasSize(1);
                    assertThat(e.violations().get(0).champ()).isEqualTo("pseudo");
                    assertThat(e.violations().get(0).code()).isEqualTo(codeAttendu);
                });
    }

    @Test
    @DisplayName("CA2 - un pseudo de 31 caractères est refusé (PSEUDO_LONGUEUR)")
    void doit_refuser_un_pseudo_de_31_caracteres() {
        assertThatThrownBy(() -> new Pseudo("a".repeat(31)))
                .isInstanceOfSatisfying(DonneesCompteInvalidesException.class,
                        e -> assertThat(e.violations().get(0).code()).isEqualTo("PSEUDO_LONGUEUR"));
    }

    @Test
    @DisplayName("CA2 - un pseudo null est refusé (PSEUDO_REQUIS)")
    void doit_refuser_un_pseudo_null_comme_requis() {
        assertThatThrownBy(() -> new Pseudo(null))
                .isInstanceOfSatisfying(DonneesCompteInvalidesException.class,
                        e -> assertThat(e.violations().get(0).code()).isEqualTo("PSEUDO_REQUIS"));
    }

    @Test
    @DisplayName("CA2 - une seule violation retenue : la longueur prime sur les caractères")
    void doit_retenir_la_longueur_avant_les_caracteres() {
        assertThatThrownBy(() -> new Pseudo("@"))
                .isInstanceOfSatisfying(DonneesCompteInvalidesException.class, e -> {
                    assertThat(e.violations()).hasSize(1);
                    assertThat(e.violations().get(0).code()).isEqualTo("PSEUDO_LONGUEUR");
                });
    }

    @Test
    @DisplayName("CA5/RG3 - la forme normalisée est le pseudo en minuscules")
    void doit_normaliser_le_pseudo_en_minuscules() {
        assertThat(new Pseudo("ÉloÏse_AB").normalise()).isEqualTo("éloïse_ab");
    }
}
