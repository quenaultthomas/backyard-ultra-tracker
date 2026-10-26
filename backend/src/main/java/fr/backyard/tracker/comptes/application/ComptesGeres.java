package fr.backyard.tracker.comptes.application;

import fr.backyard.tracker.comptes.domaine.Compte;
import fr.backyard.tracker.comptes.domaine.CompteNonGerableException;
import fr.backyard.tracker.comptes.domaine.Role;
import java.util.Optional;
import java.util.Set;

/**
 * Comptes visés par chaque route de gestion (8.1 RG2) : la route admins agit sur un ADMIN (et signale l'admin master
 * par le refus du domaine), la route bénévoles sur un BENEVOLE. Tout autre Compte, ou aucun, est introuvable.
 */
enum ComptesGeres {
    ADMINS(Set.of(Role.ADMIN, Role.ADMIN_MASTER)),
    BENEVOLES(Set.of(Role.BENEVOLE));

    private final Set<Role> roles;

    ComptesGeres(Set<Role> roles) {
        this.roles = roles;
    }

    /** @throws CompteNonGerableException Compte absent ou d'un rôle non géré par cette route */
    Compte cible(Optional<Compte> compte) {
        return compte.filter(trouve -> roles.contains(trouve.role())).orElseThrow(CompteNonGerableException::new);
    }
}
