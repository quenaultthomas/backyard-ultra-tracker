package fr.backyard.tracker.courses.application;

import fr.backyard.tracker.courses.domaine.DepotInscriptions;
import fr.backyard.tracker.courses.domaine.Inscription;
import fr.backyard.tracker.courses.domaine.JetonQr;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Double en mémoire du port DepotInscriptions pour les tests de 3.1 et 3.2. */
final class DepotInscriptionsEnMemoireDeTest implements DepotInscriptions {

    final List<Inscription> inscriptions = new ArrayList<>();
    /** 4.8 : écritures de mise à jour (transition d'une Inscription existante). */
    int nombreDeMisesAJour;
    /** 4.8 : si posé, reçoit "inscription" à chaque recherche par jeton (ordre des lectures). */
    List<String> journalDesLectures;

    /**
     * 4.8 (nom supposé : {@code DepotInscriptions.mettreAJour(Inscription)}) : remplace l'Inscription de même
     * identifiant par la version transmise.
     */
    public void mettreAJour(Inscription inscription) {
        nombreDeMisesAJour++;
        inscriptions.replaceAll(i -> i.id().equals(inscription.id()) ? inscription : i);
    }

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
        nombreDeComptagesUnitaires++;
        return (int) inscriptions.stream().filter(i -> i.courseId().equals(courseId)).count();
    }

    /** 7.2 RG10 : compteur des comptages unitaires (ne doivent plus servir aux listes de Courses). */
    int nombreDeComptagesUnitaires;
    /** 7.2 RG10 : nombre de comptages groupés demandés. */
    int nombreDeComptagesGroupes;

    /** 7.2 RG10 (nom supposé) : une entrée par Course demandée, 0 pour une Course sans Inscrit. */
    public Map<UUID, Integer> nombresInscritsParCourse(Collection<UUID> courseIds) {
        nombreDeComptagesGroupes++;
        Map<UUID, Integer> nombres = new HashMap<>();
        courseIds.forEach(id -> nombres.put(id, (int) inscriptions.stream().filter(i -> i.courseId().equals(id)).count()));
        return nombres;
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

    /** 3.5 RG3 : Inscriptions de la seule Course demandée, dans l'ordre d'insertion (le tri est une règle du cas d'usage). */
    @Override
    public List<Inscription> parCourse(UUID courseId) {
        return inscriptions.stream().filter(i -> i.courseId().equals(courseId)).toList();
    }

    /** 4.4a RG3 : recherche du jeton dans la seule Course demandée ; le jeton d'une autre Course est inconnu. */
    @Override
    public Optional<Inscription> parCourseEtJeton(UUID courseId, JetonQr jeton) {
        if (journalDesLectures != null) {
            journalDesLectures.add("inscription");
        }
        return inscriptions.stream()
                .filter(i -> i.courseId().equals(courseId) && i.jetonQr().equals(jeton))
                .findFirst();
    }

    /** 4.5b RG1 : recherche par (identifiant, Course) ; l'Inscription d'une autre Course n'est jamais renvoyée. */
    @Override
    public Optional<Inscription> parIdEtCourse(UUID inscriptionId, UUID courseId) {
        return inscriptions.stream().filter(i -> i.id().equals(inscriptionId) && i.courseId().equals(courseId))
                .findFirst();
    }
}
