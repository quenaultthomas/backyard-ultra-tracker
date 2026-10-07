package fr.backyard.tracker.comptes.exposition;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Incrément 3.6, CA1 (RG14) : la requête de suppression ne montre jamais le mot de passe. */
class SupprimerCompteRequeteTest {

    @Test
    @DisplayName("CA1 - toString de la requête de suppression masque le mot de passe actuel")
    void doit_masquer_le_mot_de_passe_actuel_dans_to_string() {
        String texte = new SupprimerCompteRequete("secret-de-test-123").toString();

        assertThat(texte).doesNotContain("secret-de-test-123");
    }
}
