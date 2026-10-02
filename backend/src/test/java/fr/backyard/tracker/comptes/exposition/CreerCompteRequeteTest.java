package fr.backyard.tracker.comptes.exposition;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class CreerCompteRequeteTest {

    @Test
    @DisplayName("CA4 - toString du DTO masque le mot de passe")
    void doit_masquer_le_mot_de_passe_dans_to_string() {
        String texte = new CreerCompteRequete("Alice", "secret-de-test-123").toString();

        assertThat(texte)
                .isEqualTo("CreerCompteRequete[pseudo=Alice, motDePasse=masqué]")
                .doesNotContain("secret-de-test-123");
    }

    @Test
    @DisplayName("CA4 - toString du DTO ne plante pas avec un mot de passe null")
    void doit_masquer_meme_un_mot_de_passe_absent() {
        assertThat(new CreerCompteRequete("Alice", null).toString()).doesNotContain("null");
    }
}
