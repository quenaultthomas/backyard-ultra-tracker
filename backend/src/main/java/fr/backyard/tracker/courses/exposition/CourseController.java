package fr.backyard.tracker.courses.exposition;

import fr.backyard.tracker.courses.application.DeclarerCourse;
import fr.backyard.tracker.courses.application.ListerCourses;
import fr.backyard.tracker.courses.application.ModifierCourse;
import fr.backyard.tracker.courses.domaine.Course;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Gestion des Courses. Réservé à ADMIN et ADMIN_MASTER par la politique de sécurité (/api/administration/**),
 * avant ce contrôleur. Le journal ne contient que des identifiants, jamais le corps de la requête.
 */
@RestController
@RequestMapping("/api/administration/courses")
public class CourseController {

    private static final Logger JOURNAL = LoggerFactory.getLogger(CourseController.class);

    private final DeclarerCourse declarerCourse;
    private final ListerCourses listerCourses;
    private final ModifierCourse modifierCourse;

    public CourseController(DeclarerCourse declarerCourse, ListerCourses listerCourses,
                            ModifierCourse modifierCourse) {
        this.declarerCourse = declarerCourse;
        this.listerCourses = listerCourses;
        this.modifierCourse = modifierCourse;
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public CourseReponse declarerCourse(@RequestBody CourseRequete requete) {
        Course course = declarerCourse.executer(requete.versCommandeDeDeclaration());
        JOURNAL.info("Course déclarée (course {})", course.id());
        return CourseReponse.depuis(course, Optional.empty());
    }

    @GetMapping
    public List<CourseReponse> listerCourses() {
        return listerCourses.executerAvecLogos().stream().map(CourseReponse::depuis).toList();
    }

    /** Le corps est lu avant l'identifiant : un corps illisible donne 400 même pour une Course inexistante. */
    @PutMapping(path = "/{id}", consumes = MediaType.APPLICATION_JSON_VALUE)
    public CourseReponse modifierCourse(@PathVariable("id") String id, @RequestBody CourseRequete requete) {
        Course course = modifierCourse.executer(requete.versCommandeDeModification(IdentifiantCourse.deCourse(id)));
        JOURNAL.info("Course modifiée (course {})", course.id());
        return CourseReponse.depuis(listerCourses.avecEmpreinteLogo(course));
    }
}
