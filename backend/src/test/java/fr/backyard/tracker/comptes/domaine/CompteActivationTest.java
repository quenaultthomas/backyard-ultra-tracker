package fr.backyard.tracker.comptes.domaine;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Incrément 8.1, CA1 : état actif d'un Compte, désactivation, réactivation, connectabilité (RG1). */
class CompteActivationTest {

    private static final Instant CREE_LE = Instant.parse("2026-10-26T09:00:00Z");
    private static final String EMPREINTE = "empreinte-opaque-de-test";
    private static final UUID ID = UUID.fromString("00000000-0000-0000-0000-0000000000a1");

    private static Compte benevoleActif() {
        return Compte.reconstituer(ID, new Pseudo("Léo"), EMPREINTE, Role.BENEVOLE, CREE_LE, true);
    }

    private static Compte benevoleInactif() {
        return Compte.reconstituer(ID, new Pseudo("Léo"), EMPREINTE, Role.BENEVOLE, CREE_LE, false);
    }

    @Test
    @DisplayName("CA1 - un Compte coureur créé est actif")
    void doit_creer_un_compte_coureur_actif() {
        assertThat(Compte.creerCoureur(new Pseudo("Alice"), EMPREINTE, CREE_LE).actif()).isTrue();
    }

    @Test
    @DisplayName("CA1 - un Compte admin créé est actif")
    void doit_creer_un_compte_admin_actif() {
        assertThat(Compte.creerAdmin(new Pseudo("Nadia"), EMPREINTE, CREE_LE).actif()).isTrue();
    }

    @Test
    @DisplayName("CA1 - un Compte bénévole créé est actif")
    void doit_creer_un_compte_benevole_actif() {
        assertThat(Compte.creerBenevole(new Pseudo("Léo"), EMPREINTE, CREE_LE).actif()).isTrue();
    }

    @Test
    @DisplayName("CA1 - le Compte admin master créé est actif")
    void doit_creer_le_compte_admin_master_actif() {
        assertThat(Compte.creerAdminMaster(new Pseudo("Patron"), EMPREINTE, CREE_LE).actif()).isTrue();
    }

    @Test
    @DisplayName("CA1 - reconstituer avec actif=false donne un Compte inactif, avec actif=true un Compte actif")
    void doit_reconstituer_l_etat_lu_en_base() {
        assertThat(benevoleInactif().actif()).isFalse();
        assertThat(benevoleActif().actif()).isTrue();
    }

    @Test
    @DisplayName("CA1 - desactiver retourne un Compte inactif de même id, pseudo, empreinte, rôle et date de création")
    void doit_desactiver_un_compte_en_conservant_son_identite() {
        Compte desactive = benevoleActif().desactiver();

        assertThat(desactive.actif()).isFalse();
        assertThat(desactive.id()).isEqualTo(ID);
        assertThat(desactive.pseudo()).isEqualTo(new Pseudo("Léo"));
        assertThat(desactive.pseudoNormalise()).isEqualTo(benevoleActif().pseudoNormalise());
        assertThat(desactive.empreinteMotDePasse()).isEqualTo(EMPREINTE);
        assertThat(desactive.role()).isEqualTo(Role.BENEVOLE);
        assertThat(desactive.creeLe()).isEqualTo(CREE_LE);
    }

    @Test
    @DisplayName("CA1 - desactiver ne modifie pas le Compte d'origine (immuabilité)")
    void doit_laisser_le_compte_d_origine_inchange_apres_desactivation() {
        Compte origine = benevoleActif();

        origine.desactiver();

        assertThat(origine.actif()).isTrue();
    }

    @Test
    @DisplayName("CA1 - reactiver retourne un Compte actif de même id, pseudo, empreinte, rôle et date de création")
    void doit_reactiver_un_compte_en_conservant_son_identite() {
        Compte reactive = benevoleInactif().reactiver();

        assertThat(reactive.actif()).isTrue();
        assertThat(reactive.id()).isEqualTo(ID);
        assertThat(reactive.pseudo()).isEqualTo(new Pseudo("Léo"));
        assertThat(reactive.empreinteMotDePasse()).isEqualTo(EMPREINTE);
        assertThat(reactive.role()).isEqualTo(Role.BENEVOLE);
        assertThat(reactive.creeLe()).isEqualTo(CREE_LE);
    }

    @Test
    @DisplayName("CA1 - désactiver un Compte déjà inactif, réactiver un Compte déjà actif : l'état reste le même")
    void doit_rester_dans_le_meme_etat_pour_une_operation_redondante() {
        assertThat(benevoleInactif().desactiver().actif()).isFalse();
        assertThat(benevoleActif().reactiver().actif()).isTrue();
    }

    @Test
    @DisplayName("CA1 - un Compte actif avec empreinte peut se connecter")
    void doit_pouvoir_se_connecter_quand_actif_et_avec_empreinte() {
        assertThat(benevoleActif().peutSeConnecter()).isTrue();
    }

    @Test
    @DisplayName("CA1 - un Compte inactif avec empreinte ne peut pas se connecter")
    void doit_refuser_la_connexion_d_un_compte_inactif() {
        assertThat(benevoleInactif().peutSeConnecter()).isFalse();
    }

    @Test
    @DisplayName("CA1 - un Compte actif sans empreinte (anonymisé) ne peut pas se connecter")
    void doit_refuser_la_connexion_d_un_compte_actif_sans_empreinte() {
        Compte sansEmpreinte = Compte.reconstituer(ID, new Pseudo("Alice"), null, Role.COUREUR, CREE_LE, true);

        assertThat(sansEmpreinte.peutSeConnecter()).isFalse();
    }

    @Test
    @DisplayName("CA1 - un Compte inactif et sans empreinte ne peut pas se connecter")
    void doit_refuser_la_connexion_d_un_compte_inactif_et_sans_empreinte() {
        Compte sansEmpreinte = Compte.reconstituer(ID, new Pseudo("Alice"), null, Role.COUREUR, CREE_LE, false);

        assertThat(sansEmpreinte.peutSeConnecter()).isFalse();
    }

    @Test
    @DisplayName("CA1 - réactiver un Compte inactif redonne la connexion avec la même empreinte")
    void doit_pouvoir_se_connecter_apres_reactivation() {
        Compte reactive = benevoleInactif().reactiver();

        assertThat(reactive.peutSeConnecter()).isTrue();
        assertThat(reactive.empreinteMotDePasse()).isEqualTo(EMPREINTE);
    }

    @Test
    @DisplayName("CA1 - l'anonymisation d'un coureur reste inchangée : pseudo anonyme, sans empreinte, non connectable")
    void doit_anonymiser_un_coureur_comme_avant() {
        Compte alice = Compte.creerCoureur(new Pseudo("Alice"), EMPREINTE, CREE_LE);

        Compte anonyme = alice.anonymiser();

        assertThat(anonyme.id()).isEqualTo(alice.id());
        assertThat(anonyme.estAnonyme()).isTrue();
        assertThat(anonyme.empreinteMotDePasse()).isNull();
        assertThat(anonyme.peutSeConnecter()).isFalse();
    }

    @Test
    @DisplayName("CA1 - réactiver un Compte anonymisé ne le rend pas connectable (pas d'empreinte)")
    void doit_laisser_un_compte_anonymise_non_connectable_apres_reactivation() {
        Compte anonyme = Compte.creerCoureur(new Pseudo("Alice"), EMPREINTE, CREE_LE).anonymiser();

        assertThat(anonyme.reactiver().peutSeConnecter()).isFalse();
    }

    @Test
    @DisplayName("CA1 - toString ne contient ni l'empreinte ni de mot de passe")
    void doit_masquer_l_empreinte_dans_to_string() {
        assertThat(benevoleActif().toString()).doesNotContain(EMPREINTE).doesNotContainIgnoringCase("motdepasse");
        assertThat(benevoleInactif().toString()).doesNotContain(EMPREINTE);
    }
}
