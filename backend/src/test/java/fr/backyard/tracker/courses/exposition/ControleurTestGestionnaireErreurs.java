package fr.backyard.tracker.courses.exposition;

import fr.backyard.tracker.courses.domaine.CourseIntrouvableException;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Contrôleur de test (7.3 CA12), actif sous le seul profil {@code test-gestionnaire} : placé dans
 * {@code courses.exposition}, il lève une exception métier que le gestionnaire d'erreurs des Courses doit traduire.
 */
@RestController
@Profile("test-gestionnaire")
public class ControleurTestGestionnaireErreurs {

    @GetMapping("/api/test-gestionnaire/introuvable")
    public String introuvable() {
        throw new CourseIntrouvableException();
    }
}
