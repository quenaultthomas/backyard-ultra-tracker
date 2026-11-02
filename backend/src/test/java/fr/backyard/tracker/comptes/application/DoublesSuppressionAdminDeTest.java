package fr.backyard.tracker.comptes.application;

import fr.backyard.tracker.comptes.domaine.Compte;
import fr.backyard.tracker.comptes.domaine.DepotComptes;
import fr.backyard.tracker.comptes.domaine.Role;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Doubles de 9.4 : dépôt de Comptes en mémoire qui verrouille par rôle (ordre croissant d'identifiant), lit un Compte
 * isolé sous verrou et sait supprimer, avec un journal commun pour vérifier l'ordre des opérations (RG8, RG9). Les
 * espions de sessions et de tentatives sont ceux de {@link DoublesSuppressionBenevoleDeTest}.
 */
final class DoublesSuppressionAdminDeTest {

    static final String CHARGEMENT_ADMINS = "chargement-admins-sous-verrou";

    private DoublesSuppressionAdminDeTest() {
    }

    static final class DepotAdmins implements DepotComptes {
        private final Map<UUID, Compte> comptes = new LinkedHashMap<>();
        private final List<String> journal;
        final List<UUID> suppressions = new ArrayList<>();
        RuntimeException echecSuppression;
        int misesAJour;

        DepotAdmins(List<String> journal) {
            this.journal = journal;
        }

        Compte ajouter(Compte compte) {
            comptes.put(compte.id(), compte);
            return compte;
        }

        boolean contient(UUID id) {
            return comptes.containsKey(id);
        }

        @Override
        public boolean existeParPseudoNormalise(String pseudoNormalise) {
            return comptes.values().stream().anyMatch(c -> c.pseudoNormalise().equals(pseudoNormalise));
        }

        @Override
        public void enregistrer(Compte compte) {
            ajouter(compte);
        }

        @Override
        public Optional<Compte> trouverParId(UUID id) {
            journal.add(DoublesSuppressionBenevoleDeTest.LECTURE_SIMPLE);
            return Optional.ofNullable(comptes.get(id));
        }

        @Override
        public Optional<Compte> trouverParIdPourModification(UUID id) {
            journal.add(DoublesSuppressionBenevoleDeTest.CHARGEMENT_COMPTE);
            return Optional.ofNullable(comptes.get(id));
        }

        @Override
        public List<Compte> trouverParRolePourModification(Role role) {
            journal.add(CHARGEMENT_ADMINS);
            return comptes.values().stream()
                    .filter(c -> c.role() == role)
                    .sorted(Comparator.comparing(Compte::id))
                    .toList();
        }

        @Override
        public void mettreAJour(Compte compte) {
            journal.add(DoublesSuppressionBenevoleDeTest.MISE_A_JOUR);
            misesAJour++;
            comptes.put(compte.id(), compte);
        }

        @Override
        public void supprimer(UUID id) {
            journal.add(DoublesSuppressionBenevoleDeTest.SUPPRESSION);
            if (echecSuppression != null) {
                throw echecSuppression;
            }
            suppressions.add(id);
            comptes.remove(id);
        }
    }
}
