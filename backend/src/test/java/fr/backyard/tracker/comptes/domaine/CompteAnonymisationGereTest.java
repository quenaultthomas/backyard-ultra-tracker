package fr.backyard.tracker.comptes.domaine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Incrément 9.6a, CA1 : anonymisation d'un Compte BENEVOLE ou ADMIN (RG3).
 * Signature attendue : {@code Compte#anonymiserBenevoleOuAdmin()} (nom choisi par le testeur), qui lève
 * {@link SuppressionCompteInterditeException} pour l'ADMIN_MASTER.
 */
class CompteAnonymisationGereTest {

    private static final Instant CREE_LE = Instant.parse("2026-10-02T09:00:00Z");
    private static final UUID ID = UUID.fromString("0a1b2c3d-4e5f-6789-abcd-ef0123456789");

    private static Compte compte(Role role, boolean actif) {
        return Compte.reconstituer(ID, new Pseudo("Marc"), "empreinte:secrete", role, CREE_LE, actif);
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(value = Role.class, names = {"BENEVOLE", "ADMIN"})
    @DisplayName("CA1 - un bénévole ou un admin anonymisé garde id et date de création, devient « Coureur anonyme » de rôle COUREUR")
    void doit_anonymiser_un_compte_gere_en_conservant_id_et_date(Role role) {
        Compte apres = compte(role, true).anonymiserBenevoleOuAdmin();

        assertThat(apres.id()).isEqualTo(ID);
        assertThat(apres.creeLe()).isEqualTo(CREE_LE);
        assertThat(apres.pseudo()).isEqualTo(Pseudo.anonyme());
        assertThat(apres.pseudo().valeur()).isEqualTo("Coureur anonyme");
        assertThat(apres.role()).isEqualTo(Role.COUREUR);
        assertThat(apres.estAnonyme()).isTrue();
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(value = Role.class, names = {"BENEVOLE", "ADMIN"})
    @DisplayName("CA1 - la clé d'unicité est « # » plus hexadécimal propre au Compte (30 caractères), l'ancienne clé est libérée")
    void doit_construire_une_cle_propre_au_compte_et_liberer_l_ancienne(Role role) {
        Compte avant = compte(role, true);

        Compte apres = avant.anonymiserBenevoleOuAdmin();

        assertThat(apres.pseudoNormalise()).hasSize(30).isEqualTo("#0a1b2c3d4e5f6789abcdef0123456");
        assertThat(apres.pseudoNormalise()).isNotEqualTo(avant.pseudoNormalise());
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(value = Role.class, names = {"BENEVOLE", "ADMIN"})
    @DisplayName("CA1 - l'empreinte est effacée et la connexion devient impossible")
    void doit_effacer_l_empreinte_et_interdire_la_connexion(Role role) {
        Compte apres = compte(role, true).anonymiserBenevoleOuAdmin();

        assertThat(apres.empreinteMotDePasse()).isNull();
        assertThat(apres.peutSeConnecter()).isFalse();
    }

    @ParameterizedTest(name = "actif = {0}")
    @ValueSource(booleans = {true, false})
    @DisplayName("CA1 - l'état actif est inchangé par l'anonymisation d'un bénévole")
    void doit_conserver_l_etat_actif_d_un_benevole(boolean actif) {
        Compte apres = compte(Role.BENEVOLE, actif).anonymiserBenevoleOuAdmin();

        assertThat(apres.actif()).isEqualTo(actif);
    }

    @ParameterizedTest(name = "actif = {0}")
    @ValueSource(booleans = {true, false})
    @DisplayName("CA1 - l'état actif est inchangé par l'anonymisation d'un admin")
    void doit_conserver_l_etat_actif_d_un_admin(boolean actif) {
        Compte apres = compte(Role.ADMIN, actif).anonymiserBenevoleOuAdmin();

        assertThat(apres.actif()).isEqualTo(actif);
    }

    @Test
    @DisplayName("CA1 - l'admin master ne peut pas être anonymisé et reste inchangé")
    void doit_refuser_l_anonymisation_de_l_admin_master() {
        Compte master = compte(Role.ADMIN_MASTER, true);

        assertThatThrownBy(master::anonymiserBenevoleOuAdmin).isInstanceOf(SuppressionCompteInterditeException.class);

        assertThat(master.role()).isEqualTo(Role.ADMIN_MASTER);
        assertThat(master.empreinteMotDePasse()).isEqualTo("empreinte:secrete");
        assertThat(master.peutSeConnecter()).isTrue();
    }

    @Test
    @DisplayName("CA1 - le Compte d'origine n'est pas modifié (domaine immuable)")
    void doit_laisser_le_compte_d_origine_intact() {
        Compte avant = compte(Role.BENEVOLE, true);

        avant.anonymiserBenevoleOuAdmin();

        assertThat(avant.pseudo().valeur()).isEqualTo("Marc");
        assertThat(avant.role()).isEqualTo(Role.BENEVOLE);
        assertThat(avant.peutSeConnecter()).isTrue();
    }

    @Test
    @DisplayName("CA1 - le Compte anonymisé n'expose ni l'ancien pseudo ni l'empreinte dans toString")
    void doit_ne_jamais_exposer_pseudo_ni_empreinte_dans_to_string() {
        String texte = compte(Role.ADMIN, true).anonymiserBenevoleOuAdmin().toString();

        assertThat(texte).doesNotContainIgnoringCase("marc").doesNotContain("empreinte:secrete");
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(value = Role.class, names = {"BENEVOLE", "ADMIN"})
    @DisplayName("CA1 - le résultat est identique à celui de anonymiser() du 3.6 pour un coureur de même identité")
    void doit_donner_le_meme_compte_anonymise_que_celui_d_un_coureur(Role role) {
        Compte gere = compte(role, true).anonymiserBenevoleOuAdmin();
        Compte coureur = compte(Role.COUREUR, true).anonymiser();

        assertThat(gere.id()).isEqualTo(coureur.id());
        assertThat(gere.pseudo()).isEqualTo(coureur.pseudo());
        assertThat(gere.pseudoNormalise()).isEqualTo(coureur.pseudoNormalise());
        assertThat(gere.empreinteMotDePasse()).isEqualTo(coureur.empreinteMotDePasse());
        assertThat(gere.role()).isEqualTo(coureur.role());
        assertThat(gere.creeLe()).isEqualTo(coureur.creeLe());
        assertThat(gere.actif()).isEqualTo(coureur.actif());
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(value = Role.class, names = {"BENEVOLE", "ADMIN", "ADMIN_MASTER"})
    @DisplayName("CA1 - anonymiser() du 3.6 reste refusée pour un bénévole, un admin et l'admin master")
    void doit_continuer_de_refuser_anonymiser_pour_un_compte_qui_n_est_pas_coureur(Role role) {
        Compte compte = compte(role, true);

        assertThatThrownBy(compte::anonymiser).isInstanceOf(SuppressionCompteInterditeException.class);
    }
}
