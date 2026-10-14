package fr.backyard.tracker.courses.infrastructure;

import fr.backyard.tracker.courses.domaine.DepotPassages;
import fr.backyard.tracker.courses.domaine.Passage;
import fr.backyard.tracker.courses.domaine.PassageDejaEnregistreException;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.hibernate.Session;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * Adaptateur JPA du dépôt de Passages : insertion et lecture, jamais de modification ni de suppression. L'insertion
 * est immédiate, dans la transaction du cas d'usage : le doublon (Inscription, Boucle) d'un scan concurrent, garanti
 * par la contrainte uq_passage_inscription_boucle, devient une exception métier, jamais une erreur interne.
 */
@Repository
public class PassageJpaAdapter implements DepotPassages {

    @PersistenceContext
    private EntityManager entityManager;

    @Override
    @Transactional(readOnly = true)
    public Optional<Passage> parId(UUID id) {
        return entityManager.createQuery("select p from PassageJpaEntity p where p.id = :id", PassageJpaEntity.class)
                .setParameter("id", id)
                .getResultStream()
                .findFirst()
                .map(PassageJpaAdapter::versDomaine);
    }

    @Override
    @Transactional(readOnly = true)
    public boolean existe(UUID inscriptionId, int numeroBoucle) {
        return entityManager.createQuery("select count(p) from PassageJpaEntity p "
                        + "where p.inscriptionId = :inscriptionId and p.numeroBoucle = :numeroBoucle", Long.class)
                .setParameter("inscriptionId", inscriptionId)
                .setParameter("numeroBoucle", numeroBoucle)
                .getSingleResult() > 0;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<Passage> parInscriptionEtBoucle(UUID inscriptionId, int numeroBoucle) {
        return entityManager.createQuery("select p from PassageJpaEntity p "
                        + "where p.inscriptionId = :inscriptionId and p.numeroBoucle = :numeroBoucle",
                        PassageJpaEntity.class)
                .setParameter("inscriptionId", inscriptionId)
                .setParameter("numeroBoucle", numeroBoucle)
                .getResultStream()
                .findFirst()
                .map(PassageJpaAdapter::versDomaine);
    }

    /**
     * Insertion sans erreur SQL sur un doublon ({@code on conflict do nothing}) : la transaction reste utilisable et
     * aucune erreur n'est journalisée. Aucune ligne insérée signifie qu'un Passage existe déjà pour (Inscription,
     * Boucle), ou pour cet identifiant (envoi simultané du même scan). Les autres contraintes lèvent leur erreur.
     */
    @Override
    @Transactional
    public void enregistrer(Passage passage) {
        int lignesInserees = entityManager.unwrap(Session.class).createNativeMutationQuery("""
                        insert into passage (id, inscription_id, numero_boucle, scanne_le, origine)
                        values (:id, :inscriptionId, :numeroBoucle, :scanneLe, :origine)
                        on conflict do nothing""")
                .setParameter("id", passage.id(), UUID.class)
                .setParameter("inscriptionId", passage.inscriptionId(), UUID.class)
                .setParameter("numeroBoucle", passage.numeroBoucle(), Integer.class)
                .setParameter("scanneLe", passage.scanneLe().orElse(null), Instant.class)
                .setParameter("origine", passage.origine().name(), String.class)
                .executeUpdate();
        if (lignesInserees == 0) {
            throw new PassageDejaEnregistreException(passage.numeroBoucle());
        }
    }

    private static Passage versDomaine(PassageJpaEntity entite) {
        return Passage.reconstituer(entite.id(), entite.inscriptionId(), entite.numeroBoucle(), entite.scanneLe(),
                entite.origine());
    }
}
