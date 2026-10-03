package fr.backyard.tracker.courses.infrastructure;

import fr.backyard.tracker.courses.domaine.Course;
import fr.backyard.tracker.courses.domaine.DepotCourses;
import fr.backyard.tracker.courses.domaine.ParametresBoucle;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.util.List;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/** Adaptateur JPA du dépôt de Courses. Ne trie pas : l'ordre est appliqué par le domaine. */
@Repository
public class CourseJpaAdapter implements DepotCourses {

    @PersistenceContext
    private EntityManager entityManager;

    @Override
    @Transactional
    public void enregistrer(Course course) {
        entityManager.persist(versEntite(course));
        entityManager.flush();
    }

    @Override
    public List<Course> toutes() {
        return entityManager.createQuery("select c from CourseJpaEntity c", CourseJpaEntity.class)
                .getResultStream()
                .map(CourseJpaAdapter::versDomaine)
                .toList();
    }

    private static CourseJpaEntity versEntite(Course course) {
        ParametresBoucle boucle = course.parametresBoucle();
        return new CourseJpaEntity(course.id(), course.nom(), course.date(), course.statut(),
                boucle.distanceMetres(), boucle.dureeMinutes(), boucle.denivelePositifMetres(),
                course.nombreMaxParticipants(), course.nombreMaxBoucles());
    }

    private static Course versDomaine(CourseJpaEntity entite) {
        return Course.reconstituer(entite.id(), entite.nom(), entite.date(), entite.statut(),
                new ParametresBoucle(entite.distanceBoucleMetres(), entite.dureeBoucleMinutes(),
                        entite.denivelePositifBoucleMetres()),
                entite.nombreMaxParticipants(), entite.nombreMaxBoucles());
    }
}
