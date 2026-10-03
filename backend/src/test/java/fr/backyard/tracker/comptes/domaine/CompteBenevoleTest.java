package fr.backyard.tracker.comptes.domaine;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Incrément 1.6a : fabrique de comptes BENEVOLE. */
class CompteBenevoleTest {

    private static final Instant MAINTENANT = Instant.parse("2026-10-02T10:00:00Z");

    @Test
    @DisplayName("CA1 - Compte.creerBenevole produit un compte de rôle BENEVOLE connectable")
    void doit_creer_un_compte_de_role_benevole_connectable() {
        Compte compte = Compte.creerBenevole(new Pseudo("Léo"), "empreinte", MAINTENANT);

        assertThat(compte.role()).isEqualTo(Role.BENEVOLE);
        assertThat(compte.id()).isNotNull();
        assertThat(compte.pseudo().valeur()).isEqualTo("Léo");
        assertThat(compte.pseudoNormalise()).isEqualTo("léo");
        assertThat(compte.creeLe()).isEqualTo(MAINTENANT);
        assertThat(compte.empreinteMotDePasse()).isEqualTo("empreinte");
        assertThat(compte.peutSeConnecter()).isTrue();
    }

    @Test
    @DisplayName("CA1 - creerAdmin et creerCoureur restent inchangés")
    void doit_continuer_a_creer_admin_et_coureur_avec_leur_role() {
        assertThat(Compte.creerAdmin(new Pseudo("Nadia"), "empreinte", MAINTENANT).role()).isEqualTo(Role.ADMIN);
        assertThat(Compte.creerCoureur(new Pseudo("Alice"), "empreinte", MAINTENANT).role()).isEqualTo(Role.COUREUR);
    }
}
