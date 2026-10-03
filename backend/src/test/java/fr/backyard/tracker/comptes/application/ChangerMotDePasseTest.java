package fr.backyard.tracker.comptes.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import fr.backyard.tracker.comptes.application.ChangerMotDePasse.Commande;
import fr.backyard.tracker.comptes.domaine.Compte;
import fr.backyard.tracker.comptes.domaine.CompteIntrouvableOuInutilisableException;
import fr.backyard.tracker.comptes.domaine.ConnexionBloqueeException;
import fr.backyard.tracker.comptes.domaine.DepotComptes;
import fr.backyard.tracker.comptes.domaine.DonneesCompteInvalidesException;
import fr.backyard.tracker.comptes.domaine.EncodeurMotDePasse;
import fr.backyard.tracker.comptes.domaine.IdentifiantsInvalidesException;
import fr.backyard.tracker.comptes.domaine.InvalidationAutresSessions;
import fr.backyard.tracker.comptes.domaine.MotDePasse;
import fr.backyard.tracker.comptes.domaine.MotDePasseActuelIncorrectException;
import fr.backyard.tracker.comptes.domaine.NouveauMotDePasseIdentiqueException;
import fr.backyard.tracker.comptes.domaine.PolitiqueBlocage;
import fr.backyard.tracker.comptes.domaine.Pseudo;
import fr.backyard.tracker.comptes.domaine.RegistreTentativesConnexion;
import fr.backyard.tracker.comptes.domaine.Role;
import fr.backyard.tracker.comptes.domaine.TentativesConnexion;
import fr.backyard.tracker.comptes.domaine.ViolationValidation;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/** Changement de son propre mot de passe (incrément 1.6b, CA1 à CA7). */
class ChangerMotDePasseTest {

    private static final Instant T = Instant.parse("2026-10-02T10:00:00Z");
    private static final String ANCIEN = "un-mot-de-passe-12";
    private static final String NOUVEAU = "nouveau-mot-de-passe-1";
    private static final String SECRET = "secret-de-test-123";
    private static final String COURT = "court-secre";
    private static final String PREFIXE_EMPREINTE = "empreinte:";
    private static final String CLE_ALICE = "alice";

    private final HorlogeMutable horloge = new HorlogeMutable(T);
    private final DepotComptesEnMemoire depot = new DepotComptesEnMemoire();
    private final EncodeurEspion encodeur = new EncodeurEspion();
    private final RegistreEnMemoire registre = new RegistreEnMemoire();
    private final InvalidationEspion invalidation = new InvalidationEspion();
    private final ChangerMotDePasse changer = new ChangerMotDePasse(
            depot, encodeur, registre, PolitiqueBlocage.parDefaut(), invalidation, horloge);

    // --- fabriques de test --------------------------------------------------------------------------------

    private static String empreinteDe(String motDePasse) {
        return PREFIXE_EMPREINTE + motDePasse;
    }

    private Compte alice(Role role) {
        Compte alice = Compte.reconstituer(
                UUID.randomUUID(), new Pseudo("Alice"), empreinteDe(ANCIEN), role, T.minusSeconds(3600));
        depot.comptes.put(alice.id(), alice);
        encodeur.reinitialiser();
        return alice;
    }

    private Compte alice() {
        return alice(Role.COUREUR);
    }

    private Compte compteSansEmpreinte() {
        Compte anonyme = Compte.reconstituer(
                UUID.randomUUID(), new Pseudo("Alice"), null, Role.COUREUR, T.minusSeconds(3600));
        depot.comptes.put(anonyme.id(), anonyme);
        encodeur.reinitialiser();
        return anonyme;
    }

    private void echecsDejaEnregistres(int nombre) {
        for (int i = 0; i < nombre; i++) {
            registre.enregistrerEchec(CLE_ALICE, horloge.instant(), PolitiqueBlocage.parDefaut());
        }
    }

    private int echecs() {
        return registre.constater(CLE_ALICE, horloge.instant()).echecs();
    }

    private String empreinteEnDepot(Compte compte) {
        return depot.comptes.get(compte.id()).empreinteMotDePasse();
    }

    private void changerPour(Compte compte, String actuel, String nouveau) {
        changer.executer(new Commande(compte.id(), actuel, nouveau));
    }

    private Throwable echecDe(Runnable action) {
        try {
            action.run();
        } catch (RuntimeException e) {
            return e;
        }
        throw new AssertionError("Une exception métier était attendue");
    }

    private List<String> codesDeViolation(Compte compte, String actuel, String nouveau) {
        Throwable echec = echecDe(() -> changerPour(compte, actuel, nouveau));
        assertThat(echec).isInstanceOf(DonneesCompteInvalidesException.class);
        return ((DonneesCompteInvalidesException) echec).violations().stream()
                .map(ViolationValidation::code).toList();
    }

    // --- CA1 : succès -------------------------------------------------------------------------------------

    @ParameterizedTest(name = "{0}")
    @EnumSource(Role.class)
    @DisplayName("CA1 - le changement remplace l'empreinte et laisse id, pseudo, rôle et date de création inchangés")
    void doit_remplacer_l_empreinte_et_conserver_l_identite_du_compte_pour_chaque_role(Role role) {
        Compte avant = alice(role);

        changerPour(avant, ANCIEN, NOUVEAU);

        Compte apres = depot.comptes.get(avant.id());
        assertThat(apres.empreinteMotDePasse()).isEqualTo(encodeur.encoder(new MotDePasse(NOUVEAU)));
        assertThat(apres.empreinteMotDePasse()).isNotEqualTo(avant.empreinteMotDePasse());
        assertThat(apres.id()).isEqualTo(avant.id());
        assertThat(apres.pseudo()).isEqualTo(avant.pseudo());
        assertThat(apres.role()).isEqualTo(role);
        assertThat(apres.creeLe()).isEqualTo(avant.creeLe());
        assertThat(apres.peutSeConnecter()).isTrue();
    }

    @Test
    @DisplayName("CA1 - le compteur d'échecs du pseudo est remis à zéro après un changement réussi")
    void doit_remettre_le_compteur_d_echecs_a_zero_apres_un_changement_reussi() {
        Compte alice = alice();
        echecsDejaEnregistres(4);

        changerPour(alice, ANCIEN, NOUVEAU);

        assertThat(echecs()).isZero();
    }

    @Test
    @DisplayName("CA1 - les autres sessions sont invalidées, pour l'identifiant du compte, après le changement")
    void doit_demander_l_invalidation_des_autres_sessions_du_compte() {
        Compte alice = alice();

        changerPour(alice, ANCIEN, NOUVEAU);

        assertThat(invalidation.comptesInvalides).containsExactly(alice.id());
    }

    @Test
    @DisplayName("CA1 - le nouveau mot de passe est une valeur exacte : l'espace de fin est conservé, sans trim")
    void doit_conserver_les_espaces_du_nouveau_mot_de_passe() {
        Compte alice = alice();

        changerPour(alice, ANCIEN, NOUVEAU + " ");

        assertThat(empreinteEnDepot(alice)).isEqualTo(empreinteDe(NOUVEAU + " "));
        assertThat(empreinteEnDepot(alice)).isNotEqualTo(empreinteDe(NOUVEAU));
    }

    @Test
    @DisplayName("CA1 - l'ancien mot de passe est comparé exactement : une casse différente est un échec")
    void doit_refuser_un_ancien_mot_de_passe_dont_la_casse_differe() {
        Compte alice = alice();

        assertThatThrownBy(() -> changerPour(alice, ANCIEN.toUpperCase(), NOUVEAU))
                .isInstanceOf(MotDePasseActuelIncorrectException.class);
    }

    // --- CA2 : ancien mot de passe faux --------------------------------------------------------------------

    @Test
    @DisplayName("CA2 - un ancien mot de passe faux lève « incorrect », laisse l'empreinte et compte un échec")
    void doit_lever_mot_de_passe_actuel_incorrect_et_compter_un_echec_quand_l_ancien_est_faux() {
        Compte alice = alice();

        assertThatThrownBy(() -> changerPour(alice, SECRET, NOUVEAU))
                .isInstanceOf(MotDePasseActuelIncorrectException.class);

        assertThat(empreinteEnDepot(alice)).isEqualTo(empreinteDe(ANCIEN));
        assertThat(echecs()).isEqualTo(1);
        assertThat(invalidation.comptesInvalides).isEmpty();
    }

    @Test
    @DisplayName("CA2 - un ancien mot de passe de 129 caractères lève la même exception sans appeler l'encodeur ni compter")
    void doit_refuser_sans_calcul_ni_comptage_un_ancien_mot_de_passe_de_129_caracteres() {
        Compte alice = alice();

        assertThatThrownBy(() -> changerPour(alice, "a".repeat(129), NOUVEAU))
                .isInstanceOf(MotDePasseActuelIncorrectException.class);

        assertThat(encodeur.verifications).isZero();
        assertThat(encodeur.encodages).isZero();
        assertThat(echecs()).isZero();
        assertThat(empreinteEnDepot(alice)).isEqualTo(empreinteDe(ANCIEN));
    }

    @Test
    @DisplayName("CA2 - un ancien mot de passe de 128 caractères est vérifié (borne incluse) et compté s'il est faux")
    void doit_verifier_et_compter_un_ancien_mot_de_passe_de_128_caracteres() {
        Compte alice = alice();

        assertThatThrownBy(() -> changerPour(alice, "a".repeat(128), NOUVEAU))
                .isInstanceOf(MotDePasseActuelIncorrectException.class);

        assertThat(encodeur.verifications).isEqualTo(1);
        assertThat(echecs()).isEqualTo(1);
    }

    @Test
    @DisplayName("CA2 - un ancien mot de passe d'espaces est une valeur : vérifié et compté, pas une violation")
    void doit_traiter_un_ancien_mot_de_passe_d_espaces_comme_une_valeur_fausse() {
        Compte alice = alice();

        assertThatThrownBy(() -> changerPour(alice, " ".repeat(12), NOUVEAU))
                .isInstanceOf(MotDePasseActuelIncorrectException.class);

        assertThat(echecs()).isEqualTo(1);
    }

    @Test
    @DisplayName("CA2 - le nouveau mot de passe n'est pas haché tant que l'ancien n'est pas validé")
    void doit_ne_pas_hacher_le_nouveau_mot_de_passe_quand_l_ancien_est_faux() {
        Compte alice = alice();

        assertThatThrownBy(() -> changerPour(alice, SECRET, NOUVEAU))
                .isInstanceOf(MotDePasseActuelIncorrectException.class);

        assertThat(encodeur.encodages).isZero();
        assertThat(depot.misesAJour).isZero();
    }

    // --- CA3 : validation de format ------------------------------------------------------------------------

    @Test
    @DisplayName("CA3 - un nouveau mot de passe de 11 caractères porte MOT_DE_PASSE_TROP_COURT")
    void doit_signaler_un_nouveau_mot_de_passe_de_11_caracteres_comme_trop_court() {
        Compte alice = alice();

        assertThat(codesDeViolation(alice, ANCIEN, COURT)).containsExactly("MOT_DE_PASSE_TROP_COURT");
    }

    @Test
    @DisplayName("CA3 - un nouveau mot de passe de 129 caractères porte MOT_DE_PASSE_TROP_LONG")
    void doit_signaler_un_nouveau_mot_de_passe_de_129_caracteres_comme_trop_long() {
        Compte alice = alice();

        assertThat(codesDeViolation(alice, ANCIEN, "a".repeat(129))).containsExactly("MOT_DE_PASSE_TROP_LONG");
    }

    @Test
    @DisplayName("CA3 - un nouveau mot de passe vide ou absent porte MOT_DE_PASSE_REQUIS")
    void doit_signaler_un_nouveau_mot_de_passe_vide_ou_absent_comme_requis() {
        Compte alice = alice();

        assertThat(codesDeViolation(alice, ANCIEN, "")).containsExactly("MOT_DE_PASSE_REQUIS");
        assertThat(codesDeViolation(alice, ANCIEN, null)).containsExactly("MOT_DE_PASSE_REQUIS");
    }

    @Test
    @DisplayName("CA3 - un ancien mot de passe absent ou vide porte MOT_DE_PASSE_ACTUEL_REQUIS")
    void doit_signaler_un_ancien_mot_de_passe_absent_ou_vide_comme_requis() {
        Compte alice = alice();

        assertThat(codesDeViolation(alice, null, NOUVEAU)).containsExactly("MOT_DE_PASSE_ACTUEL_REQUIS");
        assertThat(codesDeViolation(alice, "", NOUVEAU)).containsExactly("MOT_DE_PASSE_ACTUEL_REQUIS");
    }

    @Test
    @DisplayName("CA3 - un ancien absent et un nouveau trop court donnent deux violations ensemble")
    void doit_cumuler_les_violations_des_deux_champs() {
        Compte alice = alice();

        assertThat(codesDeViolation(alice, null, COURT))
                .containsExactlyInAnyOrder("MOT_DE_PASSE_ACTUEL_REQUIS", "MOT_DE_PASSE_TROP_COURT");
        assertThat(codesDeViolation(alice, null, null))
                .containsExactlyInAnyOrder("MOT_DE_PASSE_ACTUEL_REQUIS", "MOT_DE_PASSE_REQUIS");
    }

    @Test
    @DisplayName("CA3 - la violation de l'ancien mot de passe est portée par le champ motDePasseActuel")
    void doit_attribuer_la_violation_de_l_ancien_au_champ_mot_de_passe_actuel() {
        Compte alice = alice();

        Throwable echec = echecDe(() -> changerPour(alice, null, NOUVEAU));

        assertThat(((DonneesCompteInvalidesException) echec).violations())
                .extracting(ViolationValidation::champ).containsExactly("motDePasseActuel");
    }

    @Test
    @DisplayName("CA3 - avec une violation : ni encodeur, ni comptage, ni modification, ni invalidation")
    void doit_ne_rien_faire_d_autre_quand_la_validation_echoue() {
        Compte alice = alice();
        echecsDejaEnregistres(2);

        assertThatThrownBy(() -> changerPour(alice, SECRET, COURT))
                .isInstanceOf(DonneesCompteInvalidesException.class);

        assertThat(encodeur.verifications).isZero();
        assertThat(encodeur.encodages).isZero();
        assertThat(echecs()).isEqualTo(2);
        assertThat(empreinteEnDepot(alice)).isEqualTo(empreinteDe(ANCIEN));
        assertThat(invalidation.comptesInvalides).isEmpty();
    }

    @Test
    @DisplayName("CA3 - la validation de format précède la vérification de l'ancien : ancien faux et nouveau court = violation")
    void doit_valider_le_format_avant_de_verifier_l_ancien() {
        Compte alice = alice();

        assertThatThrownBy(() -> changerPour(alice, SECRET, COURT))
                .isInstanceOf(DonneesCompteInvalidesException.class);
    }

    @Test
    @DisplayName("CA3 - des nouveaux mots de passe de 12 et de 128 caractères, ou de 12 espaces, sont acceptés")
    void doit_accepter_un_nouveau_mot_de_passe_de_12_ou_128_caracteres_ou_12_espaces() {
        Compte alice = alice();
        String douze = "b".repeat(12);
        String centVingtHuit = "c".repeat(128);
        String douzeEspaces = " ".repeat(12);

        assertThatCode(() -> {
            changerPour(alice, ANCIEN, douze);
            changerPour(alice, douze, centVingtHuit);
            changerPour(alice, centVingtHuit, douzeEspaces);
        }).doesNotThrowAnyException();

        assertThat(empreinteEnDepot(alice)).isEqualTo(empreinteDe(douzeEspaces));
    }

    // --- CA4 : nouveau identique ---------------------------------------------------------------------------

    @Test
    @DisplayName("CA4 - un nouveau mot de passe identique à l'ancien lève « identique » sans effet ni comptage")
    void doit_refuser_un_nouveau_mot_de_passe_identique_sans_effet_ni_comptage() {
        Compte alice = alice();
        echecsDejaEnregistres(2);

        assertThatThrownBy(() -> changerPour(alice, ANCIEN, ANCIEN))
                .isInstanceOf(NouveauMotDePasseIdentiqueException.class);

        assertThat(empreinteEnDepot(alice)).isEqualTo(empreinteDe(ANCIEN));
        assertThat(echecs()).isEqualTo(2);
        assertThat(invalidation.comptesInvalides).isEmpty();
        assertThat(depot.misesAJour).isZero();
    }

    @Test
    @DisplayName("CA4 - un ancien faux égal au nouveau lève « incorrect » : la vérification précède")
    void doit_lever_incorrect_quand_l_ancien_est_faux_meme_s_il_est_egal_au_nouveau() {
        Compte alice = alice();

        assertThatThrownBy(() -> changerPour(alice, SECRET, SECRET))
                .isInstanceOf(MotDePasseActuelIncorrectException.class);

        assertThat(echecs()).isEqualTo(1);
    }

    @Test
    @DisplayName("CA4 - un nouveau mot de passe qui ne diffère que par un espace de fin n'est pas identique")
    void doit_accepter_un_nouveau_mot_de_passe_qui_ne_differe_que_par_un_espace_de_fin() {
        Compte alice = alice();

        assertThatCode(() -> changerPour(alice, ANCIEN, ANCIEN + " ")).doesNotThrowAnyException();
    }

    // --- CA5 : blocage commun ------------------------------------------------------------------------------

    @Test
    @DisplayName("CA5 - les 5 premiers anciens faux lèvent « incorrect » et le 5e fixe bloqueJusqu à T + 900 s")
    void doit_declencher_le_blocage_au_cinquieme_echec_en_levant_encore_incorrect() {
        Compte alice = alice();

        for (int i = 1; i <= 5; i++) {
            assertThatThrownBy(() -> changerPour(alice, SECRET, NOUVEAU))
                    .isInstanceOf(MotDePasseActuelIncorrectException.class);
        }

        assertThat(registre.constater(CLE_ALICE, T).bloqueJusqu()).contains(T.plusSeconds(900));
    }

    @Test
    @DisplayName("CA5 - le 4e échec ne bloque pas encore")
    void doit_ne_pas_bloquer_au_quatrieme_echec() {
        Compte alice = alice();

        for (int i = 1; i <= 4; i++) {
            assertThatThrownBy(() -> changerPour(alice, SECRET, NOUVEAU))
                    .isInstanceOf(MotDePasseActuelIncorrectException.class);
        }

        assertThat(registre.constater(CLE_ALICE, T).estBloquee(T)).isFalse();
    }

    @Test
    @DisplayName("CA5 - le 6e appel, même avec le bon ancien, est bloqué (900 s) sans encodeur ni écriture")
    void doit_bloquer_le_sixieme_appel_meme_avec_le_bon_mot_de_passe_sans_calcul_ni_ecriture() {
        Compte alice = alice();
        echecsDejaEnregistres(5);

        assertThatThrownBy(() -> changerPour(alice, ANCIEN, NOUVEAU))
                .isInstanceOfSatisfying(ConnexionBloqueeException.class,
                        e -> assertThat(e.tempsRestant()).isEqualTo(Duration.ofSeconds(900)));

        assertThat(encodeur.verifications).isZero();
        assertThat(encodeur.encodages).isZero();
        assertThat(depot.misesAJour).isZero();
        assertThat(empreinteEnDepot(alice)).isEqualTo(empreinteDe(ANCIEN));
        assertThat(invalidation.comptesInvalides).isEmpty();
    }

    @Test
    @DisplayName("CA5 - à 0,5 s de la fin du blocage, le temps restant est arrondi à 1 s")
    void doit_arrondir_a_une_seconde_le_temps_restant_a_une_demi_seconde_de_la_fin() {
        Compte alice = alice();
        echecsDejaEnregistres(5);
        horloge.avancerA(T.plusSeconds(900).minusMillis(500));

        assertThatThrownBy(() -> changerPour(alice, ANCIEN, NOUVEAU))
                .isInstanceOfSatisfying(ConnexionBloqueeException.class,
                        e -> assertThat(e.tempsRestant()).isEqualTo(Duration.ofSeconds(1)));
    }

    @Test
    @DisplayName("CA5 - à bloqueJusqu exact, le changement s'exécute normalement")
    void doit_executer_le_changement_a_l_instant_exact_de_fin_de_blocage() {
        Compte alice = alice();
        echecsDejaEnregistres(5);
        horloge.avancerA(T.plusSeconds(900));

        changerPour(alice, ANCIEN, NOUVEAU);

        assertThat(empreinteEnDepot(alice)).isEqualTo(empreinteDe(NOUVEAU));
    }

    @Test
    @DisplayName("CA5 - 3 échecs de connexion (ALICE) puis 2 échecs de changement bloquent les deux opérations")
    void doit_additionner_les_echecs_de_connexion_et_de_changement_dans_le_meme_registre() {
        Compte alice = alice();
        Connecter connecter = new Connecter(depot, encodeur, registre, PolitiqueBlocage.parDefaut(), horloge);
        for (int i = 0; i < 3; i++) {
            assertThatThrownBy(() -> connecter.executer("ALICE", SECRET))
                    .isInstanceOf(IdentifiantsInvalidesException.class);
        }
        for (int i = 0; i < 2; i++) {
            assertThatThrownBy(() -> changerPour(alice, SECRET, NOUVEAU))
                    .isInstanceOf(MotDePasseActuelIncorrectException.class);
        }

        assertThatThrownBy(() -> changerPour(alice, ANCIEN, NOUVEAU))
                .isInstanceOf(ConnexionBloqueeException.class);
        assertThatThrownBy(() -> connecter.executer("alice", ANCIEN))
                .isInstanceOf(ConnexionBloqueeException.class);
    }

    @Test
    @DisplayName("CA5 - 2 échecs de changement puis 3 échecs de connexion bloquent aussi (ordre inverse)")
    void doit_bloquer_aussi_quand_les_echecs_de_changement_precedent_ceux_de_connexion() {
        Compte alice = alice();
        Connecter connecter = new Connecter(depot, encodeur, registre, PolitiqueBlocage.parDefaut(), horloge);
        for (int i = 0; i < 2; i++) {
            assertThatThrownBy(() -> changerPour(alice, SECRET, NOUVEAU))
                    .isInstanceOf(MotDePasseActuelIncorrectException.class);
        }
        for (int i = 0; i < 3; i++) {
            assertThatThrownBy(() -> connecter.executer("alice", SECRET))
                    .isInstanceOf(IdentifiantsInvalidesException.class);
        }

        assertThatThrownBy(() -> changerPour(alice, ANCIEN, NOUVEAU))
                .isInstanceOf(ConnexionBloqueeException.class);
    }

    @Test
    @DisplayName("CA5 - les échecs partiels sont oubliés après la fenêtre d'oubli : le prochain échec vaut 1")
    void doit_oublier_les_echecs_partiels_apres_la_fenetre_d_oubli() {
        Compte alice = alice();
        echecsDejaEnregistres(4);
        horloge.avancerA(T.plusSeconds(900));

        assertThatThrownBy(() -> changerPour(alice, SECRET, NOUVEAU))
                .isInstanceOf(MotDePasseActuelIncorrectException.class);

        assertThat(echecs()).isEqualTo(1);
    }

    @Test
    @DisplayName("CA5 - un autre compte n'est pas bloqué par le blocage d'Alice")
    void doit_ne_pas_bloquer_un_autre_compte() {
        alice();
        echecsDejaEnregistres(5);
        Compte leo = Compte.reconstituer(
                UUID.randomUUID(), new Pseudo("Léo"), empreinteDe(ANCIEN), Role.BENEVOLE, T.minusSeconds(60));
        depot.comptes.put(leo.id(), leo);

        changerPour(leo, ANCIEN, NOUVEAU);

        assertThat(empreinteEnDepot(leo)).isEqualTo(empreinteDe(NOUVEAU));
    }

    // --- CA6 : compte introuvable ou inutilisable ----------------------------------------------------------

    @Test
    @DisplayName("CA6 - un compte supprimé du dépôt lève « introuvable ou inutilisable », l'encodeur est quand même sollicité")
    void doit_lever_compte_introuvable_pour_un_compte_supprime_en_sollicitant_l_encodeur() {
        Compte supprime = alice();
        depot.comptes.remove(supprime.id());

        assertThatThrownBy(() -> changerPour(supprime, ANCIEN, NOUVEAU))
                .isInstanceOf(CompteIntrouvableOuInutilisableException.class);

        assertThat(encodeur.verifications).isEqualTo(1);
        assertThat(depot.misesAJour).isZero();
        assertThat(invalidation.comptesInvalides).isEmpty();
    }

    @Test
    @DisplayName("CA6 - un compte à empreinte nulle lève « introuvable ou inutilisable » sans rien modifier")
    void doit_lever_compte_inutilisable_pour_un_compte_sans_empreinte() {
        Compte anonyme = compteSansEmpreinte();

        assertThatThrownBy(() -> changerPour(anonyme, ANCIEN, NOUVEAU))
                .isInstanceOf(CompteIntrouvableOuInutilisableException.class);

        assertThat(encodeur.verifications).isEqualTo(1);
        assertThat(depot.comptes.get(anonyme.id()).empreinteMotDePasse()).isNull();
        assertThat(depot.misesAJour).isZero();
        assertThat(invalidation.comptesInvalides).isEmpty();
    }

    @Test
    @DisplayName("CA6 - un identifiant inconnu lève « introuvable ou inutilisable »")
    void doit_lever_compte_introuvable_pour_un_identifiant_inconnu() {
        assertThatThrownBy(() -> changer.executer(new Commande(UUID.randomUUID(), ANCIEN, NOUVEAU)))
                .isInstanceOf(CompteIntrouvableOuInutilisableException.class);
    }

    // --- CA7 : confidentialité -----------------------------------------------------------------------------

    @Test
    @DisplayName("CA7 - aucune exception levée ne contient un mot de passe saisi, ni dans son message ni dans son toString")
    void doit_ne_jamais_exposer_un_mot_de_passe_dans_les_exceptions() {
        Compte alice = alice();
        Compte supprime = Compte.reconstituer(
                UUID.randomUUID(), new Pseudo("Zoe"), empreinteDe(ANCIEN), Role.COUREUR, T);
        Map<String, Throwable> echecs = new LinkedHashMap<>();
        echecs.put("incorrect", echecDe(() -> changerPour(alice, SECRET, NOUVEAU)));
        echecs.put("129 caractères", echecDe(() -> changerPour(alice, "a".repeat(129), NOUVEAU)));
        echecs.put("trop court", echecDe(() -> changerPour(alice, SECRET, COURT)));
        echecs.put("actuel absent", echecDe(() -> changerPour(alice, null, COURT)));
        echecs.put("identique", echecDe(() -> changerPour(alice, ANCIEN, ANCIEN)));
        echecs.put("introuvable", echecDe(() -> changerPour(supprime, SECRET, NOUVEAU)));
        echecsDejaEnregistres(5);
        echecs.put("bloquée", echecDe(() -> changerPour(alice, SECRET, NOUVEAU)));

        echecs.forEach((cas, echec) -> assertThat(List.of(String.valueOf(echec.getMessage()), echec.toString()))
                .as(cas)
                .allSatisfy(texte -> assertThat(texte)
                        .doesNotContain(SECRET).doesNotContain(COURT).doesNotContain(NOUVEAU)
                        .doesNotContain(ANCIEN)));
    }

    @Test
    @DisplayName("CA7 - la commande masque les deux mots de passe dans son toString")
    void doit_masquer_les_mots_de_passe_dans_le_toString_de_la_commande() {
        String texte = new Commande(UUID.randomUUID(), SECRET, NOUVEAU).toString();

        assertThat(texte).contains("motDePasseActuel=masqué, nouveauMotDePasse=masqué");
        assertThat(texte).doesNotContain(SECRET).doesNotContain(NOUVEAU);
    }

    // --- doubles de test -----------------------------------------------------------------------------------

    private static final class DepotComptesEnMemoire implements DepotComptes {
        final Map<UUID, Compte> comptes = new HashMap<>();
        int misesAJour;

        @Override
        public boolean existeParPseudoNormalise(String pseudoNormalise) {
            return trouverParPseudoNormalise(pseudoNormalise).isPresent();
        }

        @Override
        public Optional<Compte> trouverParPseudoNormalise(String pseudoNormalise) {
            return comptes.values().stream().filter(c -> c.pseudoNormalise().equals(pseudoNormalise)).findFirst();
        }

        @Override
        public Optional<Compte> trouverParId(UUID id) {
            return Optional.ofNullable(comptes.get(id));
        }

        @Override
        public void enregistrer(Compte compte) {
            comptes.put(compte.id(), compte);
        }

        @Override
        public void mettreAJour(Compte compte) {
            misesAJour++;
            comptes.put(compte.id(), compte);
        }
    }

    private static final class EncodeurEspion implements EncodeurMotDePasse {
        int encodages;
        int verifications;

        void reinitialiser() {
            encodages = 0;
            verifications = 0;
        }

        @Override
        public String encoder(MotDePasse motDePasse) {
            encodages++;
            return PREFIXE_EMPREINTE + motDePasse.valeur();
        }

        @Override
        public boolean verifier(String motDePasseEnClair, String empreinte) {
            verifications++;
            return empreinte != null && empreinte.equals(PREFIXE_EMPREINTE + motDePasseEnClair);
        }
    }

    private static final class InvalidationEspion implements InvalidationAutresSessions {
        final List<UUID> comptesInvalides = new ArrayList<>();

        @Override
        public void invaliderAutresSessions(UUID compteId) {
            comptesInvalides.add(compteId);
        }
    }

    /** Registre en mémoire : mêmes règles que celui de 1.3, qui viennent de {@link TentativesConnexion}. */
    private static final class RegistreEnMemoire implements RegistreTentativesConnexion {
        private final Map<String, TentativesConnexion> entrees = new HashMap<>();

        @Override
        public TentativesConnexion constater(String cle, Instant maintenant) {
            return entrees.getOrDefault(cle, TentativesConnexion.aucune());
        }

        @Override
        public TentativesConnexion enregistrerEchec(String cle, Instant maintenant, PolitiqueBlocage politique) {
            return entrees.compute(cle, (k, actuelle) ->
                    (actuelle == null ? TentativesConnexion.aucune() : actuelle).apresEchec(maintenant, politique));
        }

        @Override
        public void effacer(String cle) {
            entrees.remove(cle);
        }
    }

    private static final class HorlogeMutable extends Clock {
        private Instant instant;

        HorlogeMutable(Instant instant) {
            this.instant = instant;
        }

        void avancerA(Instant nouvelInstant) {
            this.instant = nouvelInstant;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }
}
