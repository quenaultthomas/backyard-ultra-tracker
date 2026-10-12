package fr.backyard.tracker.courses.domaine;

import java.util.Optional;
import java.util.UUID;

/** Port sortant de persistance des Passages : ajout et lecture uniquement (un Passage n'est jamais modifié). */
public interface DepotPassages {

    /** Passage de cet identifiant (identifiant de scan), quelle que soit son Inscription. */
    Optional<Passage> parId(UUID id);

    /** L'Inscription a un Passage, quelle que soit son origine, sur cette Boucle. */
    boolean existe(UUID inscriptionId, int numeroBoucle);

    /**
     * Ajoute un nouveau Passage.
     *
     * @throws PassageDejaEnregistreException l'Inscription a déjà un Passage sur cette Boucle (scan concurrent)
     */
    void enregistrer(Passage passage);
}
