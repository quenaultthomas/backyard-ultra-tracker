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
 * Incrément 9.7, CA1 : changement de rôle d'un Compte coureur en ADMIN (RG1, RG2).
 * Signature attendue : {@code Compte Compte.passerEnAdmin()} (nom indicatif de la spec), qui lève
 * {@link CompteNonGerableException} si {@link Compte#estCoureurConnectable()} est faux (même garde que
 * {@code passerEnBenevole()}).
 */
class ComptePasserEnAdminTest {

    private static final UUID ID = UUID.fromString("00000000-0000-0000-0000-0000000000a1");
    private static final Instant CREE_LE = Instant.parse("2026-10-26T09:00:00Z");
    private static final String EMPREINTE = "empreinte-argon2id";

    private static Compte compte(Role role, String empreinte, boolean actif) {
        return Compte.reconstituer(ID, new Pseudo("Alice"), empreinte, role, CREE_LE, actif);
    }

    @Test
    @DisplayName("CA1 - un coureur actif avec empreinte devient ADMIN")
    void doit_passer_un_coureur_actif_en_admin() {
        Compte admin = compte(Role.COUREUR, EMPREINTE, true).passerEnAdmin();

        assertThat(admin.role()).isEqualTo(Role.ADMIN);
    }

    @Test
    @DisplayName("CA1 - identifiant, pseudo, empreinte, date de création et état actif sont conservés")
    void doit_conserver_l_identite_du_compte_promu() {
        Compte admin = compte(Role.COUREUR, EMPREINTE, true).passerEnAdmin();

        assertThat(admin.id()).isEqualTo(ID);
        assertThat(admin.pseudo()).isEqualTo(new Pseudo("Alice"));
        assertThat(admin.empreinteMotDePasse()).isEqualTo(EMPREINTE);
        assertThat(admin.creeLe()).isEqualTo(CREE_LE);
        assertThat(admin.actif()).isTrue();
        assertThat(admin.peutSeConnecter()).isTrue();
    }

    @Test
    @DisplayName("CA1 - le Compte d'origine n'est pas modifié (immuable)")
    void doit_laisser_le_compte_d_origine_inchange() {
        Compte coureur = compte(Role.COUREUR, EMPREINTE, true);

        coureur.passerEnAdmin();

        assertThat(coureur.role()).isEqualTo(Role.COUREUR);
        assertThat(coureur.estCoureurConnectable()).isTrue();
    }

    @Test
    @DisplayName("CA1 - le Compte promu n'est plus un coureur connectable (il disparaît de la recherche)")
    void doit_ne_plus_etre_un_coureur_connectable_apres_promotion() {
        assertThat(compte(Role.COUREUR, EMPREINTE, true).passerEnAdmin().estCoureurConnectable()).isFalse();
    }

    @Test
    @DisplayName("CA1 - un coureur anonymisé est refusé")
    void doit_refuser_un_coureur_anonymise() {
        Compte anonyme = compte(Role.COUREUR, EMPREINTE, true).anonymiser();

        assertThatThrownBy(anonyme::passerEnAdmin).isInstanceOf(CompteNonGerableException.class);
    }

    @Test
    @DisplayName("CA1 - un coureur sans empreinte est refusé")
    void doit_refuser_un_coureur_sans_empreinte() {
        Compte sansEmpreinte = compte(Role.COUREUR, null, true);

        assertThatThrownBy(sansEmpreinte::passerEnAdmin).isInstanceOf(CompteNonGerableException.class);
    }

    @Test
    @DisplayName("CA1 - un coureur inactif est refusé")
    void doit_refuser_un_coureur_inactif() {
        Compte inactif = compte(Role.COUREUR, EMPREINTE, false);

        assertThatThrownBy(inactif::passerEnAdmin).isInstanceOf(CompteNonGerableException.class);
    }

    @ParameterizedTest(name = "CA1 - un compte {0} est refusé")
    @EnumSource(value = Role.class, names = {"BENEVOLE", "ADMIN", "ADMIN_MASTER"})
    @DisplayName("CA1 - un compte qui n'est pas coureur est refusé")
    void doit_refuser_un_compte_qui_n_est_pas_coureur(Role role) {
        Compte autre = compte(role, EMPREINTE, true);

        assertThatThrownBy(autre::passerEnAdmin).isInstanceOf(CompteNonGerableException.class);
        assertThat(autre.role()).isEqualTo(role);
    }

    @Test
    @DisplayName("CA1 - un second passage en admin est refusé (non idempotent)")
    void doit_refuser_un_second_passage_en_admin() {
        Compte admin = compte(Role.COUREUR, EMPREINTE, true).passerEnAdmin();

        assertThatThrownBy(admin::passerEnAdmin).isInstanceOf(CompteNonGerableException.class);
    }

    @Test
    @DisplayName("CA1 - un bénévole issu d'un coureur ne devient pas admin")
    void doit_refuser_de_passer_en_admin_un_coureur_deja_passe_en_benevole() {
        Compte benevole = compte(Role.COUREUR, EMPREINTE, true).passerEnBenevole();

        assertThatThrownBy(benevole::passerEnAdmin).isInstanceOf(CompteNonGerableException.class);
    }
}
