package fr.backyard.tracker.comptes.domaine;

import java.util.Optional;
import java.util.UUID;

/**
 * Port sortant de persistance des comptes.
 *
 * <p>Les recherches ont une implémentation par défaut uniquement pour que les dépôts réduits à la
 * création (1.1) restent valides ; l'adaptateur de persistance les redéfinit toutes.
 */
public interface DepotComptes {

    boolean existeParPseudoNormalise(String pseudoNormalise);

    /**
     * Enregistre un nouveau compte.
     *
     * @throws PseudoDejaUtiliseException si le pseudo normalisé a été pris entre-temps
     */
    void enregistrer(Compte compte);

    /** @throws UnsupportedOperationException si le dépôt ne sait pas rechercher */
    default Optional<Compte> trouverParPseudoNormalise(String pseudoNormalise) {
        throw new UnsupportedOperationException("Ce dépôt ne sait pas rechercher par pseudo.");
    }

    /** @throws UnsupportedOperationException si le dépôt ne sait pas rechercher */
    default Optional<Compte> trouverParId(UUID id) {
        throw new UnsupportedOperationException("Ce dépôt ne sait pas rechercher par identifiant.");
    }
}
