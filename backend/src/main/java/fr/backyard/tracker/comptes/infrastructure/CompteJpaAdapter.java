package fr.backyard.tracker.comptes.infrastructure;

import fr.backyard.tracker.comptes.domaine.AdminMasterDejaPresentException;
import fr.backyard.tracker.comptes.domaine.Compte;
import fr.backyard.tracker.comptes.domaine.DepotComptes;
import fr.backyard.tracker.comptes.domaine.Pseudo;
import fr.backyard.tracker.comptes.domaine.PseudoDejaUtiliseException;
import fr.backyard.tracker.comptes.domaine.Role;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.util.Optional;
import java.util.UUID;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/** Adaptateur JPA du dépôt de comptes. */
@Repository
public class CompteJpaAdapter implements DepotComptes {

    static final String CONTRAINTE_UNICITE_PSEUDO = "uk_compte_pseudo_normalise";
    static final String CONTRAINTE_UNICITE_ADMIN_MASTER = "uk_compte_admin_master";

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

    @Override
    public boolean existeAdminMaster() {
        return !entityManager
                .createQuery("select c.id from CompteJpaEntity c where c.role = :role", UUID.class)
                .setParameter("role", Role.ADMIN_MASTER)
                .setMaxResults(1)
                .getResultList()
                .isEmpty();
    }

    @Override
    public Optional<Compte> trouverParPseudoNormalise(String pseudoNormalise) {
        return entityManager
                .createQuery("select c from CompteJpaEntity c where c.pseudoNormalise = :pseudoNormalise",
                        CompteJpaEntity.class)
                .setParameter("pseudoNormalise", pseudoNormalise)
                .getResultStream()
                .findFirst()
                .map(CompteJpaAdapter::versDomaine);
    }

    @Override
    public Optional<Compte> trouverParId(UUID id) {
        return Optional.ofNullable(entityManager.find(CompteJpaEntity.class, id)).map(CompteJpaAdapter::versDomaine);
    }

    /**
     * Flush immédiat : un doublon concurrent est détecté ici par une contrainte d'unicité et traduit en
     * exception métier. Rejoint la transaction de l'appelant, ou en ouvre une s'il n'en a pas (initialisation
     * de l'admin master), annulée en cas de conflit.
     */
    @Override
    @Transactional
    public void enregistrer(Compte compte) {
        try {
            entityManager.persist(versEntite(compte));
            entityManager.flush();
        } catch (ConstraintViolationException exception) {
            throw traduire(exception);
        }
    }

    private static RuntimeException traduire(ConstraintViolationException exception) {
        String contrainte = exception.getConstraintName();
        if (CONTRAINTE_UNICITE_PSEUDO.equalsIgnoreCase(contrainte)) {
            return new PseudoDejaUtiliseException(exception);
        }
        if (CONTRAINTE_UNICITE_ADMIN_MASTER.equalsIgnoreCase(contrainte)) {
            return new AdminMasterDejaPresentException(exception);
        }
        return exception;
    }

    private static CompteJpaEntity versEntite(Compte compte) {
        return new CompteJpaEntity(compte.id(), compte.pseudo().valeur(), compte.pseudoNormalise(),
                compte.empreinteMotDePasse(), compte.role(), compte.creeLe());
    }

    private static Compte versDomaine(CompteJpaEntity entite) {
        return Compte.reconstituer(entite.id(), new Pseudo(entite.pseudo()), entite.empreinteMotDePasse(),
                entite.role(), entite.creeLe());
    }
}
