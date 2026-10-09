package fr.backyard.tracker.courses.infrastructure;

import fr.backyard.tracker.courses.domaine.Course;
import fr.backyard.tracker.courses.domaine.DepotCourses;
import fr.backyard.tracker.courses.domaine.ParametresBoucle;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import jakarta.persistence.PersistenceContext;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * Adaptateur JPA du dépôt de Courses, bénévoles affectés compris. Ne trie pas : l'ordre est appliqué par le
 * domaine. Une Course déjà enregistrée est mise à jour sur sa ligne, jamais insérée une seconde fois.
 */
@Repository
public class CourseJpaAdapter implements DepotCourses {

    private static final String COURSES_ET_BENEVOLES =
            "select c from CourseJpaEntity c left join fetch c.benevolesAffectes";

    /**
     * Filtre par sous-requête sur les identifiants : la jointure chargée reste complète, la Course remonte avec
     * tous ses bénévoles affectés et pas seulement celui recherché.
     */
    private static final String COURSES_DU_BENEVOLE = COURSES_ET_BENEVOLES
            + " where c.id in (select a.id from CourseJpaEntity a join a.benevolesAffectes b where b = :idBenevole)";

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
    @Transactional(readOnly = true)
    public Optional<Course> parId(UUID id) {
        return Optional.ofNullable(entityManager.find(CourseJpaEntity.class, id)).map(CourseJpaAdapter::versDomaine);
    }

    /** Verrou exclusif sur la ligne course jusqu'à la fin de la transaction, posé avant de lire les affectations. */
    @Override
    @Transactional
    public Optional<Course> parIdPourModification(UUID id) {
        return Optional.ofNullable(entityManager.find(CourseJpaEntity.class, id, LockModeType.PESSIMISTIC_WRITE))
                .map(CourseJpaAdapter::versDomaine);
    }

    /**
     * Supprime la ligne course ; la base supprime son logo et ses affectations (on delete cascade). Une Course déjà
     * absente ne supprime rien.
     */
    @Override
    @Transactional
    public void supprimer(UUID id) {
        CourseJpaEntity entite = entityManager.find(CourseJpaEntity.class, id);
        if (entite != null) {
            entityManager.remove(entite);
            entityManager.flush();
        }
    }

    @Override
    @Transactional(readOnly = true)
    public List<Course> toutes() {
        return versDomaine(entityManager.createQuery(COURSES_ET_BENEVOLES, CourseJpaEntity.class).getResultList());
    }

    @Override
    @Transactional(readOnly = true)
    public List<Course> parBenevole(UUID idBenevole) {
        return versDomaine(entityManager.createQuery(COURSES_DU_BENEVOLE, CourseJpaEntity.class)
                .setParameter("idBenevole", idBenevole)
                .getResultList());
    }

    /** La jointure chargée renvoie une ligne par bénévole affecté : une seule Course par identifiant, ordre conservé. */
    private static List<Course> versDomaine(List<CourseJpaEntity> lignes) {
        Map<UUID, CourseJpaEntity> parIdentifiant = new LinkedHashMap<>();
        lignes.forEach(entite -> parIdentifiant.putIfAbsent(entite.id(), entite));
        return parIdentifiant.values().stream().map(CourseJpaAdapter::versDomaine).toList();
    }

    private static CourseJpaEntity versEntite(Course course) {
        ParametresBoucle boucle = course.parametresBoucle();
        return new CourseJpaEntity(course.id(), course.nom(), course.date(), course.statut(),
                boucle.distanceMetres(), boucle.dureeMinutes(), boucle.denivelePositifMetres(),
                course.nombreMaxParticipants(), course.nombreMaxBoucles(), course.benevolesAffectes(),
                course.demarreeLe().orElse(null));
    }

    private static Course versDomaine(CourseJpaEntity entite) {
        return Course.reconstituer(entite.id(), entite.nom(), entite.date(), entite.statut(),
                new ParametresBoucle(entite.distanceBoucleMetres(), entite.dureeBoucleMinutes(),
                        entite.denivelePositifBoucleMetres()),
                entite.nombreMaxParticipants(), entite.nombreMaxBoucles(), entite.benevolesAffectes(),
                entite.demarreeLe());
    }
}
