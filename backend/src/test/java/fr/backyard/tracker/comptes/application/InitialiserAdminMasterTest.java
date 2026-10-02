package fr.backyard.tracker.comptes.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import fr.backyard.tracker.comptes.application.InitialiserAdminMaster.Resultat;
import fr.backyard.tracker.comptes.domaine.AdminMasterDejaPresentException;
import fr.backyard.tracker.comptes.domaine.Compte;
import fr.backyard.tracker.comptes.domaine.ConfigurationAdminMasterInvalideException;
import fr.backyard.tracker.comptes.domaine.DepotComptes;
import fr.backyard.tracker.comptes.domaine.EncodeurMotDePasse;
import fr.backyard.tracker.comptes.domaine.Pseudo;
import fr.backyard.tracker.comptes.domaine.Role;
import fr.backyard.tracker.comptes.domaine.ViolationValidation;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

/** Tests de l'incrément 1.4 : création idempotente de l'admin master au démarrage. */
class InitialiserAdminMasterTest {

    private static final Instant MAINTENANT = Instant.parse("2026-10-02T10:00:00Z");
    private static final String PSEUDO = "Patron";
    private static final String MOT_DE_PASSE = "mot-de-passe-patron-1";
    private static final String MOT_DE_PASSE_TEST = "secret-de-test-123";
    private static final String MOT_DE_PASSE_COURT = "court-secre";
    private static final String VARIABLE_PSEUDO = "ADMIN_MASTER_PSEUDO";
    private static final String VARIABLE_MOT_DE_PASSE = "ADMIN_MASTER_MOT_DE_PASSE";

    private final Clock horlogeFixe = Clock.fixed(MAINTENANT, ZoneOffset.UTC);
    private final DepotComptesEnMemoire depot = new DepotComptesEnMemoire();
    private final List<String> motsDePasseEncodes = new ArrayList<>();
    private final EncodeurMotDePasse encodeurFactice = motDePasse -> {
        motsDePasseEncodes.add(motDePasse.valeur());
        return "empreinte-factice";
    };
    private final InitialiserAdminMaster initialiser = new InitialiserAdminMaster(depot, encodeurFactice, horlogeFixe);

    // CA1
    @Test
    @DisplayName("CA1 - crée l'admin master avec pseudo saisi, date du Clock et empreinte de l'encodeur")
    void doit_creer_l_admin_master_sur_un_depot_vide() {
        Resultat resultat = initialiser.executer(PSEUDO, MOT_DE_PASSE);

        assertThat(resultat).isEqualTo(Resultat.CREE);
        assertThat(depot.enregistres).hasSize(1);
        Compte compte = depot.enregistres.get(0);
        assertThat(compte.role()).isEqualTo(Role.ADMIN_MASTER);
        assertThat(compte.pseudo().valeur()).isEqualTo("Patron");
        assertThat(compte.creeLe()).isEqualTo(MAINTENANT);
        assertThat(compte.empreinteMotDePasse()).isEqualTo("empreinte-factice").isNotEqualTo(MOT_DE_PASSE);
        assertThat(compte.peutSeConnecter()).isTrue();
    }

    @Test
    @DisplayName("CA1 - l'encodeur reçoit le mot de passe saisi et le compte ne le contient pas")
    void doit_encoder_le_mot_de_passe_sans_le_conserver() {
        initialiser.executer(PSEUDO, MOT_DE_PASSE);

        assertThat(motsDePasseEncodes).containsExactly(MOT_DE_PASSE);
        assertThat(depot.enregistres.get(0).toString()).doesNotContain(MOT_DE_PASSE);
    }

    @Test
    @DisplayName("CA1 - le pseudo est conservé tel que saisi après trim")
    void doit_retirer_les_espaces_de_bord_du_pseudo_en_conservant_la_casse() {
        initialiser.executer("  Patron  ", MOT_DE_PASSE);

        assertThat(depot.enregistres.get(0).pseudo().valeur()).isEqualTo("Patron");
    }

    @Test
    @DisplayName("CA1 - la date de création est tronquée à la microseconde")
    void doit_tronquer_la_date_de_creation_a_la_microseconde() {
        Instant avecNanos = Instant.parse("2026-10-02T10:00:00.123456789Z");

        new InitialiserAdminMaster(depot, encodeurFactice, Clock.fixed(avecNanos, ZoneOffset.UTC))
                .executer(PSEUDO, MOT_DE_PASSE);

        assertThat(depot.enregistres.get(0).creeLe()).isEqualTo(Instant.parse("2026-10-02T10:00:00.123456Z"));
    }

    // CA2
    static Stream<Arguments> variablesIgnoreesQuandAdminMasterExiste() {
        return Stream.of(
                Arguments.of("Patron", MOT_DE_PASSE),
                Arguments.of("a", "court"),
                Arguments.of(null, null),
                Arguments.of("Patron", null),
                Arguments.of(null, MOT_DE_PASSE));
    }

    @ParameterizedTest(name = "pseudo={0}")
    @MethodSource("variablesIgnoreesQuandAdminMasterExiste")
    @DisplayName("CA2 - un admin master existant rend le résultat DEJA_PRESENT, dépôt inchangé, encodeur non appelé")
    void doit_ignorer_les_variables_quand_un_admin_master_existe(String pseudo, String motDePasse) {
        Compte existant = adminMasterExistant("Chef");
        depot.enregistrer(existant);

        Resultat resultat = initialiser.executer(pseudo, motDePasse);

        assertThat(resultat).isEqualTo(Resultat.DEJA_PRESENT);
        assertThat(depot.enregistres).containsExactly(existant);
        assertThat(depot.enregistres.get(0).empreinteMotDePasse()).isEqualTo("empreinte-d-origine");
        assertThat(depot.enregistres.get(0).role()).isEqualTo(Role.ADMIN_MASTER);
        assertThat(motsDePasseEncodes).isEmpty();
    }

    // CA3
    @Test
    @DisplayName("CA3 - deux exécutions successives : un seul admin master, CREE puis DEJA_PRESENT, empreinte inchangée")
    void doit_etre_idempotent_sur_deux_executions() {
        Resultat premier = initialiser.executer(PSEUDO, MOT_DE_PASSE);
        String empreinteApresPremier = depot.enregistres.get(0).empreinteMotDePasse();

        Resultat second = initialiser.executer(PSEUDO, MOT_DE_PASSE);

        assertThat(premier).isEqualTo(Resultat.CREE);
        assertThat(second).isEqualTo(Resultat.DEJA_PRESENT);
        assertThat(depot.enregistres).hasSize(1);
        assertThat(depot.enregistres.get(0).role()).isEqualTo(Role.ADMIN_MASTER);
        assertThat(depot.enregistres.get(0).empreinteMotDePasse()).isEqualTo(empreinteApresPremier);
        assertThat(motsDePasseEncodes).hasSize(1);
    }

    // CA4
    static Stream<Arguments> variablesNonRenseignees() {
        return Stream.of(
                Arguments.of(null, null),
                Arguments.of("", ""),
                Arguments.of("   ", null));
    }

    @ParameterizedTest(name = "pseudo=[{0}] motDePasse=[{1}]")
    @MethodSource("variablesNonRenseignees")
    @DisplayName("CA4 - sans variable renseignée et sans admin master : NON_CONFIGURE, aucun compte")
    void doit_ne_rien_creer_quand_les_variables_ne_sont_pas_renseignees(String pseudo, String motDePasse) {
        Resultat resultat = initialiser.executer(pseudo, motDePasse);

        assertThat(resultat).isEqualTo(Resultat.NON_CONFIGURE);
        assertThat(depot.enregistres).isEmpty();
        assertThat(motsDePasseEncodes).isEmpty();
    }

    @Test
    @DisplayName("CA4 - un pseudo d'espaces seuls vaut non renseigné, un mot de passe d'espaces est une valeur (CA5)")
    void doit_considerer_un_mot_de_passe_d_espaces_comme_renseigne() {
        assertThatThrownBy(() -> initialiser.executer("   ", " ".repeat(12)))
                .isInstanceOfSatisfying(ConfigurationAdminMasterInvalideException.class, e ->
                        assertThat(e.getMessage()).contains("manquante : " + VARIABLE_PSEUDO));
        assertThat(depot.enregistres).isEmpty();
    }

    // CA5
    static Stream<Arguments> uneSeuleVariable() {
        return Stream.of(
                Arguments.of("Patron", null, VARIABLE_MOT_DE_PASSE),
                Arguments.of(null, MOT_DE_PASSE, VARIABLE_PSEUDO),
                Arguments.of("Patron", "", VARIABLE_MOT_DE_PASSE));
    }

    @ParameterizedTest(name = "pseudo={0} manquante={2}")
    @MethodSource("uneSeuleVariable")
    @DisplayName("CA5 - une seule variable renseignée : exception typée nommant la variable manquante, aucun compte")
    void doit_refuser_une_seule_variable_renseignee(String pseudo, String motDePasse, String manquante) {
        assertThatThrownBy(() -> initialiser.executer(pseudo, motDePasse))
                .isInstanceOfSatisfying(ConfigurationAdminMasterInvalideException.class, e -> {
                    assertThat(e.getMessage()).contains("manquante : " + manquante);
                    assertThat(e.violations()).extracting(ViolationValidation::champ).containsExactly(manquante);
                });
        assertThat(depot.enregistres).isEmpty();
        assertThat(motsDePasseEncodes).isEmpty();
    }

    // CA6
    static Stream<Arguments> pseudosInvalides() {
        return Stream.of(
                Arguments.of("ab", "entre 3 et 30"),
                Arguments.of("a b", "lettres"),
                Arguments.of("a".repeat(31), "entre 3 et 30"));
    }

    @ParameterizedTest(name = "pseudo de {0} caractères")
    @MethodSource("pseudosInvalides")
    @DisplayName("CA6 - pseudo invalide : l'exception nomme ADMIN_MASTER_PSEUDO et la règle violée")
    void doit_refuser_un_pseudo_invalide(String pseudo, String regle) {
        assertThatThrownBy(() -> initialiser.executer(pseudo, MOT_DE_PASSE))
                .isInstanceOfSatisfying(ConfigurationAdminMasterInvalideException.class, e -> {
                    assertThat(e.violations()).hasSize(1);
                    assertThat(e.violations().get(0).champ()).isEqualTo(VARIABLE_PSEUDO);
                    assertThat(e.getMessage()).contains(VARIABLE_PSEUDO).contains(regle);
                });
        assertThat(depot.enregistres).isEmpty();
    }

    static Stream<Arguments> motsDePasseInvalides() {
        return Stream.of(
                Arguments.of("x".repeat(11), "au moins 12 caractères"),
                Arguments.of("x".repeat(129), "128 caractères"));
    }

    @ParameterizedTest(name = "mot de passe de {0} caractères")
    @MethodSource("motsDePasseInvalides")
    @DisplayName("CA6 - mot de passe invalide : l'exception nomme ADMIN_MASTER_MOT_DE_PASSE et la règle violée")
    void doit_refuser_un_mot_de_passe_invalide(String motDePasse, String regle) {
        assertThatThrownBy(() -> initialiser.executer(PSEUDO, motDePasse))
                .isInstanceOfSatisfying(ConfigurationAdminMasterInvalideException.class, e -> {
                    assertThat(e.violations()).hasSize(1);
                    assertThat(e.violations().get(0).champ()).isEqualTo(VARIABLE_MOT_DE_PASSE);
                    assertThat(e.getMessage()).contains(VARIABLE_MOT_DE_PASSE).contains(regle);
                    assertThat(e.getMessage()).doesNotContain(motDePasse);
                });
        assertThat(depot.enregistres).isEmpty();
        assertThat(motsDePasseEncodes).isEmpty();
    }

    @Test
    @DisplayName("CA6 - pseudo et mot de passe invalides ensemble : deux violations cumulées")
    void doit_cumuler_les_violations_du_pseudo_et_du_mot_de_passe() {
        assertThatThrownBy(() -> initialiser.executer("ab", MOT_DE_PASSE_COURT))
                .isInstanceOfSatisfying(ConfigurationAdminMasterInvalideException.class, e -> {
                    assertThat(e.violations()).extracting(ViolationValidation::champ)
                            .containsExactlyInAnyOrder(VARIABLE_PSEUDO, VARIABLE_MOT_DE_PASSE);
                    assertThat(e.getMessage()).contains(VARIABLE_PSEUDO).contains(VARIABLE_MOT_DE_PASSE);
                });
        assertThat(depot.enregistres).isEmpty();
    }

    @ParameterizedTest(name = "mot de passe de {0} caractères")
    @ValueSource(ints = {12, 128})
    @DisplayName("CA6 - mots de passe de 12 et 128 caractères acceptés")
    void doit_accepter_un_mot_de_passe_aux_bornes(int longueur) {
        Resultat resultat = initialiser.executer(PSEUDO, "x".repeat(longueur));

        assertThat(resultat).isEqualTo(Resultat.CREE);
        assertThat(depot.enregistres).hasSize(1);
    }

    @Test
    @DisplayName("CA6 - un mot de passe de 12 espaces est accepté comme valeur")
    void doit_accepter_un_mot_de_passe_de_douze_espaces() {
        assertThat(initialiser.executer(PSEUDO, " ".repeat(12))).isEqualTo(Resultat.CREE);
    }

    @Test
    @DisplayName("CA6 - un pseudo en majuscules est accepté et conservé tel quel")
    void doit_accepter_un_pseudo_en_majuscules() {
        assertThat(initialiser.executer("ADMIN", MOT_DE_PASSE)).isEqualTo(Resultat.CREE);
        assertThat(depot.enregistres.get(0).pseudo().valeur()).isEqualTo("ADMIN");
        assertThat(depot.enregistres.get(0).pseudoNormalise()).isEqualTo("admin");
    }

    // CA7
    @Test
    @DisplayName("CA7 - pseudo déjà pris par un coureur sous une autre casse : exception, coureur inchangé, aucun ajout")
    void doit_refuser_un_pseudo_deja_utilise_par_un_coureur() {
        Compte alice = Compte.creerCoureur(new Pseudo("Alice"), "empreinte-alice", MAINTENANT);
        depot.enregistrer(alice);

        assertThatThrownBy(() -> initialiser.executer("ALICE", MOT_DE_PASSE))
                .isInstanceOfSatisfying(ConfigurationAdminMasterInvalideException.class, e -> {
                    assertThat(e.violations()).extracting(ViolationValidation::champ)
                            .containsExactly(VARIABLE_PSEUDO);
                    assertThat(e.getMessage()).contains(VARIABLE_PSEUDO).containsIgnoringCase("déjà utilisé");
                });
        assertThat(depot.enregistres).containsExactly(alice);
        assertThat(alice.role()).isEqualTo(Role.COUREUR);
        assertThat(motsDePasseEncodes).isEmpty();
    }

    // CA8
    @Test
    @DisplayName("CA8 - violation d'unicité de l'admin master à l'enregistrement (concurrence) : DEJA_PRESENT sans exception")
    void doit_se_comporter_comme_deja_present_quand_un_autre_demarrage_a_cree_l_admin_master() {
        DepotComptes depotConcurrent = new DepotComptesEnMemoire() {
            @Override
            public void enregistrer(Compte compte) {
                throw new AdminMasterDejaPresentException(new IllegalStateException("uk_compte_admin_master"));
            }
        };
        InitialiserAdminMaster cas = new InitialiserAdminMaster(depotConcurrent, encodeurFactice, horlogeFixe);

        Resultat resultat = cas.executer(PSEUDO, MOT_DE_PASSE);

        assertThat(resultat).isEqualTo(Resultat.DEJA_PRESENT);
    }

    // CA9
    @Test
    @DisplayName("CA9 - ni message ni toString des exceptions de CA5 à CA7 ni du résultat ne contiennent le mot de passe")
    void doit_ne_jamais_exposer_le_mot_de_passe_dans_les_erreurs() {
        List<Throwable> erreurs = new ArrayList<>();
        erreurs.add(capturer("Patron", null));
        erreurs.add(capturer(null, MOT_DE_PASSE_TEST));
        erreurs.add(capturer("ab", MOT_DE_PASSE_COURT));
        erreurs.add(capturer("Patron", MOT_DE_PASSE_COURT));
        depot.enregistrer(Compte.creerCoureur(new Pseudo("Alice"), "empreinte-alice", MAINTENANT));
        erreurs.add(capturer("alice", MOT_DE_PASSE_TEST));

        assertThat(erreurs).hasSize(5).doesNotContainNull();
        for (Throwable erreur : erreurs) {
            assertThat(erreur.getMessage()).doesNotContain(MOT_DE_PASSE_TEST).doesNotContain(MOT_DE_PASSE_COURT);
            assertThat(erreur.toString()).doesNotContain(MOT_DE_PASSE_TEST).doesNotContain(MOT_DE_PASSE_COURT);
        }
    }

    @Test
    @DisplayName("CA9 - le résultat de la création et le compte créé ne contiennent pas le mot de passe")
    void doit_ne_pas_exposer_le_mot_de_passe_dans_le_resultat_ni_le_compte() {
        Resultat resultat = new InitialiserAdminMaster(new DepotComptesEnMemoire(), encodeurFactice, horlogeFixe)
                .executer(PSEUDO, MOT_DE_PASSE_TEST);

        assertThat(resultat.toString()).doesNotContain(MOT_DE_PASSE_TEST);
    }

    private Throwable capturer(String pseudo, String motDePasse) {
        try {
            initialiser.executer(pseudo, motDePasse);
        } catch (RuntimeException e) {
            return e;
        }
        return null;
    }

    private static Compte adminMasterExistant(String pseudo) {
        return Compte.reconstituer(UUID.randomUUID(), new Pseudo(pseudo), "empreinte-d-origine",
                Role.ADMIN_MASTER, MAINTENANT);
    }

    /** Dépôt en mémoire : unicité sur le pseudo normalisé, existence d'un ADMIN_MASTER. */
    private static class DepotComptesEnMemoire implements DepotComptes {
        final List<Compte> enregistres = new ArrayList<>();

        @Override
        public boolean existeParPseudoNormalise(String pseudoNormalise) {
            return enregistres.stream().anyMatch(c -> c.pseudoNormalise().equals(pseudoNormalise));
        }

        @Override
        public boolean existeAdminMaster() {
            return enregistres.stream().anyMatch(c -> c.role() == Role.ADMIN_MASTER);
        }

        @Override
        public void enregistrer(Compte compte) {
            enregistres.add(compte);
        }
    }
}
