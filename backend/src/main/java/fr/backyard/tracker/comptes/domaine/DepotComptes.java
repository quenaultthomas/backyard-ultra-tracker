package fr.backyard.tracker.comptes.domaine;

import java.util.Collection;
import java.util.List;
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
     * @throws PseudoDejaUtiliseException      si le pseudo normalisé a été pris entre-temps
     * @throws AdminMasterDejaPresentException si un autre admin master a été enregistré entre-temps
     */
    void enregistrer(Compte compte);

    /**
     * Vrai si un compte de rôle {@link Role#ADMIN_MASTER} existe, quel que soit son pseudo.
     *
     * @throws UnsupportedOperationException si le dépôt ne sait pas rechercher
     */
    default boolean existeAdminMaster() {
        throw new UnsupportedOperationException("Ce dépôt ne sait pas rechercher l'admin master.");
    }

    /** @throws UnsupportedOperationException si le dépôt ne sait pas rechercher */
    default Optional<Compte> trouverParPseudoNormalise(String pseudoNormalise) {
        throw new UnsupportedOperationException("Ce dépôt ne sait pas rechercher par pseudo.");
    }

    /** @throws UnsupportedOperationException si le dépôt ne sait pas rechercher */
    default Optional<Compte> trouverParId(UUID id) {
        throw new UnsupportedOperationException("Ce dépôt ne sait pas rechercher par identifiant.");
    }

    /**
     * Comptes trouvés parmi ces identifiants, en une seule recherche, dans un ordre quelconque ; les identifiants
     * inconnus sont ignorés.
     *
     * @throws UnsupportedOperationException si le dépôt ne sait pas rechercher
     */
    default List<Compte> trouverParIds(Collection<UUID> ids) {
        throw new UnsupportedOperationException("Ce dépôt ne sait pas rechercher par identifiants.");
    }

    /**
     * Enregistre les modifications d'un compte existant (empreinte du mot de passe).
     *
     * @throws CompteIntrouvableOuInutilisableException si le compte n'existe plus
     * @throws UnsupportedOperationException            si le dépôt ne sait pas mettre à jour
     */
    default void mettreAJour(Compte compte) {
        throw new UnsupportedOperationException("Ce dépôt ne sait pas mettre à jour un compte.");
    }

    /**
     * Comptes du rôle donné, dans un ordre quelconque (l'ordre d'affichage est une règle des cas d'usage).
     *
     * @throws UnsupportedOperationException si le dépôt ne sait pas rechercher
     */
    default List<Compte> listerParRole(Role role) {
        throw new UnsupportedOperationException("Ce dépôt ne sait pas lister par rôle.");
    }
}
