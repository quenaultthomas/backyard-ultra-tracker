package fr.backyard.tracker.comptes.application;

import fr.backyard.tracker.comptes.domaine.Compte;
import fr.backyard.tracker.comptes.domaine.DepotComptes;
import fr.backyard.tracker.comptes.domaine.Role;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Double en mémoire du port DepotComptes (8.1) : inscrit dans un journal commun les chargements sous verrou, les
 * lectures simples (interdites pour une modification) et les mises à jour, pour vérifier l'ordre des appels.
 */
final class DepotComptesVerrouilleDeTest implements DepotComptes {

    static final String CHARGEMENT_COMPTE = "chargement-compte-sous-verrou";
    static final String CHARGEMENT_ADMINS = "chargement-admins-sous-verrou";
    static final String LECTURE_SIMPLE = "lecture-simple";
    static final String MISE_A_JOUR = "mise-a-jour";

    private final Map<UUID, Compte> comptes = new LinkedHashMap<>();
    private final List<String> journal;
    RuntimeException echecMiseAJour;
    int misesAJour;

    DepotComptesVerrouilleDeTest(List<String> journal) {
        this.journal = journal;
    }

    Compte ajouter(Compte compte) {
        comptes.put(compte.id(), compte);
        return compte;
    }

    Compte enDepot(UUID id) {
        return comptes.get(id);
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
        journal.add(LECTURE_SIMPLE);
        return Optional.ofNullable(comptes.get(id));
    }

    @Override
    public Optional<Compte> trouverParIdPourModification(UUID id) {
        journal.add(CHARGEMENT_COMPTE);
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
        journal.add(MISE_A_JOUR);
        if (echecMiseAJour != null) {
            throw echecMiseAJour;
        }
        misesAJour++;
        comptes.put(compte.id(), compte);
    }
}
