package fr.backyard.tracker.comptes.infrastructure;

import fr.backyard.tracker.comptes.domaine.EncodeurMotDePasse;
import fr.backyard.tracker.comptes.domaine.MotDePasse;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

/** Implémentation du port d'encodage par le {@link PasswordEncoder} Argon2id de {@link SecuriteConfiguration}. */
@Component
public class EncodeurMotDePasseArgon2 implements EncodeurMotDePasse {

    private final PasswordEncoder passwordEncoder;

    public EncodeurMotDePasseArgon2(PasswordEncoder passwordEncoder) {
        this.passwordEncoder = passwordEncoder;
    }

    @Override
    public String encoder(MotDePasse motDePasse) {
        return passwordEncoder.encode(motDePasse.valeur());
    }
}
