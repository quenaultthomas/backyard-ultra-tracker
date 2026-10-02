package fr.backyard.tracker.comptes.domaine;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class CompteConnexionTest {

    private static final Instant CREE_LE = Instant.parse("2026-10-02T10:00:00Z");

    @Test
    @DisplayName("CA4 - un compte avec empreinte peut se connecter")
    void doit_pouvoir_se_connecter_un_compte_avec_empreinte() {
        Compte compte = Compte.creerCoureur(new Pseudo("Alice"), "empreinte-valide", CREE_LE);

        assertThat(compte.peutSeConnecter()).isTrue();
    }

    @Test
    @DisplayName("CA4 - un compte sans empreinte (anonymisé) ne peut pas se connecter")
    void doit_ne_pas_pouvoir_se_connecter_un_compte_sans_empreinte() {
        Compte compte = Compte.reconstituer(
                UUID.randomUUID(), new Pseudo("Anonyme1"), null, Role.COUREUR, CREE_LE);

        assertThat(compte.peutSeConnecter()).isFalse();
    }
}
