package fr.backyard.tracker.comptes.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import fr.backyard.tracker.comptes.domaine.MotDePasse;
import fr.backyard.tracker.partage.infrastructure.AdresseCliente;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.security.crypto.password.PasswordEncoder;

/** SEC-06 (audit 2026-10-16) : l'adaptateur Argon2id passe par le limiteur. Test unitaire sans Spring. */
class EncodeurMotDePasseArgon2Test {

    private static final AdresseCliente SANS_PROXY = new AdresseCliente("");

    /** Encodeur réversible de test : l'empreinte est la valeur préfixée. */
    private static final PasswordEncoder ENCODEUR_DE_TEST = new PasswordEncoder() {
        @Override
        public String encode(CharSequence brut) {
            return "empreinte:" + brut;
        }

        @Override
        public boolean matches(CharSequence brut, String empreinte) {
            return empreinte.equals(encode(brut));
        }
    };

    @Test
    @DisplayName("SEC-06 : encode et vérifie par le PasswordEncoder délégué")
    void doit_encoder_et_verifier() {
        EncodeurMotDePasseArgon2 encodeur = new EncodeurMotDePasseArgon2(ENCODEUR_DE_TEST, SANS_PROXY, "2", "1", "100");

        String empreinte = encodeur.encoder(new MotDePasse("motdepasse-solide"));

        assertThat(empreinte).isEqualTo("empreinte:motdepasse-solide");
        assertThat(encodeur.verifier("motdepasse-solide", empreinte)).isTrue();
        assertThat(encodeur.verifier("autre-motdepasse", empreinte)).isFalse();
    }

    @Test
    @DisplayName("SEC-06 : une configuration invalide empêche le démarrage avec un message nommant les propriétés")
    void doit_refuser_une_configuration_invalide() {
        assertThatThrownBy(() -> new EncodeurMotDePasseArgon2(ENCODEUR_DE_TEST, SANS_PROXY, "0", "1", "100"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("backyard.hachage.concurrence-max")
                .hasMessageContaining("backyard.hachage.attente-max-millisecondes");
        assertThatThrownBy(() -> new EncodeurMotDePasseArgon2(ENCODEUR_DE_TEST, SANS_PROXY, "1", "1", "-1"))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new EncodeurMotDePasseArgon2(ENCODEUR_DE_TEST, SANS_PROXY, "abc", "1", "100"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("backyard.hachage.concurrence-max")
                .hasMessageContaining("abc");
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "-1", "abc"})
    @DisplayName("CA5 - concurrence-max-par-ip invalide (0, -1, abc) : démarrage en échec citant la propriété")
    void doit_refuser_un_plafond_par_adresse_invalide(String valeur) {
        assertThatThrownBy(() -> new EncodeurMotDePasseArgon2(ENCODEUR_DE_TEST, SANS_PROXY, "4", valeur, "100"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("backyard.hachage.concurrence-max-par-ip");
    }

    @ParameterizedTest
    @ValueSource(strings = {"1", "10"})
    @DisplayName("CA5 - concurrence-max-par-ip valide (1, 10 même supérieur au plafond global) : accepté")
    void doit_accepter_un_plafond_par_adresse_valide(String valeur) {
        EncodeurMotDePasseArgon2 encodeur =
                new EncodeurMotDePasseArgon2(ENCODEUR_DE_TEST, SANS_PROXY, "4", valeur, "100");

        assertThat(encodeur.verifier("motdepasse-solide", "empreinte:motdepasse-solide")).isTrue();
    }
}
