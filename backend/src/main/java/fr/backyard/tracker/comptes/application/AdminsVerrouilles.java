package fr.backyard.tracker.comptes.application;

import fr.backyard.tracker.comptes.domaine.Compte;
import fr.backyard.tracker.comptes.domaine.CompteNonGerableException;
import fr.backyard.tracker.comptes.domaine.DepotComptes;
import fr.backyard.tracker.comptes.domaine.Role;
import java.util.List;
import java.util.UUID;

/**
 * Lecture commune à la désactivation et à la suppression d'un admin (8.1 RG11, 9.4 RG8) : tous les Comptes ADMIN sont
 * relus sous verrou (ordre croissant d'identifiant) avant toute décision, ce qui sérialise les modifications
 * concurrentes ; la cible est prise dans cette liste, sinon relue sous verrou par identifiant (admin master, ou Compte
 * d'un autre rôle).
 *
 * @param admins Comptes de rôle ADMIN verrouillés
 * @param cible  Compte visé, de rôle ADMIN ou ADMIN_MASTER
 */
record AdminsVerrouilles(List<Compte> admins, Compte cible) {

    /** @throws CompteNonGerableException aucun Compte ADMIN ni ADMIN_MASTER pour cet identifiant */
    static AdminsVerrouilles charger(DepotComptes depotComptes, UUID cibleId) {
        List<Compte> admins = depotComptes.trouverParRolePourModification(Role.ADMIN);
        Compte cible = ComptesGeres.ADMINS.cible(admins.stream()
                .filter(admin -> admin.id().equals(cibleId))
                .findFirst()
                .or(() -> depotComptes.trouverParIdPourModification(cibleId)));
        return new AdminsVerrouilles(admins, cible);
    }
}
