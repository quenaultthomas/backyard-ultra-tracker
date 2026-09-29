package fr.backyard.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Spec increment 5 - RG3, CA3, CL11 [unit] : longueur du mot de passe d'un compte coureur. */
@Tag("INC-5")
class PasswordPolicyTest {

    @Test
    @DisplayName("CA3 - refuses : court12 (7 caracteres), 73 caracteres ASCII, 37 fois e accent aigu (74 octets), absent")
    void ca3_rejectedPasswords() {
        assertThat(PasswordPolicy.isAcceptable("court12")).isFalse();
        assertThat(PasswordPolicy.isAcceptable("a".repeat(73))).isFalse();
        assertThat(PasswordPolicy.isAcceptable("é".repeat(37))).isFalse();
        assertThat(PasswordPolicy.isAcceptable(null)).isFalse();
        assertThat(PasswordPolicy.isAcceptable("")).isFalse();
    }

    @Test
    @DisplayName("CA3 - acceptes : huitcar8 (8), 72 caracteres ASCII, 36 fois e accent aigu (72 octets)")
    void ca3_acceptedPasswords() {
        assertThat(PasswordPolicy.isAcceptable("huitcar8")).isTrue();
        assertThat(PasswordPolicy.isAcceptable("a".repeat(72))).isTrue();
        assertThat(PasswordPolicy.isAcceptable("é".repeat(36))).isTrue();
    }

    @Test
    @DisplayName("RG3 - aucun nettoyage d'espaces : 8 espaces forment un mot de passe de 8 caracteres")
    void rg3_spacesAreNotTrimmed() {
        assertThat(PasswordPolicy.isAcceptable("        ")).isTrue();
        assertThat(PasswordPolicy.isAcceptable(" court1 ")).isTrue();
        assertThat(PasswordPolicy.isAcceptable("court1 ")).isFalse();
    }
}
