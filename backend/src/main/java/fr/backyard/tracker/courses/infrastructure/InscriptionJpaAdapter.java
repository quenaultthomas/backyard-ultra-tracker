package fr.backyard.tracker.courses.infrastructure;

import fr.backyard.tracker.courses.domaine.DepotInscriptions;
import fr.backyard.tracker.courses.domaine.Inscription;
import fr.backyard.tracker.courses.domaine.InscriptionDejaExistanteException;
import fr.backyard.tracker.courses.domaine.JetonQr;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.PersistenceException;
import java.util.List;
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
                inscription.dossard(), inscription.jetonQr().valeur(), inscription.statut());
    }

    private static Inscription versDomaine(InscriptionJpaEntity entite) {
        return Inscription.reconstituer(entite.id(), entite.courseId(), entite.compteId(), entite.dossard(),
                new JetonQr(entite.jetonQr()), entite.statut());
    }
}
