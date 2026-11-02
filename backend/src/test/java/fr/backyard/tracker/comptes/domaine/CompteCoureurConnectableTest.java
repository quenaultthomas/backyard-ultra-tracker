package fr.backyard.tracker.comptes.domaine;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * Incrément 9.5, CA2 : définition unique de « coureur connectable » (RG3), seule source de vérité du filtre de la
 * recherche. Signature attendue : {@code boolean Compte.estCoureurConnectable()}.
 */
class CompteCoureurConnectableTest {

    private static final Instant CREE_LE = Instant.parse("2026-11-02T09:00:00Z");
    private static final String EMPREINTE = "empreinte-opaque-de-test";
    private static final UUID ID = UUID.fromString("00000000-0000-0000-0000-0000000000a1");

    private static Compte compte(String pseudo, Role role, boolean actif) {
        return Compte.reconstituer(ID, new Pseudo(pseudo), EMPREINTE, role, CREE_LE, actif);
    }

    @Test
    @DisplayName("CA2 - un COUREUR actif avec empreinte est un coureur connectable")
    void doit_etre_connectable_un_coureur_actif_avec_empreinte() {
        assertThat(compte("Alice", Role.COUREUR, true).estCoureurConnectable()).isTrue();
    }

    @Test
    @DisplayName("CA2 - un COUREUR anonymisé (empreinte nulle) n'est pas un coureur connectable")
    void doit_ne_pas_etre_connectable_un_coureur_anonymise() {
        Compte anonymise = compte("Alice", Role.COUREUR, true).anonymiser();

        assertThat(anonymise.estCoureurConnectable()).isFalse();
    }

    @Test
    @DisplayName("CA2 - un COUREUR reconstitué sans empreinte n'est pas un coureur connectable")
    void doit_ne_pas_etre_connectable_un_coureur_sans_empreinte() {
        Compte sansEmpreinte = Compte.reconstituer(ID, new Pseudo("Alice"), null, Role.COUREUR, CREE_LE, true);

        assertThat(sansEmpreinte.estCoureurConnectable()).isFalse();
    }

    @Test
    @DisplayName("CA2 - un COUREUR inactif n'est pas un coureur connectable")
    void doit_ne_pas_etre_connectable_un_coureur_inactif() {
        assertThat(compte("Alice", Role.COUREUR, false).estCoureurConnectable()).isFalse();
    }

    @ParameterizedTest
    @EnumSource(value = Role.class, names = {"BENEVOLE", "ADMIN", "ADMIN_MASTER"})
    @DisplayName("CA2 - un Compte actif d'un autre rôle que COUREUR n'est pas un coureur connectable")
    void doit_ne_pas_etre_connectable_un_compte_actif_qui_n_est_pas_coureur(Role role) {
        assertThat(compte("Pseudo-" + role.name().toLowerCase(), role, true).estCoureurConnectable()).isFalse();
    }

    @Test
    @DisplayName("CA2 - la notion s'appuie sur peutSeConnecter : une réactivation rend le coureur connectable")
    void doit_suivre_peutSeConnecter_pour_un_coureur() {
        Compte coureur = compte("Alice", Role.COUREUR, true);

        assertThat(coureur.estCoureurConnectable()).isEqualTo(coureur.peutSeConnecter());
        assertThat(coureur.desactiver().estCoureurConnectable()).isEqualTo(coureur.desactiver().peutSeConnecter());
        assertThat(coureur.desactiver().reactiver().estCoureurConnectable()).isTrue();
    }
}
