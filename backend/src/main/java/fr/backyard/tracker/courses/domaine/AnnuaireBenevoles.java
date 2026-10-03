package fr.backyard.tracker.courses.domaine;

import java.util.Collection;
import java.util.UUID;

/**
 * Port sortant : savoir si un identifiant de Compte désigne un bénévole, sans que le contexte courses connaisse
 * les Comptes.
 */
public interface AnnuaireBenevoles {

    /** Vrai si un Compte de rôle BENEVOLE existe pour cet identifiant. */
    boolean estBenevole(UUID idCompte);

    /** @throws BenevoleInconnuException au moins un identifiant ne désigne pas un bénévole */
    default void verifierBenevoles(Collection<UUID> idsComptes) {
        if (!idsComptes.stream().distinct().allMatch(this::estBenevole)) {
            throw new BenevoleInconnuException();
        }
    }
}
