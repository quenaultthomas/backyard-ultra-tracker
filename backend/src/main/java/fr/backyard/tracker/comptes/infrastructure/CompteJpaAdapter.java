package fr.backyard.tracker.comptes.infrastructure;

import fr.backyard.tracker.comptes.domaine.Compte;
import fr.backyard.tracker.comptes.domaine.DepotComptes;
import fr.backyard.tracker.comptes.domaine.PseudoDejaUtiliseException;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.util.UUID;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.stereotype.Repository;

/** Adaptateur JPA du dépôt de comptes. */
@Repository
public class CompteJpaAdapter implements DepotComptes {

    static final String CONTRAINTE_UNICITE_PSEUDO = "uk_compte_pseudo_normalise";

    @PersistenceContext
    private EntityManager entityManager;

    @Override
    public boolean existeParPseudoNormalise(String pseudoNormalise) {
        return !entityManager
                .createQuery("select c.id from CompteJpaEntity c where c.pseudoNormalise = :pseudoNormalise",
                        UUID.class)
                .setParameter("pseudoNormalise", pseudoNormalise)
                .setMaxResults(1)
                .getResultList()
                .isEmpty();
    }

    /** Flush immédiat : un doublon concurrent est détecté ici par la contrainte d'unicité et traduit en 409. */
    @Override
    public void enregistrer(Compte compte) {
        try {
            entityManager.persist(versEntite(compte));
            entityManager.flush();
        } catch (ConstraintViolationException exception) {
            if (CONTRAINTE_UNICITE_PSEUDO.equalsIgnoreCase(exception.getConstraintName())) {
                throw new PseudoDejaUtiliseException(exception);
            }
            throw exception;
        }
    }

    private static CompteJpaEntity versEntite(Compte compte) {
        return new CompteJpaEntity(compte.id(), compte.pseudo().valeur(), compte.pseudoNormalise(),
                compte.empreinteMotDePasse(), compte.role(), compte.creeLe());
    }
}
