package fr.backyard.tracker.comptes.domaine;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Incrément 1.5 : fabrique de comptes ADMIN. */
class CompteAdminTest {

    private static final Instant MAINTENANT = Instant.parse("2026-10-02T10:00:00Z");

    @Test
    @DisplayName("CA1 - Compte.creerAdmin produit un compte de rôle ADMIN connectable")
    void doit_creer_un_compte_de_role_admin_connectable() {
        Compte compte = Compte.creerAdmin(new Pseudo("Nadia"), "empreinte", MAINTENANT);

        assertThat(compte.role()).isEqualTo(Role.ADMIN);
        assertThat(compte.id()).isNotNull();
        assertThat(compte.pseudo().valeur()).isEqualTo("Nadia");
        assertThat(compte.pseudoNormalise()).isEqualTo("nadia");
        assertThat(compte.creeLe()).isEqualTo(MAINTENANT);
        assertThat(compte.empreinteMotDePasse()).isEqualTo("empreinte");
        assertThat(compte.peutSeConnecter()).isTrue();
    }

    @Test
    @DisplayName("CA1 - Compte.creerCoureur reste un compte COUREUR")
    void doit_continuer_a_creer_un_compte_coureur() {
        Compte compte = Compte.creerCoureur(new Pseudo("Alice"), "empreinte", MAINTENANT);

        assertThat(compte.role()).isEqualTo(Role.COUREUR);
    }
}
