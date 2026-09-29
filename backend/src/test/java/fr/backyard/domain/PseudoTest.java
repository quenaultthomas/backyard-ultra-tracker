package fr.backyard.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Spec increment 5 - RG2, CA2 [unit] : format de la saisie et normalisation du pseudo, sans Spring ni base. */
@Tag("INC-5")
class PseudoTest {

    @ParameterizedTest(name = "CA2 - saisie acceptee : ''{0}''")
    @ValueSource(strings = {"Lievre_42", "abc", "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAA", "  Lievre  ", "LIEVRE", "a-b", "a_b"})
    void ca2_wellFormedInputs(String input) {
        assertThat(Pseudo.isWellFormed(input)).isTrue();
    }

    @ParameterizedTest(name = "CA2 - saisie refusee : ''{0}''")
    @NullAndEmptySource
    @ValueSource(strings = {"ab", "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA", "Jean Dupont", "élan", "   ", "a%b", "Coureur n°1"})
    void ca2_malformedInputs(String input) {
        assertThat(Pseudo.isWellFormed(input)).isFalse();
    }

    @Test
    @DisplayName("CA2 - le format est controle sur la saisie privee de ses espaces de bord : 30 caracteres entoures d'espaces acceptes")
    void ca2_formatCheckedAfterTrim() {
        String thirty = "A".repeat(30);

        assertThat(Pseudo.isWellFormed("  " + thirty + "  ")).isTrue();
        assertThat(Pseudo.isWellFormed("  " + thirty + "A  ")).isFalse();
    }

    @ParameterizedTest(name = "CA2 - normaliser(''{0}'') = ''{1}''")
    @CsvSource(delimiter = '|', value = {
        "'  Lievre_42 '|lievre_42",
        "LIEVRE|lievre",
        "lievre|lievre",
        "'  LiEvRe '|lievre",
        "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAA|aaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"})
    void ca2_normalize(String input, String expected) {
        assertThat(Pseudo.normalize(input)).isEqualTo(expected);
    }

    @Test
    @DisplayName("RG2 - la normalisation est idempotente et le resultat d'un pseudo valide respecte ^[a-z0-9_-]{3,30}$")
    void rg2_normalizeIsIdempotent() {
        String once = Pseudo.normalize("  Lievre-42_X ");

        assertThat(Pseudo.normalize(once)).isEqualTo(once);
        assertThat(once).matches("^[a-z0-9_-]{3,30}$");
    }

    @Test
    @DisplayName("RG2 - minuscules en Locale.ROOT : le I majuscule donne i meme si la locale par defaut est turque")
    void rg2_normalizeIsLocaleIndependent() {
        Locale previous = Locale.getDefault();
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"));

            assertThat(Pseudo.normalize("LIEVRE_I")).isEqualTo("lievre_i");
        } finally {
            Locale.setDefault(previous);
        }
    }

    @Test
    @DisplayName("RG2 - normaliser une valeur absente est une erreur de programmation (IllegalArgumentException)")
    void rg2_normalizeNullIsRejected() {
        assertThatThrownBy(() -> Pseudo.normalize(null)).isInstanceOf(IllegalArgumentException.class);
    }
}
