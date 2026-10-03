package fr.backyard.tracker.courses.infrastructure;

import fr.backyard.tracker.courses.domaine.Course;
import fr.backyard.tracker.courses.domaine.DepotCourses;
import fr.backyard.tracker.courses.domaine.ParametresBoucle;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * Adaptateur JPA du dépôt de Courses. Ne trie pas : l'ordre est appliqué par le domaine. Une Course déjà enregistrée
 * est mise à jour sur sa ligne, jamais insérée une seconde fois.
 */
@Repository
public class CourseJpaAdapter implements DepotCourses {

    @PersistenceContext
    private EntityManager entityManager;

    @Override
    @Transactional
    public void enregistrer(Course course) {
        CourseJpaEntity existante = entityManager.find(CourseJpaEntity.class, course.id());
        if (existante == null) {
            entityManager.persist(versEntite(course));
        } else {
            existante.remplacerPar(versEntite(course));
        }
        entityManager.flush();
    }

    @Override
    public Optional<Course> parId(UUID id) {
        return Optional.ofNullable(entityManager.find(CourseJpaEntity.class, id)).map(CourseJpaAdapter::versDomaine);
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
