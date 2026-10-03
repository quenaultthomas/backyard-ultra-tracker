package fr.backyard.tracker.courses.domaine;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Tests de l'incrément 3.1 : jeton QR aléatoire, opaque et jamais montré par toString (CA1 ; RG7). */
class JetonQrTest {

    @Test
    @DisplayName("CA1 - deux jetons générés sont différents")
    void doit_generer_deux_jetons_differents() {
        assertThat(JetonQr.generer().valeur()).isNotEqualTo(JetonQr.generer().valeur());
    }

    @Test
    @DisplayName("CA1 - un jeton généré fait 43 caractères base64url sans remplissage")
    void doit_generer_un_jeton_de_43_caracteres_base64url_sans_remplissage() {
        for (int i = 0; i < 20; i++) {
            assertThat(JetonQr.generer().valeur()).hasSize(43).matches("[A-Za-z0-9_-]{43}").doesNotContain("=");
        }
    }

    @Test
    @DisplayName("CA1 - toString ne contient pas la valeur du jeton")
    void doit_masquer_la_valeur_dans_to_string() {
        JetonQr jeton = JetonQr.generer();

        assertThat(jeton.toString()).doesNotContain(jeton.valeur());
    }
}
