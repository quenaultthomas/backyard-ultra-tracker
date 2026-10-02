package fr.backyard.tracker.comptes.domaine;

/** Port sortant de persistance des comptes. */
public interface DepotComptes {

    boolean existeParPseudoNormalise(String pseudoNormalise);

    /**
     * Enregistre un nouveau compte.
     *
     * @throws PseudoDejaUtiliseException si le pseudo normalisé a été pris entre-temps
     */
    void enregistrer(Compte compte);
}
