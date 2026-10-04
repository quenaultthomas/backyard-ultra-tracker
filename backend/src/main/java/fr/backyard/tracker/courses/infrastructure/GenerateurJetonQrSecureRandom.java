package fr.backyard.tracker.courses.infrastructure;

import fr.backyard.tracker.courses.domaine.GenerateurJetonQr;
import fr.backyard.tracker.courses.domaine.JetonQr;
import org.springframework.stereotype.Component;

/** Générateur de production : jetons aléatoires issus de SecureRandom (voir {@link JetonQr#generer()}). */
@Component
public class GenerateurJetonQrSecureRandom implements GenerateurJetonQr {

    @Override
    public JetonQr generer() {
        return JetonQr.generer();
    }
}
