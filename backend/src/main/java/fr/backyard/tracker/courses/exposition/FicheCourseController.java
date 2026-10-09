package fr.backyard.tracker.courses.exposition;

import fr.backyard.tracker.courses.application.AffecterBenevoles;
import fr.backyard.tracker.courses.application.DemarrerCourse;
import fr.backyard.tracker.courses.application.LireFicheCourse;
import fr.backyard.tracker.courses.application.ListerInscritsCourse;
import fr.backyard.tracker.courses.application.ListerCourses;
import fr.backyard.tracker.courses.domaine.Course;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Fiche d'administration d'une Course, affectation de ses bénévoles, lecture de ses inscrits et démarrage. Réservé à ADMIN et ADMIN_MASTER par la
 * politique de sécurité (/api/administration/**). Le journal ne contient que l'identifiant et un nombre.
 */
@RestController
@RequestMapping("/api/administration/courses/{id}")
public class FicheCourseController {

    private static final Logger JOURNAL = LoggerFactory.getLogger(FicheCourseController.class);

    private final LireFicheCourse lireFicheCourse;
    private final AffecterBenevoles affecterBenevoles;
    private final ListerCourses listerCourses;
    private final ListerInscritsCourse listerInscritsCourse;
    private final DemarrerCourse demarrerCourse;

    public FicheCourseController(LireFicheCourse lireFicheCourse, AffecterBenevoles affecterBenevoles,
                                 ListerCourses listerCourses, ListerInscritsCourse listerInscritsCourse,
                                 DemarrerCourse demarrerCourse) {
        this.lireFicheCourse = lireFicheCourse;
        this.affecterBenevoles = affecterBenevoles;
        this.listerCourses = listerCourses;
        this.listerInscritsCourse = listerInscritsCourse;
        this.demarrerCourse = demarrerCourse;
    }

    @GetMapping
    public FicheCourseReponse lireFiche(@PathVariable("id") String id) {
        return reponse(lireFicheCourse.executer(IdentifiantCourse.deCourse(id)));
    }

    /** Lecture seule : aucun journal (ni pseudo, ni identifiant de Compte). */
    @GetMapping("/inscriptions")
    public InscritsCourseReponse listerInscrits(@PathVariable("id") String id) {
        return InscritsCourseReponse.depuis(listerInscritsCourse.executer(IdentifiantCourse.deCourse(id)));
    }

    /** Le corps et son format sont contrôlés avant l'identifiant : 400 même pour une Course inexistante. */
    @PutMapping(path = "/benevoles", consumes = MediaType.APPLICATION_JSON_VALUE)
    public FicheCourseReponse affecterBenevoles(@PathVariable("id") String id,
                                                @RequestBody AffecterBenevolesRequete requete) {
        Course course = affecterBenevoles.executer(requete.versCommande(id));
        JOURNAL.info("Bénévoles de la course affectés (course {}, {} bénévoles)", course.id(),
                course.benevolesAffectes().size());
        return reponse(LireFicheCourse.Fiche.de(course));
    }

    /** Aucun corps lu : l'heure de départ vient uniquement de l'horloge de l'application. */
    @PostMapping("/demarrage")
    public FicheCourseReponse demarrer(@PathVariable("id") String id) {
        Course course = demarrerCourse.executer(IdentifiantCourse.deCourse(id));
        JOURNAL.info("Course démarrée (course {})", course.id());
        return reponse(LireFicheCourse.Fiche.de(course));
    }

    private FicheCourseReponse reponse(LireFicheCourse.Fiche fiche) {
        return FicheCourseReponse.depuis(listerCourses.avecEmpreinteLogo(fiche.course()), fiche.benevoleIds());
    }
}
