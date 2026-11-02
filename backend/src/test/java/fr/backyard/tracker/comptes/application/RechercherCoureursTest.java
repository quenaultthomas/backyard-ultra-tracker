package fr.backyard.tracker.comptes.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import fr.backyard.tracker.comptes.domaine.Compte;
import fr.backyard.tracker.comptes.domaine.DepotComptes;
import fr.backyard.tracker.comptes.domaine.DonneesCompteInvalidesException;
import fr.backyard.tracker.comptes.domaine.Pseudo;
import fr.backyard.tracker.comptes.domaine.Role;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Incrément 9.5, CA3 : cas d'usage de recherche de coureurs par fragment de pseudo (RG1, RG3, RG4, RG5, RG9).
 * Signatures attendues :
 * <ul>
 *   <li>{@code new RechercherCoureurs(DepotComptes)} ;</li>
 *   <li>{@code RechercherCoureurs.Resultat executer(UUID appelantId, String saisie)} ;</li>
 *   <li>{@code record RechercherCoureurs.Resultat(List<Compte> coureurs, boolean plusDeResultats)} ;</li>
 *   <li>port {@code DepotComptes#rechercherCoureursConnectables(String fragmentNormalise, int limite)} renvoyant des
 *       {@code Compte} triés par pseudo normalisé, au plus {@code limite} (ici 11) ; méthode par défaut
 *       {@code UnsupportedOperationException}.</li>
 * </ul>
 * La ligne de journal (RG7) est vérifiée en intégration (CA10).
 */
class RechercherCoureursTest {

    private static final Instant CREE_LE = Instant.parse("2026-11-02T09:00:00Z");
    private static final UUID APPELANT = UUID.fromString("00000000-0000-0000-0000-0000000000f1");

    private final DepotRechercheEspion depot = new DepotRechercheEspion();
    private final RechercherCoureurs rechercher = new RechercherCoureurs(depot);

    private static Compte coureur(String pseudo) {
        return Compte.reconstituer(UUID.randomUUID(), new Pseudo(pseudo), "empreinte", Role.COUREUR, CREE_LE, true);
    }

    private static List<Compte> coureursNumerotes(int nombre) {
        return IntStream.rangeClosed(1, nombre).mapToObj(i -> coureur(String.format("promo%02d", i))).toList();
    }

    private static List<String> pseudos(List<Compte> comptes) {
        return comptes.stream().map(c -> c.pseudo().valeur()).toList();
    }

    @Test
    @DisplayName("CA3 - demande au dépôt le fragment normalisé et une limite de 11")
    void doit_demander_au_depot_le_fragment_normalise_et_onze_resultats() {
        rechercher.executer(APPELANT, "  PrOMo ");

        assertThat(depot.appels).containsExactly(new Appel("promo", 11));
    }

    @Test
    @DisplayName("CA3 - 11 Comptes renvoyés : 10 gardés dans l'ordre reçu et plusDeResultats vrai")
    void doit_garder_dix_resultats_et_signaler_plus_de_resultats_quand_le_depot_en_renvoie_onze() {
        depot.resultat = coureursNumerotes(11);

        RechercherCoureurs.Resultat resultat = rechercher.executer(APPELANT, "promo");

        assertThat(pseudos(resultat.coureurs())).containsExactly("promo01", "promo02", "promo03", "promo04",
                "promo05", "promo06", "promo07", "promo08", "promo09", "promo10");
        assertThat(resultat.plusDeResultats()).isTrue();
    }

    @Test
    @DisplayName("CA3 - exactement 10 Comptes renvoyés : 10 gardés et plusDeResultats faux")
    void doit_ne_pas_signaler_plus_de_resultats_avec_exactement_dix_correspondances() {
        depot.resultat = coureursNumerotes(10);

        RechercherCoureurs.Resultat resultat = rechercher.executer(APPELANT, "promo");

        assertThat(resultat.coureurs()).hasSize(10);
        assertThat(resultat.plusDeResultats()).isFalse();
    }

    @Test
    @DisplayName("CA3 - 3 Comptes renvoyés : 3 gardés et plusDeResultats faux")
    void doit_renvoyer_tous_les_resultats_sous_la_limite() {
        depot.resultat = coureursNumerotes(3);

        RechercherCoureurs.Resultat resultat = rechercher.executer(APPELANT, "promo");

        assertThat(resultat.coureurs()).hasSize(3);
        assertThat(resultat.plusDeResultats()).isFalse();
    }

    @Test
    @DisplayName("CA3 - aucun Compte renvoyé : résultat vide et plusDeResultats faux")
    void doit_renvoyer_un_resultat_vide_sans_correspondance() {
        depot.resultat = List.of();

        RechercherCoureurs.Resultat resultat = rechercher.executer(APPELANT, "zzz");

        assertThat(resultat.coureurs()).isEmpty();
        assertThat(resultat.plusDeResultats()).isFalse();
    }

    @Test
    @DisplayName("CA3 - l'ordre reçu du dépôt est conservé tel quel (le tri est celui du dépôt)")
    void doit_conserver_l_ordre_recu_du_depot() {
        depot.resultat = List.of(coureur("promo-b"), coureur("promo-a"), coureur("promo-c"));

        assertThat(pseudos(rechercher.executer(APPELANT, "promo").coureurs()))
                .containsExactly("promo-b", "promo-a", "promo-c");
    }

    @ParameterizedTest
    @ValueSource(strings = {"%%%", "a b", "a\\b", "a\u0000b", "<script>", "' OR 1=1 --", "a%c"})
    @DisplayName("CA3 - fragment sans pseudo possible : aucun appel au dépôt, résultat vide")
    void doit_ne_pas_interroger_le_depot_pour_un_fragment_sans_pseudo_possible(String saisie) {
        depot.resultat = coureursNumerotes(5);

        RechercherCoureurs.Resultat resultat = rechercher.executer(APPELANT, saisie);

        assertThat(depot.appels).isEmpty();
        assertThat(resultat.coureurs()).isEmpty();
        assertThat(resultat.plusDeResultats()).isFalse();
    }

    @Test
    @DisplayName("CA3 - un dépôt qui renvoie un BENEVOLE, un ADMIN, un anonymisé ou un inactif parmi les résultats : ils sont écartés")
    void doit_ecarter_les_comptes_non_eligibles_renvoyes_par_le_depot() {
        Compte benevole = Compte.reconstituer(UUID.randomUUID(), new Pseudo("promo-benevole"), "e", Role.BENEVOLE,
                CREE_LE, true);
        Compte admin = Compte.reconstituer(UUID.randomUUID(), new Pseudo("promo-admin"), "e", Role.ADMIN, CREE_LE,
                true);
        Compte master = Compte.reconstituer(UUID.randomUUID(), new Pseudo("promo-master"), "e", Role.ADMIN_MASTER,
                CREE_LE, true);
        Compte anonymise = coureur("promo-ancien").anonymiser();
        Compte inactif = coureur("promo-inactif").desactiver();
        Compte valide = coureur("promo-valide");
        depot.resultat = List.of(benevole, admin, master, anonymise, valide, inactif);

        RechercherCoureurs.Resultat resultat = rechercher.executer(APPELANT, "promo");

        assertThat(resultat.coureurs()).containsExactly(valide);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "ab", "  ab  ", "   "})
    @DisplayName("CA3 - fragment trop court : exception de validation RECHERCHE_TROP_COURTE, dépôt non appelé")
    void doit_refuser_un_fragment_trop_court_sans_appeler_le_depot(String saisie) {
        assertThatThrownBy(() -> rechercher.executer(APPELANT, saisie))
                .isInstanceOfSatisfying(DonneesCompteInvalidesException.class, e -> {
                    assertThat(e.violations()).hasSize(1);
                    assertThat(e.violations().get(0).champ()).isEqualTo("q");
                    assertThat(e.violations().get(0).code()).isEqualTo("RECHERCHE_TROP_COURTE");
                });
        assertThat(depot.appels).isEmpty();
    }

    @Test
    @DisplayName("CA3 - fragment absent : exception de validation RECHERCHE_REQUISE, dépôt non appelé")
    void doit_refuser_un_fragment_absent_sans_appeler_le_depot() {
        assertThatThrownBy(() -> rechercher.executer(APPELANT, null))
                .isInstanceOfSatisfying(DonneesCompteInvalidesException.class,
                        e -> assertThat(e.violations().get(0).code()).isEqualTo("RECHERCHE_REQUISE"));
        assertThat(depot.appels).isEmpty();
    }

    @Test
    @DisplayName("CA3 - fragment de 31 caractères : exception de validation RECHERCHE_TROP_LONGUE, dépôt non appelé")
    void doit_refuser_un_fragment_trop_long_sans_appeler_le_depot() {
        assertThatThrownBy(() -> rechercher.executer(APPELANT, "a".repeat(31)))
                .isInstanceOfSatisfying(DonneesCompteInvalidesException.class,
                        e -> assertThat(e.violations().get(0).code()).isEqualTo("RECHERCHE_TROP_LONGUE"));
        assertThat(depot.appels).isEmpty();
    }

    @Test
    @DisplayName("CA3 - un fragment de 30 caractères est transmis au dépôt")
    void doit_transmettre_au_depot_un_fragment_de_trente_caracteres() {
        rechercher.executer(APPELANT, "a".repeat(30));

        assertThat(depot.appels).containsExactly(new Appel("a".repeat(30), 11));
    }

    @Test
    @DisplayName("CA3 - le souligné est transmis tel quel au dépôt (l'échappement du LIKE relève de l'adaptateur)")
    void doit_transmettre_le_souligne_non_echappe() {
        rechercher.executer(APPELANT, "p_o");

        assertThat(depot.appels).containsExactly(new Appel("p_o", 11));
    }

    @Test
    @DisplayName("CA3 - la recherche est en lecture seule : une seule lecture, aucune écriture")
    void doit_ne_faire_aucune_ecriture() {
        depot.resultat = coureursNumerotes(4);

        rechercher.executer(APPELANT, "promo");

        assertThat(depot.appels).hasSize(1);
        assertThat(depot.ecritures).isZero();
    }

    private record Appel(String fragment, int limite) {
    }

    /** Dépôt factice : enregistre les recherches, compte toute écriture, et ne sait rien faire d'autre. */
    private static final class DepotRechercheEspion implements DepotComptes {
        final List<Appel> appels = new ArrayList<>();
        List<Compte> resultat = List.of();
        int ecritures;

        @Override
        public List<Compte> rechercherCoureursConnectables(String fragmentNormalise, int limite) {
            appels.add(new Appel(fragmentNormalise, limite));
            return resultat;
        }

        @Override
        public boolean existeParPseudoNormalise(String pseudoNormalise) {
            return false;
        }

        @Override
        public void enregistrer(Compte compte) {
            ecritures++;
        }

        @Override
        public void mettreAJour(Compte compte) {
            ecritures++;
        }

        @Override
        public void supprimer(UUID id) {
            ecritures++;
        }
    }
}
