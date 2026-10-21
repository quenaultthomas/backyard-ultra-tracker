package fr.backyard.tracker.courses.infrastructure;

import fr.backyard.tracker.courses.domaine.DepotInscriptions;
import fr.backyard.tracker.courses.domaine.Inscription;
import fr.backyard.tracker.courses.domaine.InscriptionDejaExistanteException;
import fr.backyard.tracker.courses.domaine.JetonQr;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.PersistenceException;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * Adaptateur JPA du dépôt d'Inscriptions. Il ne calcule aucun dossard ni aucune complétude : il fournit
 * seulement le plus grand dossard existant et le nombre d'Inscriptions.
 * La contrainte d'unicité (Course, Compte) est le filet ultime du contrôle de doublon, traduite en exception métier.
 */
@Repository
public class InscriptionJpaAdapter implements DepotInscriptions {

    static final String CONTRAINTE_COURSE_COMPTE = "uq_inscription_course_compte";

    @PersistenceContext
    private EntityManager entityManager;

    @Override
    @Transactional
    public void enregistrer(Inscription inscription) {
        try {
            entityManager.persist(versEntite(inscription));
            entityManager.flush();
        } catch (PersistenceException exception) {
            throw traduire(exception);
        }
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<Integer> plusGrandDossard(UUID courseId) {
        return Optional.ofNullable(entityManager.createQuery(
                        "select max(i.dossard) from InscriptionJpaEntity i where i.courseId = :courseId", Integer.class)
                .setParameter("courseId", courseId)
                .getSingleResult());
    }

    @Override
    @Transactional(readOnly = true)
    public boolean existePour(UUID courseId, UUID compteId) {
        return entityManager.createQuery("select count(i) from InscriptionJpaEntity i "
                        + "where i.courseId = :courseId and i.compteId = :compteId", Long.class)
                .setParameter("courseId", courseId)
                .setParameter("compteId", compteId)
                .getSingleResult() > 0;
    }

    @Override
    @Transactional(readOnly = true)
    public int nombreInscrits(UUID courseId) {
        return Math.toIntExact(entityManager.createQuery(
                        "select count(i) from InscriptionJpaEntity i where i.courseId = :courseId", Long.class)
                .setParameter("courseId", courseId)
                .getSingleResult());
    }

    /** Une requête groupée pour toutes les Courses (aucune pour une liste vide) ; 0 pour une Course sans Inscrit. */
    @Override
    @Transactional(readOnly = true)
    public Map<UUID, Integer> nombresInscritsParCourse(Collection<UUID> courseIds) {
        Map<UUID, Integer> nombres = new HashMap<>();
        courseIds.forEach(courseId -> nombres.put(courseId, 0));
        if (courseIds.isEmpty()) {
            return nombres;
        }
        entityManager.createQuery("select i.courseId, count(i) from InscriptionJpaEntity i "
                        + "where i.courseId in :courseIds group by i.courseId", Object[].class)
                .setParameter("courseIds", courseIds)
                .getResultList()
                .forEach(ligne -> nombres.put((UUID) ligne[0], Math.toIntExact((Long) ligne[1])));
        return nombres;
    }

    @Override
    @Transactional(readOnly = true)
    public List<Inscription> parCompte(UUID compteId) {
        return entityManager.createQuery("select i from InscriptionJpaEntity i where i.compteId = :compteId",
                        InscriptionJpaEntity.class)
                .setParameter("compteId", compteId)
                .getResultList().stream()
                .map(InscriptionJpaAdapter::versDomaine)
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<Inscription> parCourse(UUID courseId) {
        return entityManager.createQuery("select i from InscriptionJpaEntity i where i.courseId = :courseId",
                        InscriptionJpaEntity.class)
                .setParameter("courseId", courseId)
                .getResultList().stream()
                .map(InscriptionJpaAdapter::versDomaine)
                .toList();
    }

    /** Requête (et non find) : relue sous le verrou de la Course, elle voit une suppression concurrente validée. */
    @Override
    @Transactional(readOnly = true)
    public Optional<Inscription> parIdEtCompte(UUID inscriptionId, UUID compteId) {
        return entityManager.createQuery("select i from InscriptionJpaEntity i "
                        + "where i.id = :id and i.compteId = :compteId", InscriptionJpaEntity.class)
                .setParameter("id", inscriptionId)
                .setParameter("compteId", compteId)
                .getResultStream()
                .findFirst()
                .map(InscriptionJpaAdapter::versDomaine);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<Inscription> parIdEtCourse(UUID inscriptionId, UUID courseId) {
        return entityManager.createQuery("select i from InscriptionJpaEntity i "
                        + "where i.id = :id and i.courseId = :courseId", InscriptionJpaEntity.class)
                .setParameter("id", inscriptionId)
                .setParameter("courseId", courseId)
                .getResultStream()
                .findFirst()
                .map(InscriptionJpaAdapter::versDomaine);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<Inscription> parCourseEtJeton(UUID courseId, JetonQr jeton) {
        return entityManager.createQuery("select i from InscriptionJpaEntity i "
                        + "where i.courseId = :courseId and i.jetonQr = :jeton", InscriptionJpaEntity.class)
                .setParameter("courseId", courseId)
                .setParameter("jeton", jeton.valeur())
                .getResultStream()
                .findFirst()
                .map(InscriptionJpaAdapter::versDomaine);
    }

    /** Mise à jour de la ligne existante (statut, motif et Boucle d'abandon) ; une Inscription absente est une erreur. */
    @Override
    @Transactional
    public void mettreAJour(Inscription inscription) {
        InscriptionJpaEntity existante = entityManager.find(InscriptionJpaEntity.class, inscription.id());
        if (existante == null) {
            throw new IllegalStateException("Inscription introuvable pour mise à jour : " + inscription.id());
        }
        existante.remplacerAbandonPar(versEntite(inscription));
        entityManager.flush();
    }

    @Override
    @Transactional
    public void supprimer(UUID inscriptionId) {
        entityManager.createQuery("delete from InscriptionJpaEntity i where i.id = :id")
                .setParameter("id", inscriptionId)
                .executeUpdate();
    }

    /** Seule la violation de l'unicité (Course, Compte) est un doublon métier ; toute autre erreur est relancée. */
    private static RuntimeException traduire(PersistenceException exception) {
        return violeUniciteCourseCompte(exception) ? new InscriptionDejaExistanteException() : exception;
    }

    /** La violation peut être l'exception elle-même ou une de ses causes, selon la version d'Hibernate. */
    private static boolean violeUniciteCourseCompte(Throwable exception) {
        for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
            if (cause instanceof ConstraintViolationException violation) {
                return CONTRAINTE_COURSE_COMPTE.equalsIgnoreCase(violation.getConstraintName());
            }
        }
        return false;
    }

    private static InscriptionJpaEntity versEntite(Inscription inscription) {
        return new InscriptionJpaEntity(inscription.id(), inscription.courseId(), inscription.compteId(),
                inscription.dossard(), inscription.jetonQr().valeur(), inscription.statut(),
                inscription.motifAbandon().orElse(null), inscription.boucleAbandon().orElse(null));
    }

    private static Inscription versDomaine(InscriptionJpaEntity entite) {
        return Inscription.reconstituer(entite.id(), entite.courseId(), entite.compteId(), entite.dossard(),
                new JetonQr(entite.jetonQr()), entite.statut(), entite.motifAbandon(), entite.boucleAbandon());
    }
}
