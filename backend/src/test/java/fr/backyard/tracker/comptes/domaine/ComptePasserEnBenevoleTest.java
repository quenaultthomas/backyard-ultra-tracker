package fr.backyard.tracker.comptes.domaine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * Incrément 9.6b, CA12 : changement de rôle d'un Compte coureur en BENEVOLE (RG11, RG12).
 * Signature attendue : {@code Compte Compte.passerEnBenevole()} (nom indicatif de la spec), qui lève
 * {@link CompteNonGerableException} si {@link Compte#estCoureurConnectable()} est faux.
 */
class ComptePasserEnBenevoleTest {

    private static final UUID ID = UUID.fromString("00000000-0000-0000-0000-0000000000a1");
    private static final Instant CREE_LE = Instant.parse("2026-10-26T09:00:00Z");
    private static final String EMPREINTE = "empreinte-argon2id";

    private static Compte compte(Role role, String empreinte, boolean actif) {
        return Compte.reconstituer(ID, new Pseudo("Alice"), empreinte, role, CREE_LE, actif);
    }

    @Test
    @DisplayName("CA12 - un coureur actif devient BENEVOLE")
    void doit_passer_un_coureur_actif_en_benevole() {
        Compte benevole = compte(Role.COUREUR, EMPREINTE, true).passerEnBenevole();

        assertThat(benevole.role()).isEqualTo(Role.BENEVOLE);
    }

    @Test
    @DisplayName("CA12 - identifiant, pseudo, empreinte, date de création et état actif sont conservés")
    void doit_conserver_l_identite_du_compte_promu() {
        Compte benevole = compte(Role.COUREUR, EMPREINTE, true).passerEnBenevole();

        assertThat(benevole.id()).isEqualTo(ID);
        assertThat(benevole.pseudo()).isEqualTo(new Pseudo("Alice"));
        assertThat(benevole.pseudoNormalise()).isEqualTo(new Pseudo("Alice").normalise());
        assertThat(benevole.empreinteMotDePasse()).isEqualTo(EMPREINTE);
        assertThat(benevole.creeLe()).isEqualTo(CREE_LE);
        assertThat(benevole.actif()).isTrue();
        assertThat(benevole.peutSeConnecter()).isTrue();
    }

    @Test
    @DisplayName("CA12 - le Compte d'origine n'est pas modifié (immuable)")
    void doit_laisser_le_compte_d_origine_inchange() {
        Compte coureur = compte(Role.COUREUR, EMPREINTE, true);

        coureur.passerEnBenevole();

        assertThat(coureur.role()).isEqualTo(Role.COUREUR);
        assertThat(coureur.estCoureurConnectable()).isTrue();
    }

    @Test
    @DisplayName("CA12 - le Compte promu n'est plus un coureur connectable (il disparaît de la recherche)")
    void doit_ne_plus_etre_un_coureur_connectable_apres_promotion() {
        Compte benevole = compte(Role.COUREUR, EMPREINTE, true).passerEnBenevole();

        assertThat(benevole.estCoureurConnectable()).isFalse();
    }

    @Test
    @DisplayName("CA12 - un coureur anonymisé est refusé")
    void doit_refuser_un_coureur_anonymise() {
        Compte anonyme = compte(Role.COUREUR, EMPREINTE, true).anonymiser();

        assertThatThrownBy(anonyme::passerEnBenevole).isInstanceOf(CompteNonGerableException.class);
    }

    @Test
    @DisplayName("CA12 - un coureur sans empreinte est refusé")
    void doit_refuser_un_coureur_sans_empreinte() {
        Compte sansEmpreinte = compte(Role.COUREUR, null, true);

        assertThatThrownBy(sansEmpreinte::passerEnBenevole).isInstanceOf(CompteNonGerableException.class);
    }

    @Test
    @DisplayName("CA12 - un coureur inactif est refusé")
    void doit_refuser_un_coureur_inactif() {
        Compte inactif = compte(Role.COUREUR, EMPREINTE, false);

        assertThatThrownBy(inactif::passerEnBenevole).isInstanceOf(CompteNonGerableException.class);
    }

    @ParameterizedTest(name = "CA12 - un compte {0} est refusé")
    @EnumSource(value = Role.class, names = {"BENEVOLE", "ADMIN", "ADMIN_MASTER"})
    @DisplayName("CA12 - un compte qui n'est pas coureur est refusé")
    void doit_refuser_un_compte_qui_n_est_pas_coureur(Role role) {
        Compte autre = compte(role, EMPREINTE, true);

        assertThatThrownBy(autre::passerEnBenevole).isInstanceOf(CompteNonGerableException.class);
        assertThat(autre.role()).isEqualTo(role);
    }

    @Test
    @DisplayName("CA12 - un second passage en bénévole est refusé (non idempotent)")
    void doit_refuser_un_second_passage_en_benevole() {
        Compte benevole = compte(Role.COUREUR, EMPREINTE, true).passerEnBenevole();

        assertThatThrownBy(benevole::passerEnBenevole).isInstanceOf(CompteNonGerableException.class);
    }
}
