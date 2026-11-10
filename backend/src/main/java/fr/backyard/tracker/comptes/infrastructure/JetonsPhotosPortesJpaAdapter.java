package fr.backyard.tracker.comptes.infrastructure;

import fr.backyard.tracker.comptes.domaine.JetonsPhotosPortes;
import fr.backyard.tracker.partage.domaine.FormatImage;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.Tuple;
import java.util.Collection;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * Jetons de photo portés par un Compte (correctif audit jalon 10 RG4) : une seule instruction SQL pour tous les jetons
 * demandés, par l'index d'unicité {@code uk_compte_photo_jeton}. Lecture seule.
 */
@Repository
public class JetonsPhotosPortesJpaAdapter implements JetonsPhotosPortes {

    @PersistenceContext
    private EntityManager entityManager;

    @Override
    @Transactional(readOnly = true)
    public Map<String, FormatImage> portesParmi(Collection<String> jetons) {
        if (jetons.isEmpty()) {
            return Map.of();
        }
        return entityManager.createQuery("select c.photoJeton as jeton, c.photoFormat as format "
                        + "from CompteJpaEntity c where c.photoJeton in :jetons", Tuple.class)
                .setParameter("jetons", jetons)
                .getResultStream()
                .collect(Collectors.toUnmodifiableMap(ligne -> ligne.get("jeton", String.class),
                        ligne -> ligne.get("format", FormatImage.class)));
    }
}
