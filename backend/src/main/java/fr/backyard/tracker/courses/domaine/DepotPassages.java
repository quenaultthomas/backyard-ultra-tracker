package fr.backyard.tracker.courses.domaine;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Port sortant de persistance des Passages : ajout et lecture uniquement (un Passage n'est jamais modifié). */
public interface DepotPassages {

    /** Passage de cet identifiant (identifiant de scan), quelle que soit son Inscription. */
    Optional<Passage> parId(UUID id);

    /** L'Inscription a un Passage, quelle que soit son origine, sur cette Boucle. */
    boolean existe(UUID inscriptionId, int numeroBoucle);

    /** Passage de l'Inscription sur cette Boucle, quelle que soit son origine. */
    Optional<Passage> parInscriptionEtBoucle(UUID inscriptionId, int numeroBoucle);

    /** Passages de toutes ces Inscriptions, en une seule lecture groupée (aucune lecture sans Inscription). */
    List<Passage> parInscriptions(Collection<UUID> inscriptionIds);

    /**
     * Ajoute un nouveau Passage.
     *
     * @throws PassageDejaEnregistreException l'Inscription a déjà un Passage sur cette Boucle (scan concurrent)
     */
    void enregistrer(Passage passage);
}
