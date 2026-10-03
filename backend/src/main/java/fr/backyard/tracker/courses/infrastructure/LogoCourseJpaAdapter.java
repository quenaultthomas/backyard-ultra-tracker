package fr.backyard.tracker.courses.infrastructure;

import fr.backyard.tracker.courses.domaine.DepotLogos;
import fr.backyard.tracker.courses.domaine.Logo;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * Adaptateur JPA du dépôt de logos. Les empreintes se lisent sans charger les octets ; le remplacement est une seule
 * instruction (insertion ou mise à jour de la ligne de la Course) : deux envois simultanés laissent le dernier écrit.
 */
@Repository
public class LogoCourseJpaAdapter implements DepotLogos {

    private static final String REMPLACEMENT = """
            insert into logo_course (course_id, contenu, type_mime, taille_octets, empreinte)
            values (:idCourse, :contenu, :typeMime, :taille, :empreinte)
            on conflict (course_id) do update set contenu = excluded.contenu, type_mime = excluded.type_mime,
                taille_octets = excluded.taille_octets, empreinte = excluded.empreinte""";

    @PersistenceContext
    private EntityManager entityManager;

    @Override
    @Transactional
    public void enregistrer(UUID idCourse, Logo logo) {
        entityManager.createNativeQuery(REMPLACEMENT)
                .setParameter("idCourse", idCourse)
                .setParameter("contenu", logo.octets())
                .setParameter("typeMime", logo.typeMime())
                .setParameter("taille", logo.taille())
                .setParameter("empreinte", logo.empreinte())
                .executeUpdate();
    }

    @Override
    public Optional<Logo> parIdCourse(UUID idCourse) {
        return Optional.ofNullable(entityManager.find(LogoCourseJpaEntity.class, idCourse))
                .map(entite -> Logo.depuis(entite.contenu()));
    }

    @Override
    @Transactional
    public void supprimer(UUID idCourse) {
        entityManager.createQuery("delete from LogoCourseJpaEntity l where l.idCourse = :idCourse")
                .setParameter("idCourse", idCourse)
                .executeUpdate();
    }

    @Override
    public Map<UUID, String> empreintesParIdCourse() {
        return entityManager.createQuery("select l.idCourse, l.empreinte from LogoCourseJpaEntity l", Object[].class)
                .getResultStream()
                .collect(Collectors.toMap(ligne -> (UUID) ligne[0], ligne -> (String) ligne[1]));
    }

    @Override
    public Optional<String> empreinteParIdCourse(UUID idCourse) {
        return entityManager.createQuery(
                        "select l.empreinte from LogoCourseJpaEntity l where l.idCourse = :idCourse", String.class)
                .setParameter("idCourse", idCourse)
                .getResultStream()
                .findFirst();
    }
}
