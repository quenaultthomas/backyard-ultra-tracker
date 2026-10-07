package fr.backyard.tracker.courses.application;

import fr.backyard.tracker.courses.domaine.DepotInscriptions;
import fr.backyard.tracker.courses.domaine.Inscription;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Double en mémoire du port DepotInscriptions pour les tests de 3.1 et 3.2. */
final class DepotInscriptionsEnMemoireDeTest implements DepotInscriptions {

    final List<Inscription> inscriptions = new ArrayList<>();

    @Override
    public void enregistrer(Inscription inscription) {
        inscriptions.add(inscription);
    }

    @Override
    public Optional<Integer> plusGrandDossard(UUID courseId) {
        return inscriptions.stream().filter(i -> i.courseId().equals(courseId)).map(Inscription::dossard)
                .max(Integer::compareTo);
    }

    @Override
    public boolean existePour(UUID courseId, UUID compteId) {
        return inscriptions.stream().anyMatch(i -> i.courseId().equals(courseId) && i.compteId().equals(compteId));
    }

    /** Compte toutes les Inscriptions de la Course, quel que soit leur statut (3.2 RG3) ; ne compare à aucun maximum. */
    @Override
    public int nombreInscrits(UUID courseId) {
        return (int) inscriptions.stream().filter(i -> i.courseId().equals(courseId)).count();
    }

    @Override
    public List<Inscription> parCompte(UUID compteId) {
        return inscriptions.stream().filter(i -> i.compteId().equals(compteId)).toList();
    }

    /** 3.4 RG3 : recherche par (identifiant, Compte) ; l'Inscription d'autrui n'est jamais renvoyée. */
    @Override
    public Optional<Inscription> parIdEtCompte(UUID inscriptionId, UUID compteId) {
        return inscriptions.stream().filter(i -> i.id().equals(inscriptionId) && i.compteId().equals(compteId))
                .findFirst();
    }

    /** 3.4 RG1 : suppression définitive. */
    @Override
    public void supprimer(UUID inscriptionId) {
        inscriptions.removeIf(i -> i.id().equals(inscriptionId));
    }
}
