package fr.backyard.tracker.comptes.domaine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Incrément 9.5, CA1 : validation et normalisation du fragment de recherche (RG1, RG2, RG4).
 * Signatures attendues, dans {@code comptes.domaine} :
 * {@code static RechercheCoureurs RechercheCoureurs.de(String saisie)} (lève {@link DonneesCompteInvalidesException}
 * avec exactement une {@link ViolationValidation} de champ {@code "q"}), {@code String fragment()} (rogné, minuscules
 * {@code Locale.ROOT}, via {@code Pseudo.normaliser}) et {@code boolean aucunPseudoPossible()}.
 * Codes : RECHERCHE_REQUISE, RECHERCHE_TROP_COURTE, RECHERCHE_TROP_LONGUE. Seuils : 3 et 30 points de code.
 */
class RechercheCoureursTest {

    private static final String TRENTE = "abcdefghij".repeat(3);
    /** Lettre hors plan de base : 2 {@code char}, 1 point de code. */
    private static final String LETTRE_HORS_PLAN_DE_BASE = new String(Character.toChars(0x1D49C));

    private static ViolationValidation violationDe(String saisie) {
        try {
            RechercheCoureurs.de(saisie);
        } catch (DonneesCompteInvalidesException e) {
            assertThat(e.violations()).hasSize(1);
            return e.violations().get(0);
        }
        throw new AssertionError("Une violation était attendue pour la saisie de longueur "
                + (saisie == null ? "null" : saisie.length()));
    }

    @ParameterizedTest
    @NullSource
    @DisplayName("CA1 - une saisie absente est refusée avec RECHERCHE_REQUISE")
    void doit_refuser_une_saisie_absente(String saisie) {
        ViolationValidation violation = violationDe(saisie);

        assertThat(violation.champ()).isEqualTo("q");
        assertThat(violation.code()).isEqualTo("RECHERCHE_REQUISE");
        assertThat(violation.message()).isEqualTo("La recherche est obligatoire.");
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "a", "ab", "  ab  ", "   ", "\t\n"})
    @DisplayName("CA1 - moins de 3 caractères après rognage (vide, espaces, 2 caractères) : RECHERCHE_TROP_COURTE")
    void doit_refuser_un_fragment_de_moins_de_trois_caracteres_apres_rognage(String saisie) {
        ViolationValidation violation = violationDe(saisie);

        assertThat(violation.champ()).isEqualTo("q");
        assertThat(violation.code()).isEqualTo("RECHERCHE_TROP_COURTE");
        assertThat(violation.message()).isEqualTo("La recherche doit contenir au moins 3 caractères.");
    }

    @Test
    @DisplayName("CA1 - deux lettres hors plan de base (4 char) comptent pour 2 points de code : trop courte")
    void doit_mesurer_la_longueur_minimale_en_points_de_code() {
        assertThat(violationDe(LETTRE_HORS_PLAN_DE_BASE.repeat(2)).code()).isEqualTo("RECHERCHE_TROP_COURTE");
    }

    @ParameterizedTest
    @ValueSource(strings = {"abc", "  abc  ", "pro", "Zoé"})
    @DisplayName("CA1 - 3 caractères après rognage : fragment valide")
    void doit_accepter_un_fragment_de_trois_caracteres(String saisie) {
        assertThat(RechercheCoureurs.de(saisie)).isNotNull();
    }

    @Test
    @DisplayName("CA1 - un fragment entouré d'espaces équivaut au même fragment sans espaces")
    void doit_rogner_les_espaces_de_bord() {
        assertThat(RechercheCoureurs.de("  abc  ").fragment()).isEqualTo("abc");
    }

    @Test
    @DisplayName("CA1 - 30 caractères valides : accepté")
    void doit_accepter_un_fragment_de_trente_caracteres() {
        RechercheCoureurs recherche = RechercheCoureurs.de(TRENTE);

        assertThat(recherche.fragment()).isEqualTo(TRENTE);
        assertThat(recherche.aucunPseudoPossible()).isFalse();
    }

    @Test
    @DisplayName("CA1 - 31 caractères : RECHERCHE_TROP_LONGUE, sans répéter la saisie")
    void doit_refuser_un_fragment_de_trente_et_un_caracteres() {
        ViolationValidation violation = violationDe(TRENTE + "k");

        assertThat(violation.champ()).isEqualTo("q");
        assertThat(violation.code()).isEqualTo("RECHERCHE_TROP_LONGUE");
        assertThat(violation.message()).isEqualTo("La recherche ne peut pas dépasser 30 caractères.");
        assertThat(violation.message()).doesNotContain(TRENTE);
    }

    @Test
    @DisplayName("CA1 - 30 espaces de bord de part et d'autre d'un fragment de 3 caractères : accepté (mesure après rognage)")
    void doit_mesurer_la_longueur_maximale_apres_rognage() {
        String saisie = " ".repeat(30) + "abc" + " ".repeat(30);

        assertThat(RechercheCoureurs.de(saisie).fragment()).isEqualTo("abc");
    }

    @Test
    @DisplayName("CA1 - 30 lettres hors plan de base (60 char) comptent pour 30 points de code : accepté")
    void doit_accepter_trente_points_de_code_hors_plan_de_base() {
        assertThat(RechercheCoureurs.de(LETTRE_HORS_PLAN_DE_BASE.repeat(30)).aucunPseudoPossible()).isFalse();
    }

    @Test
    @DisplayName("CA1 - 31 lettres hors plan de base : trop longue (31 points de code)")
    void doit_refuser_trente_et_un_points_de_code_hors_plan_de_base() {
        assertThat(violationDe(LETTRE_HORS_PLAN_DE_BASE.repeat(31)).code()).isEqualTo("RECHERCHE_TROP_LONGUE");
    }

    @Test
    @DisplayName("CA1 - 29 lettres et une lettre hors plan de base (31 char, 30 points de code) : accepté")
    void doit_compter_un_caractere_hors_plan_de_base_pour_un_seul() {
        String saisie = "a".repeat(29) + LETTRE_HORS_PLAN_DE_BASE;

        assertThat(saisie.length()).isEqualTo(31);
        assertThat(RechercheCoureurs.de(saisie)).isNotNull();
    }

    @Test
    @DisplayName("CA1 - le fragment est normalisé comme un pseudo : PrOMo donne promo")
    void doit_normaliser_le_fragment_en_minuscules() {
        assertThat(RechercheCoureurs.de("PrOMo").fragment()).isEqualTo("promo");
    }

    @Test
    @DisplayName("CA1 - la normalisation est celle de Pseudo.normaliser et ne replie pas les accents")
    void doit_normaliser_comme_un_pseudo_sans_replier_les_accents() {
        assertThat(RechercheCoureurs.de(" ÉLOÏ ").fragment()).isEqualTo(Pseudo.normaliser(" ÉLOÏ "));
        assertThat(RechercheCoureurs.de("ÉLOÏ").fragment()).isEqualTo("éloï");
        assertThat(RechercheCoureurs.de("éloï").fragment()).isNotEqualTo("eloi");
    }

    @ParameterizedTest
    @ValueSource(strings = {"%%%", "a b", "a\\b", "a\u0000b", "<b>", "' OR 1=1 --", "a%c", "a\"b", "a\tb", "a*b"})
    @DisplayName("CA1 - un fragment hors de l'alphabet des pseudos est valide mais n'a aucun pseudo possible")
    void doit_signaler_aucun_pseudo_possible_hors_de_l_alphabet(String saisie) {
        assertThat(RechercheCoureurs.de(saisie).aucunPseudoPossible()).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"p_o", "a.b", "a-b", "promo01", "Éloïse", "___", "..."})
    @DisplayName("CA1 - un fragment fait de lettres, chiffres, point, tiret et souligné a des pseudos possibles")
    void doit_signaler_un_pseudo_possible_dans_l_alphabet(String saisie) {
        assertThat(RechercheCoureurs.de(saisie).aucunPseudoPossible()).isFalse();
    }

    @Test
    @DisplayName("CA1 - le souligné reste dans le fragment tel quel (l'échappement relève de l'adaptateur)")
    void doit_conserver_le_souligne_dans_le_fragment() {
        assertThat(RechercheCoureurs.de("p_o").fragment()).isEqualTo("p_o");
    }

    @Test
    @DisplayName("CA1 - ordre des contrôles : requis avant courte")
    void doit_controler_le_requis_avant_la_longueur_minimale() {
        assertThat(violationDe(null).code()).isEqualTo("RECHERCHE_REQUISE");
        assertThat(violationDe("").code()).isEqualTo("RECHERCHE_TROP_COURTE");
    }

    @Test
    @DisplayName("CA1 - ordre des contrôles : une saisie trop longue ET hors alphabet donne trop longue, une seule violation")
    void doit_ne_retenir_qu_une_violation_la_plus_prioritaire() {
        assertThatThrownBy(() -> RechercheCoureurs.de("%".repeat(40)))
                .isInstanceOfSatisfying(DonneesCompteInvalidesException.class, e -> {
                    assertThat(e.violations()).hasSize(1);
                    assertThat(e.violations().get(0).code()).isEqualTo("RECHERCHE_TROP_LONGUE");
                });
    }

    @Test
    @DisplayName("CA1 - une saisie de 2 caractères hors alphabet est trop courte (validation avant alphabet)")
    void doit_controler_la_longueur_avant_l_alphabet() {
        assertThat(violationDe("%%").code()).isEqualTo("RECHERCHE_TROP_COURTE");
    }

    @Test
    @DisplayName("CA1 - les messages de violation ne reprennent jamais la saisie")
    void doit_produire_des_messages_sans_la_saisie() {
        assertThat(violationDe("zq").message()).doesNotContain("zq");
        assertThat(violationDe("zq".repeat(16)).message()).doesNotContain("zq");
        assertThat(violationDe(null).message()).isNotBlank();
    }
}
