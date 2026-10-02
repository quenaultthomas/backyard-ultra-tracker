package fr.backyard.tracker.comptes.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import fr.backyard.tracker.comptes.domaine.Compte;
import fr.backyard.tracker.comptes.domaine.DepotComptes;
import fr.backyard.tracker.comptes.domaine.DonneesCompteInvalidesException;
import fr.backyard.tracker.comptes.domaine.EncodeurMotDePasse;
import fr.backyard.tracker.comptes.domaine.IdentifiantsInvalidesException;
import fr.backyard.tracker.comptes.domaine.MotDePasse;
import fr.backyard.tracker.comptes.domaine.Pseudo;
import fr.backyard.tracker.comptes.domaine.Role;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ConnecterTest {

    private static final Instant MAINTENANT = Instant.parse("2026-10-02T10:00:00Z");
    private static final String MOT_DE_PASSE = "un-mot-de-passe-12";
    private static final String PREFIXE_EMPREINTE = "empreinte:";

    private final Clock horlogeFixe = Clock.fixed(MAINTENANT, ZoneOffset.UTC);
    private final DepotComptesEnMemoire depot = new DepotComptesEnMemoire();
    private final EncodeurEspion encodeur = new EncodeurEspion();
    private final Connecter connecter = new Connecter(depot, encodeur);

    private Compte alice() {
        Compte alice = Compte.creerCoureur(
                new Pseudo("Alice"), encodeur.encoder(new MotDePasse(MOT_DE_PASSE)), horlogeFixe.instant());
        depot.comptes.add(alice);
        return alice;
    }

    private Compte compteSansEmpreinte(String pseudo) {
        Compte anonyme = Compte.reconstituer(
                UUID.randomUUID(), new Pseudo(pseudo), null, Role.COUREUR, horlogeFixe.instant());
        depot.comptes.add(anonyme);
        return anonyme;
    }

    private String messageDEchec(String pseudo, String motDePasse) {
        try {
            connecter.executer(pseudo, motDePasse);
        } catch (IdentifiantsInvalidesException e) {
            return e.getMessage();
        }
        throw new AssertionError("Une IdentifiantsInvalidesException était attendue");
    }

    @Test
    @DisplayName("CA1 - le pseudo est comparé sans espaces de bord ni casse")
    void doit_retourner_le_compte_quand_le_pseudo_est_saisi_en_majuscules_avec_des_espaces() {
        Compte alice = alice();

        Compte connecte = connecter.executer("  ALICE ", MOT_DE_PASSE);

        assertThat(connecte).isEqualTo(alice);
        assertThat(connecte.pseudo().valeur()).isEqualTo("Alice");
    }

    @Test
    @DisplayName("CA2 - un mot de passe faux est refusé")
    void doit_refuser_un_mot_de_passe_faux() {
        alice();

        assertThatThrownBy(() -> connecter.executer("Alice", "un-mot-de-passe-13"))
                .isInstanceOf(IdentifiantsInvalidesException.class);
    }

    @Test
    @DisplayName("CA2 - un mot de passe avec un espace final est refusé (aucun trim)")
    void doit_refuser_un_mot_de_passe_avec_espace_final() {
        alice();

        assertThatThrownBy(() -> connecter.executer("Alice", MOT_DE_PASSE + " "))
                .isInstanceOf(IdentifiantsInvalidesException.class);
    }

    @Test
    @DisplayName("CA2 - la casse du mot de passe compte")
    void doit_refuser_un_mot_de_passe_de_casse_differente() {
        alice();

        assertThatThrownBy(() -> connecter.executer("Alice", MOT_DE_PASSE.toUpperCase()))
                .isInstanceOf(IdentifiantsInvalidesException.class);
    }

    @Test
    @DisplayName("CA2 / CA3 / CA4 - mot de passe faux, pseudo inconnu et compte sans empreinte : même message")
    void doit_utiliser_le_meme_message_pour_tous_les_echecs() {
        alice();
        compteSansEmpreinte("Anonyme1");

        String motDePasseFaux = messageDEchec("Alice", "un-mot-de-passe-13");
        String espaceFinal = messageDEchec("Alice", MOT_DE_PASSE + " ");
        String pseudoInconnu = messageDEchec("Inconnu", MOT_DE_PASSE);
        String sansEmpreinte = messageDEchec("Anonyme1", MOT_DE_PASSE);

        assertThat(motDePasseFaux).isNotBlank();
        assertThat(List.of(espaceFinal, pseudoInconnu, sansEmpreinte)).containsOnly(motDePasseFaux);
    }

    @Test
    @DisplayName("CA2 - un mot de passe faux provoque une vérification contre l'empreinte du compte")
    void doit_verifier_le_mot_de_passe_contre_l_empreinte_du_compte() {
        Compte alice = alice();

        assertThatThrownBy(() -> connecter.executer("Alice", "un-mot-de-passe-13"))
                .isInstanceOf(IdentifiantsInvalidesException.class);

        assertThat(encodeur.empreintesVerifiees).containsExactly(alice.empreinteMotDePasse());
    }

    @Test
    @DisplayName("CA3 - un pseudo inconnu provoque exactement une vérification, contre une empreinte factice")
    void doit_verifier_contre_une_empreinte_factice_quand_le_pseudo_est_inconnu() {
        Compte alice = alice();

        assertThatThrownBy(() -> connecter.executer("Inconnu", MOT_DE_PASSE))
                .isInstanceOf(IdentifiantsInvalidesException.class);

        assertThat(encodeur.empreintesVerifiees).hasSize(1);
        assertThat(encodeur.empreintesVerifiees.get(0))
                .isNotNull()
                .isNotEqualTo(alice.empreinteMotDePasse());
    }

    @Test
    @DisplayName("CA3 - l'empreinte factice ne dépend pas du mot de passe saisi ni du pseudo")
    void doit_refuser_un_pseudo_inconnu_meme_avec_le_mot_de_passe_utilise_pour_l_empreinte_factice() {
        assertThatThrownBy(() -> connecter.executer("Inconnu", MOT_DE_PASSE))
                .isInstanceOf(IdentifiantsInvalidesException.class);
        assertThatThrownBy(() -> connecter.executer("Autre", "autre-mot-de-passe-1"))
                .isInstanceOf(IdentifiantsInvalidesException.class);

        assertThat(encodeur.empreintesVerifiees).hasSize(2);
        assertThat(encodeur.empreintesVerifiees.get(1)).isEqualTo(encodeur.empreintesVerifiees.get(0));
    }

    @Test
    @DisplayName("CA4 - un compte sans empreinte est refusé avec une vérification contre l'empreinte factice")
    void doit_refuser_un_compte_sans_empreinte_apres_une_verification_factice() {
        compteSansEmpreinte("Anonyme1");

        assertThatThrownBy(() -> connecter.executer("Anonyme1", MOT_DE_PASSE))
                .isInstanceOf(IdentifiantsInvalidesException.class);

        assertThat(encodeur.empreintesVerifiees).hasSize(1);
        assertThat(encodeur.empreintesVerifiees.get(0)).isNotNull().startsWith(PREFIXE_EMPREINTE);
    }

    @Test
    @DisplayName("CA5 - un pseudo de 31 caractères est refusé sans aucun appel à l'encodeur")
    void doit_refuser_un_pseudo_de_31_caracteres_sans_calcul() {
        assertThatThrownBy(() -> connecter.executer("a".repeat(31), MOT_DE_PASSE))
                .isInstanceOf(IdentifiantsInvalidesException.class);

        assertThat(encodeur.empreintesVerifiees).isEmpty();
    }

    @Test
    @DisplayName("CA5 - le trim précède la borne : 30 caractères entourés d'espaces sont traités normalement")
    void doit_verifier_un_pseudo_de_30_caracteres_entoure_d_espaces() {
        assertThatThrownBy(() -> connecter.executer(" " + "a".repeat(30) + " ", MOT_DE_PASSE))
                .isInstanceOf(IdentifiantsInvalidesException.class);

        assertThat(encodeur.empreintesVerifiees).hasSize(1);
    }

    @Test
    @DisplayName("CA5 - un mot de passe de 129 caractères est refusé sans aucun appel à l'encodeur")
    void doit_refuser_un_mot_de_passe_de_129_caracteres_sans_calcul() {
        alice();

        assertThatThrownBy(() -> connecter.executer("Alice", "m".repeat(129)))
                .isInstanceOf(IdentifiantsInvalidesException.class);

        assertThat(encodeur.empreintesVerifiees).isEmpty();
    }

    @Test
    @DisplayName("CA5 - la borne de 128 se compte en points de code : 128 émojis (256 unités UTF-16) sont vérifiés")
    void doit_verifier_un_mot_de_passe_de_128_points_de_code_meme_s_il_depasse_128_unites_utf16() {
        alice();
        String motDePasse128PointsDeCode = "😀".repeat(128);

        assertThatThrownBy(() -> connecter.executer("Alice", motDePasse128PointsDeCode))
                .isInstanceOf(IdentifiantsInvalidesException.class);

        assertThat(encodeur.empreintesVerifiees).hasSize(1);
    }

    @Test
    @DisplayName("CA5 - un pseudo au format invalide et un mot de passe de 5 caractères : refus après une vérification")
    void doit_refuser_apres_verification_sans_violation_de_format() {
        assertThatThrownBy(() -> connecter.executer("a b", "court"))
                .isInstanceOf(IdentifiantsInvalidesException.class);

        assertThat(encodeur.empreintesVerifiees).hasSize(1);
    }

    @Test
    @DisplayName("CA5 - un mot de passe de 128 caractères est vérifié normalement")
    void doit_connecter_avec_un_mot_de_passe_de_128_caracteres() {
        String motDePasse128 = "m".repeat(128);
        Compte alice = Compte.creerCoureur(
                new Pseudo("Alice"), encodeur.encoder(new MotDePasse(motDePasse128)), horlogeFixe.instant());
        depot.comptes.add(alice);

        Compte connecte = connecter.executer("Alice", motDePasse128);

        assertThat(connecte).isEqualTo(alice);
        assertThat(encodeur.empreintesVerifiees).hasSize(1);
    }

    @Test
    @DisplayName("CA6 - pseudo null et mot de passe null : deux violations requises ensemble, dépôt non interrogé")
    void doit_signaler_les_deux_champs_requis_quand_ils_sont_null() {
        DepotComptes depotEspion = mock(DepotComptes.class);
        Connecter casUsage = new Connecter(depotEspion, encodeur);

        assertThatThrownBy(() -> casUsage.executer(null, null))
                .isInstanceOfSatisfying(DonneesCompteInvalidesException.class, e ->
                        assertThat(e.violations()).extracting(v -> v.code())
                                .containsExactlyInAnyOrder("PSEUDO_REQUIS", "MOT_DE_PASSE_REQUIS"));
        verifyNoInteractions(depotEspion);
        assertThat(encodeur.empreintesVerifiees).isEmpty();
    }

    @Test
    @DisplayName("CA6 - pseudo d'espaces et mot de passe vide : deux violations requises ensemble")
    void doit_signaler_les_deux_champs_requis_quand_pseudo_blanc_et_mot_de_passe_vide() {
        DepotComptes depotEspion = mock(DepotComptes.class);
        Connecter casUsage = new Connecter(depotEspion, encodeur);

        assertThatThrownBy(() -> casUsage.executer("   ", ""))
                .isInstanceOfSatisfying(DonneesCompteInvalidesException.class, e -> {
                    assertThat(e.violations()).extracting(v -> v.code())
                            .containsExactlyInAnyOrder("PSEUDO_REQUIS", "MOT_DE_PASSE_REQUIS");
                    assertThat(e.violations()).extracting(v -> v.champ())
                            .containsExactlyInAnyOrder("pseudo", "motDePasse");
                });
        verifyNoInteractions(depotEspion);
    }

    @Test
    @DisplayName("CA6 - pseudo absent seul : une seule violation PSEUDO_REQUIS")
    void doit_signaler_seulement_le_pseudo_requis() {
        DepotComptes depotEspion = mock(DepotComptes.class);
        Connecter casUsage = new Connecter(depotEspion, encodeur);

        assertThatThrownBy(() -> casUsage.executer("   ", "x"))
                .isInstanceOfSatisfying(DonneesCompteInvalidesException.class, e ->
                        assertThat(e.violations()).extracting(v -> v.code()).containsExactly("PSEUDO_REQUIS"));
        verifyNoInteractions(depotEspion);
    }

    @Test
    @DisplayName("CA6 - mot de passe absent seul : une seule violation MOT_DE_PASSE_REQUIS")
    void doit_signaler_seulement_le_mot_de_passe_requis() {
        DepotComptes depotEspion = mock(DepotComptes.class);
        Connecter casUsage = new Connecter(depotEspion, encodeur);

        assertThatThrownBy(() -> casUsage.executer("Alice", null))
                .isInstanceOfSatisfying(DonneesCompteInvalidesException.class, e ->
                        assertThat(e.violations()).extracting(v -> v.code())
                                .containsExactly("MOT_DE_PASSE_REQUIS"));
        verifyNoInteractions(depotEspion);
    }

    @Test
    @DisplayName("CA6 - un mot de passe fait d'espaces est une valeur, pas une violation : échec d'identifiants")
    void doit_traiter_un_mot_de_passe_d_espaces_comme_un_mot_de_passe_faux() {
        alice();

        assertThatThrownBy(() -> connecter.executer("Alice", "            "))
                .isInstanceOf(IdentifiantsInvalidesException.class);

        assertThat(encodeur.empreintesVerifiees).hasSize(1);
    }

    @Test
    @DisplayName("CA7 - les messages d'échec ne contiennent jamais le mot de passe saisi")
    void doit_ne_jamais_exposer_le_mot_de_passe_dans_les_exceptions() {
        alice();
        String secret = "secret-de-test-123";

        assertThatThrownBy(() -> connecter.executer("Alice", secret))
                .isInstanceOfSatisfying(IdentifiantsInvalidesException.class, e ->
                        assertThat(e.toString()).doesNotContain(secret));
        assertThatThrownBy(() -> connecter.executer("Inconnu", secret))
                .isInstanceOfSatisfying(IdentifiantsInvalidesException.class, e ->
                        assertThat(e.toString()).doesNotContain(secret));
        assertThatThrownBy(() -> connecter.executer(null, secret))
                .isInstanceOfSatisfying(DonneesCompteInvalidesException.class, e ->
                        assertThat(e.toString()).doesNotContain(secret));
    }

    @Test
    @DisplayName("CA7 - le message d'échec ne contient pas le pseudo saisi")
    void doit_ne_jamais_exposer_le_pseudo_dans_le_message_d_echec() {
        assertThat(messageDEchec("Inconnu", MOT_DE_PASSE)).doesNotContain("Inconnu");
    }

    @Test
    @DisplayName("CA8 - le cas d'usage Connecter est dans comptes.application")
    void doit_placer_connecter_dans_la_couche_application_des_comptes() {
        assertThat(Connecter.class.getPackageName()).isEqualTo("fr.backyard.tracker.comptes.application");
    }

    @Test
    @DisplayName("CA9 - le compte retourné expose id, pseudo, rôle et date de création d'origine")
    void doit_retourner_le_compte_d_origine() {
        Compte alice = alice();

        Compte connecte = connecter.executer("Alice", MOT_DE_PASSE);

        assertThat(connecte.id()).isEqualTo(alice.id());
        assertThat(connecte.pseudo().valeur()).isEqualTo("Alice");
        assertThat(connecte.role()).isEqualTo(Role.COUREUR);
        assertThat(connecte.creeLe()).isEqualTo(MAINTENANT);
    }

    @Test
    @DisplayName("CA9 - une connexion réussie n'enregistre rien dans le dépôt")
    void doit_ne_rien_enregistrer_lors_d_une_connexion_reussie() {
        alice();

        connecter.executer("Alice", MOT_DE_PASSE);

        assertThat(depot.nombreEnregistrements).isZero();
        assertThat(depot.comptes).hasSize(1);
    }

    /** Dépôt en mémoire : recherche sur le pseudo normalisé. */
    private static final class DepotComptesEnMemoire implements DepotComptes {
        final List<Compte> comptes = new ArrayList<>();
        int nombreEnregistrements;

        @Override
        public boolean existeParPseudoNormalise(String pseudoNormalise) {
            return trouverParPseudoNormalise(pseudoNormalise).isPresent();
        }

        @Override
        public Optional<Compte> trouverParPseudoNormalise(String pseudoNormalise) {
            return comptes.stream().filter(c -> c.pseudoNormalise().equals(pseudoNormalise)).findFirst();
        }

        @Override
        public void enregistrer(Compte compte) {
            nombreEnregistrements++;
            comptes.add(compte);
        }
    }

    /** Encodeur factice déterministe qui enregistre les empreintes contre lesquelles on vérifie. */
    private static final class EncodeurEspion implements EncodeurMotDePasse {
        final List<String> empreintesVerifiees = new ArrayList<>();

        @Override
        public String encoder(MotDePasse motDePasse) {
            return PREFIXE_EMPREINTE + motDePasse.valeur();
        }

        @Override
        public boolean verifier(String motDePasseEnClair, String empreinte) {
            empreintesVerifiees.add(empreinte);
            return empreinte != null && empreinte.equals(PREFIXE_EMPREINTE + motDePasseEnClair);
        }
    }
}
