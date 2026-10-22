package fr.backyard.tracker.courses.exposition;

import fr.backyard.tracker.courses.application.ConsulterSuivi;
import java.util.List;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

/**
 * Suivi public d'une Course, ouvert à tous par la politique de sécurité (GET uniquement). Jamais mis en cache.
 * Lecture fréquente : aucun journal. Aucun paramètre lu : l'instant vient uniquement de l'horloge de l'application.
 */
@RestController
public class SuiviPublicController {

    private static final String CHEMIN = "/api/courses/{id}/suivi";

    private final ConsulterSuivi consulterSuivi;
    public SuiviPublicController(ConsulterSuivi consulterSuivi) {
        this.consulterSuivi = consulterSuivi;
    }

    /** Identifiant inconnu ou invalide : même 404 COURSE_INTROUVABLE. */
    @GetMapping(CHEMIN)
    public ResponseEntity<SuiviCourseReponse> consulterSuivi(@PathVariable("id") String id) {
        ConsulterSuivi.Suivi suivi = consulterSuivi.executer(IdentifiantCourse.deCourse(id));
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(SuiviCourseReponse.depuis(suivi));
    }

    /** HEAD, implicitement servi sur un GET par Spring MVC, n'est pas exposé : jamais de 2xx hors GET. */
    @RequestMapping(path = CHEMIN, method = RequestMethod.HEAD)
    public void refuserHead() throws HttpRequestMethodNotSupportedException {
        throw new HttpRequestMethodNotSupportedException(HttpMethod.HEAD.name(), List.of(HttpMethod.GET.name()));
    }
}
