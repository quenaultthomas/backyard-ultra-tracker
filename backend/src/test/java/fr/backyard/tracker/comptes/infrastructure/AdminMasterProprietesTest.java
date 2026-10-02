package fr.backyard.tracker.comptes.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class AdminMasterProprietesTest {

    @Test
    @DisplayName("CA9 - le toString des propriétés masque le mot de passe")
    void doit_masquer_le_mot_de_passe_dans_to_string() {
        AdminMasterProprietes proprietes = new AdminMasterProprietes("Patron", "secret-de-test-123");

        assertThat(proprietes.toString()).doesNotContain("secret-de-test-123");
    }

    @Test
    @DisplayName("CA9 - le toString des propriétés masque aussi un mot de passe invalide")
    void doit_masquer_un_mot_de_passe_invalide_dans_to_string() {
        AdminMasterProprietes proprietes = new AdminMasterProprietes("Patron", "court-secre");

        assertThat(proprietes.toString()).doesNotContain("court-secre");
    }

    @Test
    @DisplayName("CA9 - les propriétés exposent les valeurs liées par des accesseurs")
    void doit_exposer_pseudo_et_mot_de_passe_lies() {
        AdminMasterProprietes proprietes = new AdminMasterProprietes("Patron", "secret-de-test-123");

        assertThat(proprietes.pseudo()).isEqualTo("Patron");
        assertThat(proprietes.motDePasse()).isEqualTo("secret-de-test-123");
    }
}
