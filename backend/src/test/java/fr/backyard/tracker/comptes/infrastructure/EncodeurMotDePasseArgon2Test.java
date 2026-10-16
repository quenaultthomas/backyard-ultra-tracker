package fr.backyard.tracker.comptes.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import fr.backyard.tracker.comptes.domaine.MotDePasse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;

/** SEC-06 (audit 2026-10-16) : l'adaptateur Argon2id passe par le limiteur. Test unitaire sans Spring. */
class EncodeurMotDePasseArgon2Test {

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
        EncodeurMotDePasseArgon2 encodeur = new EncodeurMotDePasseArgon2(ENCODEUR_DE_TEST, "2", "100");

        String empreinte = encodeur.encoder(new MotDePasse("motdepasse-solide"));

        assertThat(empreinte).isEqualTo("empreinte:motdepasse-solide");
        assertThat(encodeur.verifier("motdepasse-solide", empreinte)).isTrue();
        assertThat(encodeur.verifier("autre-motdepasse", empreinte)).isFalse();
    }

    @Test
    @DisplayName("SEC-06 : une configuration invalide empêche le démarrage avec un message nommant les propriétés")
    void doit_refuser_une_configuration_invalide() {
        assertThatThrownBy(() -> new EncodeurMotDePasseArgon2(ENCODEUR_DE_TEST, "0", "100"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("backyard.hachage.concurrence-max")
                .hasMessageContaining("backyard.hachage.attente-max-millisecondes");
        assertThatThrownBy(() -> new EncodeurMotDePasseArgon2(ENCODEUR_DE_TEST, "1", "-1"))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new EncodeurMotDePasseArgon2(ENCODEUR_DE_TEST, "abc", "100"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("backyard.hachage.concurrence-max")
                .hasMessageContaining("abc");
    }
}
