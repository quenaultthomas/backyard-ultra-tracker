package fr.backyard.tracker.comptes.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import fr.backyard.tracker.comptes.application.CreerBenevole.Commande;
import fr.backyard.tracker.comptes.domaine.Compte;
import fr.backyard.tracker.comptes.domaine.DepotComptes;
import fr.backyard.tracker.comptes.domaine.DonneesCompteInvalidesException;
import fr.backyard.tracker.comptes.domaine.EncodeurMotDePasse;
import fr.backyard.tracker.comptes.domaine.Pseudo;
import fr.backyard.tracker.comptes.domaine.PseudoDejaUtiliseException;
import fr.backyard.tracker.comptes.domaine.Role;
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

/** Tests de l'incrément 1.6a : création d'un compte BENEVOLE par un admin (le rôle de l'appelant est contrôlé en exposition). */
class CreerBenevoleTest {

    private static final Instant MAINTENANT = Instant.parse("2026-10-02T10:00:00Z");
    private static final String MOT_DE_PASSE = "mot-de-passe-benevole-1";
    private static final String MOT_DE_PASSE_TEST = "secret-de-test-123";
    private static final String MOT_DE_PASSE_COURT = "court-secre";

    private final Clock horlogeFixe = Clock.fixed(MAINTENANT, ZoneOffset.UTC);
    private final DepotComptesEnMemoire depot = new DepotComptesEnMemoire();
    private final List<String> motsDePasseEncodes = new ArrayList<>();
    private final EncodeurMotDePasse encodeurFactice = motDePasse -> {
        motsDePasseEncodes.add(motDePasse.valeur());
        return "empreinte-factice";
    };
    private final CreerBenevole creerBenevole = new CreerBenevole(depot, encodeurFactice, horlogeFixe);

    // CA1
    @Test
    @DisplayName("CA1 - crée et renvoie un compte BENEVOLE complet : pseudo trimé, normalisé, date du Clock, empreinte de l'encodeur")
    void doit_creer_un_compte_benevole_complet() {
        Compte compte = creerBenevole.executer(new Commande("  Léo  ", MOT_DE_PASSE));

        assertThat(depot.enregistres).containsExactly(compte);
        assertThat(compte.id()).isNotNull();
        assertThat(compte.role()).isEqualTo(Role.BENEVOLE);
        assertThat(compte.pseudo().valeur()).isEqualTo("Léo");
        assertThat(compte.pseudoNormalise()).isEqualTo("léo");
        assertThat(compte.creeLe()).isEqualTo(MAINTENANT);
        assertThat(compte.empreinteMotDePasse()).isEqualTo("empreinte-factice").isNotEqualTo(MOT_DE_PASSE);
        assertThat(compte.peutSeConnecter()).isTrue();
    }

    @Test
    @DisplayName("CA1 - l'encodeur reçoit le mot de passe saisi et le compte ne le contient jamais")
    void doit_encoder_le_mot_de_passe_sans_le_conserver() {
        Compte compte = creerBenevole.executer(new Commande("Léo", MOT_DE_PASSE));

        assertThat(motsDePasseEncodes).containsExactly(MOT_DE_PASSE);
        assertThat(compte.toString()).doesNotContain(MOT_DE_PASSE);
    }

    @Test
    @DisplayName("CA1 - la date de création est tronquée à la microseconde")
    void doit_tronquer_la_date_de_creation_a_la_microseconde() {
        Instant avecNanos = Instant.parse("2026-10-02T10:00:00.123456789Z");

        Compte compte = new CreerBenevole(depot, encodeurFactice, Clock.fixed(avecNanos, ZoneOffset.UTC))
                .executer(new Commande("Léo", MOT_DE_PASSE));

        assertThat(compte.creeLe()).isEqualTo(Instant.parse("2026-10-02T10:00:00.123456Z"));
    }

    // CA2
    static Stream<Arguments> saisiesInvalides() {
        return Stream.of(
                Arguments.of("ab", MOT_DE_PASSE, "pseudo", "PSEUDO_LONGUEUR"),
                Arguments.of("a b", MOT_DE_PASSE, "pseudo", "PSEUDO_CARACTERES"),
                Arguments.of("", MOT_DE_PASSE, "pseudo", "PSEUDO_REQUIS"),
                Arguments.of("a".repeat(31), MOT_DE_PASSE, "pseudo", "PSEUDO_LONGUEUR"),
                Arguments.of("Léo", MOT_DE_PASSE_COURT, "motDePasse", "MOT_DE_PASSE_TROP_COURT"),
                Arguments.of("Léo", "x".repeat(129), "motDePasse", "MOT_DE_PASSE_TROP_LONG"),
                Arguments.of("Léo", null, "motDePasse", "MOT_DE_PASSE_REQUIS"));
    }

    @ParameterizedTest(name = "pseudo=[{0}] -> {2} {3}")
    @MethodSource("saisiesInvalides")
    @DisplayName("CA2 - une saisie invalide lève une validation avec le code de 1.1, sans rien enregistrer")
    void doit_refuser_une_saisie_invalide_sans_rien_enregistrer(
            String pseudo, String motDePasse, String champ, String code) {
        assertThatThrownBy(() -> creerBenevole.executer(new Commande(pseudo, motDePasse)))
                .isInstanceOfSatisfying(DonneesCompteInvalidesException.class, e -> {
                    assertThat(e.violations()).hasSize(1);
                    assertThat(e.violations().get(0).champ()).isEqualTo(champ);
                    assertThat(e.violations().get(0).code()).isEqualTo(code);
                });
        assertThat(depot.enregistres).isEmpty();
    }

    @Test
    @DisplayName("CA2 - pseudo absent : PSEUDO_REQUIS")
    void doit_signaler_pseudo_requis_quand_le_pseudo_est_absent() {
        assertThatThrownBy(() -> creerBenevole.executer(new Commande(null, MOT_DE_PASSE)))
                .isInstanceOfSatisfying(DonneesCompteInvalidesException.class, e ->
                        assertThat(e.violations()).extracting(v -> v.code()).containsExactly("PSEUDO_REQUIS"));
        assertThat(depot.enregistres).isEmpty();
    }

    @Test
    @DisplayName("CA2 - pseudo et mot de passe invalides : deux violations, aucun compte, ni encodeur ni dépôt sollicités")
    void doit_remonter_les_deux_violations_sans_solliciter_encodeur_ni_depot() {
        DepotComptes depotEspion = mock(DepotComptes.class);
        EncodeurMotDePasse encodeurEspion = mock(EncodeurMotDePasse.class);
        CreerBenevole casUsage = new CreerBenevole(depotEspion, encodeurEspion, horlogeFixe);

        assertThatThrownBy(() -> casUsage.executer(new Commande("ab", MOT_DE_PASSE_COURT)))
                .isInstanceOfSatisfying(DonneesCompteInvalidesException.class, e -> {
                    assertThat(e.violations()).hasSize(2);
                    assertThat(e.violations()).anySatisfy(v -> {
                        assertThat(v.champ()).isEqualTo("pseudo");
                        assertThat(v.code()).isEqualTo("PSEUDO_LONGUEUR");
                    });
                    assertThat(e.violations()).anySatisfy(v -> {
                        assertThat(v.champ()).isEqualTo("motDePasse");
                        assertThat(v.code()).isEqualTo("MOT_DE_PASSE_TROP_COURT");
                    });
                });
        verifyNoInteractions(depotEspion, encodeurEspion);
    }

    @ParameterizedTest(name = "pseudo de {0} caractères accepté")
    @ValueSource(ints = {3, 30})
    @DisplayName("CA2 - bornes du pseudo : 3 et 30 caractères acceptés")
    void doit_accepter_un_pseudo_aux_bornes(int longueur) {
        Compte compte = creerBenevole.executer(new Commande("a".repeat(longueur), MOT_DE_PASSE));

        assertThat(compte.pseudo().valeur()).hasSize(longueur);
        assertThat(compte.role()).isEqualTo(Role.BENEVOLE);
    }

    @ParameterizedTest(name = "mot de passe de {0} caractères accepté")
    @ValueSource(ints = {12, 128})
    @DisplayName("CA2 - bornes du mot de passe : 12 et 128 caractères acceptés")
    void doit_accepter_un_mot_de_passe_aux_bornes(int longueur) {
        Compte compte = creerBenevole.executer(new Commande("Léo", "x".repeat(longueur)));

        assertThat(depot.enregistres).containsExactly(compte);
        assertThat(motsDePasseEncodes).containsExactly("x".repeat(longueur));
    }

    // CA3
    @ParameterizedTest(name = "pseudo {0} déjà pris")
    @ValueSource(strings = {"ALICE", "nadia", "PATRON", "léo"})
    @DisplayName("CA3 - un pseudo déjà pris par un coureur, un admin, l'admin master ou un bénévole est refusé, tous rôles confondus")
    void doit_refuser_un_pseudo_deja_utilise_quel_que_soit_le_role_du_proprietaire(String pseudo) {
        depot.enregistres.add(compte("Alice", Role.COUREUR));
        depot.enregistres.add(compte("Nadia", Role.ADMIN));
        depot.enregistres.add(compte("Patron", Role.ADMIN_MASTER));
        depot.enregistres.add(compte("Léo", Role.BENEVOLE));
        List<Compte> avant = List.copyOf(depot.enregistres);
        List<Role> rolesAvant = depot.enregistres.stream().map(Compte::role).toList();

        assertThatThrownBy(() -> creerBenevole.executer(new Commande(pseudo, MOT_DE_PASSE)))
                .isInstanceOf(PseudoDejaUtiliseException.class);

        assertThat(depot.enregistres).containsExactlyElementsOf(avant);
        assertThat(depot.enregistres.stream().map(Compte::role).toList()).isEqualTo(rolesAvant);
        assertThat(motsDePasseEncodes).isEmpty();
    }

    // CA4
    @Test
    @DisplayName("CA4 - une violation d'unicité levée à l'enregistrement (concurrence) devient « pseudo déjà utilisé »")
    void doit_lever_pseudo_deja_utilise_quand_le_depot_signale_un_conflit_concurrent() {
        DepotComptes depotConcurrent = new DepotComptes() {
            @Override
            public boolean existeParPseudoNormalise(String pseudoNormalise) {
                return false;
            }

            @Override
            public void enregistrer(Compte compte) {
                throw new PseudoDejaUtiliseException(new IllegalStateException("contrainte unique"));
            }
        };

        assertThatThrownBy(() -> new CreerBenevole(depotConcurrent, encodeurFactice, horlogeFixe)
                .executer(new Commande("Léo", MOT_DE_PASSE)))
                .isInstanceOf(PseudoDejaUtiliseException.class);
    }

    // CA5
    @Test
    @DisplayName("CA5 - pseudo existant et mot de passe trop court : la validation prime sur « pseudo déjà utilisé »")
    void doit_lever_la_validation_avant_l_unicite() {
        depot.enregistres.add(compte("Alice", Role.COUREUR));

        assertThatThrownBy(() -> creerBenevole.executer(new Commande("alice", MOT_DE_PASSE_COURT)))
                .isInstanceOfSatisfying(DonneesCompteInvalidesException.class, e ->
                        assertThat(e.violations()).extracting(v -> v.code())
                                .containsExactly("MOT_DE_PASSE_TROP_COURT"));
        assertThat(depot.enregistres).hasSize(1);
    }

    // CA6
    @ParameterizedTest(name = "mot de passe {0}")
    @ValueSource(strings = {MOT_DE_PASSE_TEST, MOT_DE_PASSE_COURT})
    @DisplayName("CA6 - la validation n'expose jamais le mot de passe (message, toString)")
    void doit_masquer_le_mot_de_passe_dans_l_exception_de_validation(String motDePasse) {
        Commande commande = new Commande("ab", motDePasse);

        assertThatThrownBy(() -> creerBenevole.executer(commande))
                .isInstanceOfSatisfying(DonneesCompteInvalidesException.class, e -> {
                    assertThat(e.getMessage()).doesNotContain(motDePasse);
                    assertThat(e.toString()).doesNotContain(motDePasse);
                });
    }

    @ParameterizedTest(name = "mot de passe {0}")
    @ValueSource(strings = {MOT_DE_PASSE_TEST, MOT_DE_PASSE_COURT})
    @DisplayName("CA6 - « pseudo déjà utilisé » n'expose jamais le mot de passe (message, toString)")
    void doit_masquer_le_mot_de_passe_dans_l_exception_pseudo_deja_utilise(String motDePasse) {
        depot.enregistres.add(compte("Léo", Role.BENEVOLE));
        // Le mot de passe court est refusé par la validation : on teste ici un mot de passe valide.
        String valide = motDePasse.length() >= 12 ? motDePasse : motDePasse + "-complete";

        assertThatThrownBy(() -> creerBenevole.executer(new Commande("léo", valide)))
                .isInstanceOfSatisfying(PseudoDejaUtiliseException.class, e -> {
                    assertThat(e.getMessage()).doesNotContain(motDePasse);
                    assertThat(e.toString()).doesNotContain(motDePasse);
                });
    }

    @Test
    @DisplayName("CA6 - le conflit concurrent n'expose jamais le mot de passe")
    void doit_masquer_le_mot_de_passe_dans_l_exception_de_conflit_concurrent() {
        DepotComptes depotConcurrent = new DepotComptes() {
            @Override
            public boolean existeParPseudoNormalise(String pseudoNormalise) {
                return false;
            }

            @Override
            public void enregistrer(Compte compte) {
                throw new PseudoDejaUtiliseException(new IllegalStateException("contrainte unique"));
            }
        };

        assertThatThrownBy(() -> new CreerBenevole(depotConcurrent, encodeurFactice, horlogeFixe)
                .executer(new Commande("Léo", MOT_DE_PASSE_TEST)))
                .isInstanceOfSatisfying(PseudoDejaUtiliseException.class, e -> {
                    assertThat(e.getMessage()).doesNotContain(MOT_DE_PASSE_TEST);
                    assertThat(e.toString()).doesNotContain(MOT_DE_PASSE_TEST);
                });
    }

    @ParameterizedTest(name = "mot de passe {0}")
    @ValueSource(strings = {MOT_DE_PASSE_TEST, MOT_DE_PASSE_COURT})
    @DisplayName("CA6 - la commande du cas d'usage masque le mot de passe dans son toString")
    void doit_masquer_le_mot_de_passe_dans_la_commande(String motDePasse) {
        String texte = new Commande("Léo", motDePasse).toString();

        assertThat(texte).doesNotContain(motDePasse).contains("motDePasse=masqué").contains("Léo");
    }

    private static Compte compte(String pseudo, Role role) {
        return Compte.reconstituer(UUID.randomUUID(), new Pseudo(pseudo), "empreinte-d-origine", role, MAINTENANT);
    }

    /** Dépôt en mémoire : l'unicité se juge sur le pseudo normalisé, tous rôles confondus. */
    private static final class DepotComptesEnMemoire implements DepotComptes {
        final List<Compte> enregistres = new ArrayList<>();

        @Override
        public boolean existeParPseudoNormalise(String pseudoNormalise) {
            return enregistres.stream().anyMatch(c -> c.pseudoNormalise().equals(pseudoNormalise));
        }

        @Override
        public void enregistrer(Compte compte) {
            enregistres.add(compte);
        }
    }
}
