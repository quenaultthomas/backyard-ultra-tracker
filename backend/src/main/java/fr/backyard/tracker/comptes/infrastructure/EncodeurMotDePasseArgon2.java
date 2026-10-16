package fr.backyard.tracker.comptes.infrastructure;

import fr.backyard.tracker.comptes.domaine.EncodeurMotDePasse;
import fr.backyard.tracker.comptes.domaine.HachageSatureException;
import fr.backyard.tracker.comptes.domaine.MotDePasse;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * Implémentation du port d'encodage et de vérification par le {@link PasswordEncoder} Argon2id de
 * {@link SecuriteConfiguration}. Chaque calcul passe par un {@link LimiteurHachage} (SEC-06) : au plus
 * {@code backyard.hachage.concurrence-max} calculs simultanés (variable HACHAGE_CONCURRENCE_MAX), attente bornée par
 * {@code backyard.hachage.attente-max-millisecondes}. Valeurs par défaut dans application.yml.
 */
@Component
public class EncodeurMotDePasseArgon2 implements EncodeurMotDePasse {

    static final String PROPRIETE_CONCURRENCE_MAX = "backyard.hachage.concurrence-max";
    static final String PROPRIETE_ATTENTE_MAX = "backyard.hachage.attente-max-millisecondes";

    private final PasswordEncoder passwordEncoder;
    private final LimiteurHachage limiteur;

    public EncodeurMotDePasseArgon2(PasswordEncoder passwordEncoder,
                                    @Value("${" + PROPRIETE_CONCURRENCE_MAX + "}") String concurrenceMax,
                                    @Value("${" + PROPRIETE_ATTENTE_MAX + "}") String attenteMaxMillisecondes) {
        this.passwordEncoder = passwordEncoder;
        this.limiteur = limiteur(entier(PROPRIETE_CONCURRENCE_MAX, concurrenceMax),
                entier(PROPRIETE_ATTENTE_MAX, attenteMaxMillisecondes));
    }

    /** @throws HachageSatureException trop de calculs simultanés */
    @Override
    public String encoder(MotDePasse motDePasse) {
        return limiteur.executer(() -> passwordEncoder.encode(motDePasse.valeur()));
    }

    /** @throws HachageSatureException trop de calculs simultanés */
    @Override
    public boolean verifier(String motDePasseEnClair, String empreinte) {
        return limiteur.executer(() -> passwordEncoder.matches(motDePasseEnClair, empreinte));
    }

    private static int entier(String propriete, String valeur) {
        try {
            return Integer.parseInt(valeur.trim());
        } catch (NumberFormatException exception) {
            throw new IllegalStateException(propriete + " doit être un entier (reçu : « " + valeur + " »).", exception);
        }
    }

    private static LimiteurHachage limiteur(int concurrenceMax, long attenteMaxMillisecondes) {
        try {
            return new LimiteurHachage(concurrenceMax, Duration.ofMillis(attenteMaxMillisecondes));
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException("Hachage mal configuré (" + PROPRIETE_CONCURRENCE_MAX + ", "
                    + PROPRIETE_ATTENTE_MAX + ") : " + exception.getMessage(), exception);
        }
    }
}
