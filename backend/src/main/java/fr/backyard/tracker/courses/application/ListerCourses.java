package fr.backyard.tracker.courses.application;

import fr.backyard.tracker.courses.domaine.Course;
import fr.backyard.tracker.courses.domaine.DepotCourses;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Liste de toutes les Courses, tous statuts confondus, dans l'ordre défini par le domaine. */
@Service
public class ListerCourses {

    private final DepotCourses depotCourses;

    public ListerCourses(DepotCourses depotCourses) {
        this.depotCourses = depotCourses;
    }

    @Transactional(readOnly = true)
    public List<Course> executer() {
        return depotCourses.toutes().stream().sorted(Course.ORDRE_DE_LISTE).toList();
    }
}
