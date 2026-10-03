package fr.backyard.tracker.courses.exposition;

import fr.backyard.tracker.courses.application.InscrireCoureur;
import fr.backyard.tracker.courses.application.ListerCourses;
import fr.backyard.tracker.courses.application.ListerCoursesOuvertes;
import fr.backyard.tracker.courses.application.ListerCoursesOuvertes.CourseOuverte;
import fr.backyard.tracker.courses.domaine.Inscription;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Courses ouvertes et inscription du coureur connecté. Réservé à COUREUR par la politique de sécurité
 * (/api/coureur/**). L'identité vient uniquement de la session (principal : identifiant du Compte), jamais d'un
 * paramètre ni d'un corps. Le journal ne contient ni Compte, ni pseudo, ni jeton, ni nom de Course.
 */
@RestController
@RequestMapping("/api/coureur/courses")
public class InscriptionsCoureurController {

    private static final Logger JOURNAL = LoggerFactory.getLogger(InscriptionsCoureurController.class);

    private final ListerCoursesOuvertes listerCoursesOuvertes;
    private final InscrireCoureur inscrireCoureur;
    private final ListerCourses listerCourses;

    public InscriptionsCoureurController(ListerCoursesOuvertes listerCoursesOuvertes,
                                         InscrireCoureur inscrireCoureur, ListerCourses listerCourses) {
        this.listerCoursesOuvertes = listerCoursesOuvertes;
        this.inscrireCoureur = inscrireCoureur;
        this.listerCourses = listerCourses;
    }

    @GetMapping
    public List<CourseOuverteReponse> listerCoursesOuvertes(@AuthenticationPrincipal UUID compteId) {
        return listerCoursesOuvertes.executer(compteId).stream().map(this::versReponse).toList();
    }

    /** Un corps éventuel est ignoré ; un identifiant qui n'est pas un UUID donne 404 comme un UUID inconnu. */
    @PostMapping("/{id}/inscriptions")
    @ResponseStatus(HttpStatus.CREATED)
    public InscriptionReponse sinscrire(@AuthenticationPrincipal UUID compteId, @PathVariable("id") String id) {
        Inscription inscription = inscrireCoureur.executer(compteId, IdentifiantCourse.deCourse(id));
        JOURNAL.info("Inscription enregistrée (course {}, dossard {})", inscription.courseId(), inscription.dossard());
        return InscriptionReponse.depuis(inscription);
    }

    private CourseOuverteReponse versReponse(CourseOuverte courseOuverte) {
        return CourseOuverteReponse.depuis(listerCourses.avecEmpreinteLogo(courseOuverte.course()),
                courseOuverte.monInscription());
    }
}
