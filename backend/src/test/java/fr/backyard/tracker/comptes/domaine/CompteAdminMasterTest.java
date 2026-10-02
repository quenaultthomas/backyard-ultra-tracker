package fr.backyard.tracker.comptes.domaine;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class CompteAdminMasterTest {

    private static final Instant MAINTENANT = Instant.parse("2026-10-02T10:00:00Z");

    @Test
    @DisplayName("CA10 - Compte.creerAdminMaster produit un compte de rôle ADMIN_MASTER connectable")
    void doit_creer_un_compte_de_role_admin_master() {
        Compte compte = Compte.creerAdminMaster(new Pseudo("Patron"), "empreinte", MAINTENANT);

        assertThat(compte.role()).isEqualTo(Role.ADMIN_MASTER);
        assertThat(compte.id()).isNotNull();
        assertThat(compte.pseudo().valeur()).isEqualTo("Patron");
        assertThat(compte.creeLe()).isEqualTo(MAINTENANT);
        assertThat(compte.empreinteMotDePasse()).isEqualTo("empreinte");
        assertThat(compte.peutSeConnecter()).isTrue();
    }

    @Test
    @DisplayName("CA10 - Compte.creerCoureur reste un compte COUREUR")
    void doit_continuer_a_creer_un_compte_coureur() {
        Compte compte = Compte.creerCoureur(new Pseudo("Alice"), "empreinte", MAINTENANT);

        assertThat(compte.role()).isEqualTo(Role.COUREUR);
    }
}
