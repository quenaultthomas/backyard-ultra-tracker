package fr.backyard.tracker.comptes.domaine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/** Anonymisation d'un Compte (incrément 3.6, CA1 ; RG3, RG6, RG12). */
class CompteAnonymisationTest {

    private static final Instant CREE_LE = Instant.parse("2026-10-02T09:00:00Z");
    private static final UUID ID_ALICE = UUID.fromString("0a1b2c3d-4e5f-6789-abcd-ef0123456789");
    private static final UUID ID_BRUNO = UUID.fromString("ffeeddcc-bbaa-9988-7766-554433221100");

    private static Compte compte(UUID id, String pseudo, Role role) {
        return Compte.reconstituer(id, new Pseudo(pseudo), "empreinte:secrete", role, CREE_LE);
    }

    private static Compte aliceCoureuse() {
        return compte(ID_ALICE, "Alice", Role.COUREUR);
    }

    @Test
    @DisplayName("CA1 - un coureur anonymisé garde id, rôle et date de création")
    void doit_conserver_id_role_et_date_de_creation_a_l_anonymisation() {
        Compte avant = aliceCoureuse();

        Compte apres = avant.anonymiser();

        assertThat(apres.id()).isEqualTo(ID_ALICE);
        assertThat(apres.role()).isEqualTo(Role.COUREUR);
        assertThat(apres.creeLe()).isEqualTo(CREE_LE);
    }

    @Test
    @DisplayName("CA1 - un coureur anonymisé s'appelle « Coureur anonyme »")
    void doit_afficher_coureur_anonyme_comme_pseudo() {
        Compte apres = aliceCoureuse().anonymiser();

        assertThat(apres.pseudo().valeur()).isEqualTo("Coureur anonyme");
        assertThat(apres.pseudo()).isEqualTo(Pseudo.anonyme());
    }

    @Test
    @DisplayName("CA1 - un coureur anonymisé n'a plus d'empreinte et ne peut plus se connecter")
    void doit_effacer_l_empreinte_et_interdire_la_connexion() {
        Compte apres = aliceCoureuse().anonymiser();

        assertThat(apres.empreinteMotDePasse()).isNull();
        assertThat(apres.peutSeConnecter()).isFalse();
    }

    @Test
    @DisplayName("CA1 - la clé d'unicité fait 30 caractères : « # » puis les 29 premiers hexadécimaux de l'identifiant")
    void doit_construire_la_cle_d_unicite_avec_diese_et_29_caracteres_hexadecimaux() {
        Compte apres = aliceCoureuse().anonymiser();

        assertThat(apres.pseudoNormalise()).hasSize(30);
        assertThat(apres.pseudoNormalise()).isEqualTo("#0a1b2c3d4e5f6789abcdef0123456");
    }

    @Test
    @DisplayName("CA1 - deux Comptes anonymisés ont deux clés d'unicité distinctes")
    void doit_donner_des_cles_distinctes_a_deux_comptes_anonymises() {
        Compte alice = aliceCoureuse().anonymiser();
        Compte bruno = compte(ID_BRUNO, "Bruno", Role.COUREUR).anonymiser();

        assertThat(alice.pseudoNormalise()).isNotEqualTo(bruno.pseudoNormalise());
    }

    @Test
    @DisplayName("CA1 - l'ancienne clé du pseudo ne subsiste pas après anonymisation")
    void doit_ne_plus_exposer_l_ancienne_cle_de_pseudo() {
        Compte avant = aliceCoureuse();

        Compte apres = avant.anonymiser();

        assertThat(avant.pseudoNormalise()).isEqualTo("alice");
        assertThat(apres.pseudoNormalise()).isNotEqualTo("alice");
        assertThat(apres.toString()).doesNotContainIgnoringCase("alice").doesNotContain("empreinte:secrete");
    }

    @Test
    @DisplayName("CA1 - la clé anonyme et « Coureur anonyme » sont refusées à la saisie (PSEUDO_CARACTERES)")
    void doit_refuser_a_la_saisie_la_cle_anonyme_et_le_libelle_anonyme() {
        String cle = aliceCoureuse().anonymiser().pseudoNormalise();

        assertThat(Pseudo.verifier(cle)).get().extracting(ViolationValidation::code).isEqualTo("PSEUDO_CARACTERES");
        assertThat(Pseudo.verifier("Coureur anonyme")).get().extracting(ViolationValidation::code)
                .isEqualTo("PSEUDO_CARACTERES");
        assertThatThrownBy(() -> new Pseudo("Coureur anonyme")).isInstanceOf(DonneesCompteInvalidesException.class);
    }

    @Test
    @DisplayName("CA1 - le Compte d'origine n'est pas modifié par l'anonymisation (domaine immuable)")
    void doit_laisser_le_compte_d_origine_intact() {
        Compte avant = aliceCoureuse();

        avant.anonymiser();

        assertThat(avant.pseudo().valeur()).isEqualTo("Alice");
        assertThat(avant.empreinteMotDePasse()).isEqualTo("empreinte:secrete");
        assertThat(avant.peutSeConnecter()).isTrue();
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(value = Role.class, names = "COUREUR", mode = EnumSource.Mode.EXCLUDE)
    @DisplayName("CA1 - tout rôle autre que COUREUR lève SuppressionCompteInterditeException, Compte inchangé")
    void doit_interdire_l_anonymisation_d_un_compte_qui_n_est_pas_coureur(Role role) {
        Compte compte = compte(ID_ALICE, "Alice", role);

        assertThatThrownBy(compte::anonymiser).isInstanceOf(SuppressionCompteInterditeException.class);

        assertThat(compte.pseudo().valeur()).isEqualTo("Alice");
        assertThat(compte.empreinteMotDePasse()).isEqualTo("empreinte:secrete");
        assertThat(compte.peutSeConnecter()).isTrue();
        assertThat(compte.role()).isEqualTo(role);
    }

    @Test
    @DisplayName("CA1 - toString du Compte anonymisé ne contient ni empreinte ni mot de passe")
    void doit_ne_jamais_exposer_d_empreinte_dans_to_string() {
        assertThat(aliceCoureuse().toString()).doesNotContain("empreinte:secrete");
        assertThat(aliceCoureuse().anonymiser().toString()).doesNotContain("empreinte");
    }
}
