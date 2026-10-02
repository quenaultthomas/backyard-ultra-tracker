package fr.backyard.tracker.comptes.exposition;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ConnexionRequeteTest {

    @Test
    @DisplayName("CA7 - toString du DTO de connexion masque le mot de passe")
    void doit_masquer_le_mot_de_passe_dans_to_string() {
        String texte = new ConnexionRequete("Alice", "secret-de-test-123").toString();

        assertThat(texte)
                .isEqualTo("ConnexionRequete[pseudo=Alice, motDePasse=masqué]")
                .doesNotContain("secret-de-test-123");
    }
}
