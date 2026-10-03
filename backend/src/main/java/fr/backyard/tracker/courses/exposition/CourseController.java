package fr.backyard.tracker.courses.exposition;

import fr.backyard.tracker.courses.application.DeclarerCourse;
import fr.backyard.tracker.courses.application.ListerCourses;
import fr.backyard.tracker.courses.domaine.Course;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
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

    public CourseController(DeclarerCourse declarerCourse, ListerCourses listerCourses) {
        this.declarerCourse = declarerCourse;
        this.listerCourses = listerCourses;
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public CourseReponse declarerCourse(@RequestBody DeclarerCourseRequete requete) {
        Course course = declarerCourse.executer(requete.versCommande());
        JOURNAL.info("Course déclarée (course {})", course.id());
        return CourseReponse.depuis(course);
    }

    @GetMapping
    public List<CourseReponse> listerCourses() {
        return listerCourses.executer().stream().map(CourseReponse::depuis).toList();
    }
}
