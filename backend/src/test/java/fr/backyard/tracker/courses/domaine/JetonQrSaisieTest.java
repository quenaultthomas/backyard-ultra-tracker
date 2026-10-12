package fr.backyard.tracker.courses.domaine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tests de l'incrément 4.4a : normalisation d'un jeton saisi ou lu (CA2 ; RG2, RG13).
 * Signature attendue : {@code static JetonQr JetonQr.depuisSaisie(String)} ; saisie null ou blanche :
 * {@code NullPointerException} / {@code IllegalArgumentException} (comme le constructeur existant).
 */
class JetonQrSaisieTest {

    @Test
    @DisplayName("CA2 - les espaces autour sont retirés")
    void doit_retirer_les_espaces_autour_de_la_saisie() {
        assertThat(JetonQr.depuisSaisie("  AbC_-  ").valeur()).isEqualTo("AbC_-");
    }

    @Test
    @DisplayName("CA2 - tous les caractères d'espacement internes sont retirés (retour à la ligne, tabulation, espaces, insécable)")
    void doit_retirer_tous_les_caracteres_d_espacement_a_l_interieur() {
        assertThat(JetonQr.depuisSaisie("Ab\nCd\t Ef Gh").valeur()).isEqualTo("AbCdEfGh");
    }

    @Test
    @DisplayName("CA2 - un jeton coupé sur deux lignes (CRLF) est recollé")
    void doit_recoller_un_jeton_coupe_sur_deux_lignes() {
        assertThat(JetonQr.depuisSaisie("Xk3AbCdEf\r\nGhIjKl").valeur()).isEqualTo("Xk3AbCdEfGhIjKl");
    }

    @Test
    @DisplayName("CA2 - la casse est significative : abc et ABC restent distincts, aucune mise en minuscules")
    void doit_conserver_la_casse() {
        assertThat(JetonQr.depuisSaisie("abc")).isNotEqualTo(JetonQr.depuisSaisie("ABC"));
        assertThat(JetonQr.depuisSaisie("ABC").valeur()).isEqualTo("ABC");
    }

    @Test
    @DisplayName("CA2 - deux saisies égales après nettoyage donnent des jetons égaux")
    void doit_donner_des_jetons_egaux_pour_deux_saisies_egales_apres_nettoyage() {
        assertThat(JetonQr.depuisSaisie(" Ab Cd\n")).isEqualTo(JetonQr.depuisSaisie("AbCd"));
    }

    @Test
    @DisplayName("CA2 - un jeton généré, collé avec des espaces autour, redonne le même jeton")
    void doit_retrouver_un_jeton_genere_apres_nettoyage() {
        JetonQr jeton = JetonQr.generer();

        assertThat(JetonQr.depuisSaisie(" \n" + jeton.valeur() + "\t ")).isEqualTo(jeton);
    }

    @Test
    @DisplayName("CA2 - une saisie vide est refusée")
    void doit_refuser_une_saisie_vide() {
        assertThatThrownBy(() -> JetonQr.depuisSaisie("")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("CA2 - une saisie faite uniquement d'espacement (dont insécable) est refusée")
    void doit_refuser_une_saisie_blanche() {
        assertThatThrownBy(() -> JetonQr.depuisSaisie(" \t\n  ")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("CA2 - une saisie null est refusée")
    void doit_refuser_une_saisie_nulle() {
        assertThatThrownBy(() -> JetonQr.depuisSaisie(null)).isInstanceOfAny(NullPointerException.class,
                IllegalArgumentException.class);
    }

    @Test
    @DisplayName("CA2 - RG13 : toString ne contient pas la valeur du jeton saisi")
    void doit_masquer_la_valeur_du_jeton_saisi_dans_to_string() {
        JetonQr jeton = JetonQr.depuisSaisie(" SecretAbC123 ");

        assertThat(jeton.toString()).doesNotContain("SecretAbC123");
    }
}
