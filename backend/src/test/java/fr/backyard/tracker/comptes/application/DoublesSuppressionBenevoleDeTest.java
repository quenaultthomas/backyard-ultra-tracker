package fr.backyard.tracker.comptes.application;

import fr.backyard.tracker.comptes.domaine.BenevoleAffecteCourseEnCoursException;
import fr.backyard.tracker.comptes.domaine.Compte;
import fr.backyard.tracker.comptes.domaine.DepotComptes;
import fr.backyard.tracker.comptes.domaine.DesaffectationBenevole;
import fr.backyard.tracker.comptes.domaine.InvalidationAutresSessions;
import fr.backyard.tracker.comptes.domaine.PolitiqueBlocage;
import fr.backyard.tracker.comptes.domaine.RegistreTentativesConnexion;
import fr.backyard.tracker.comptes.domaine.TentativesConnexion;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Doubles de 9.3 : dépôt de Comptes qui sait supprimer, port de désaffectation totale, registre de tentatives et
 * sessions, inscrits dans un journal commun pour vérifier l'ordre des opérations (RG9).
 */
final class DoublesSuppressionBenevoleDeTest {

    static final String CHARGEMENT_COMPTE = "chargement-compte-sous-verrou";
    static final String LECTURE_SIMPLE = "lecture-simple";
    static final String MISE_A_JOUR = "mise-a-jour";
    static final String RETRAIT_TOUTES_AFFECTATIONS = "retrait-toutes-affectations";
    static final String SUPPRESSION = "suppression-compte";
    static final String REMISE_A_ZERO_COMPTEUR = "remise-a-zero-compteur";
    static final String FERMETURE_TOUTES_SESSIONS = "fermeture-toutes-sessions";

    private DoublesSuppressionBenevoleDeTest() {
    }

    static class DepotAvecSuppression implements DepotComptes {
        private final Map<UUID, Compte> comptes = new LinkedHashMap<>();
        private final List<String> journal;
        final List<UUID> suppressions = new ArrayList<>();
        RuntimeException echecSuppression;
        int misesAJour;

        DepotAvecSuppression(List<String> journal) {
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
            journal.add(LECTURE_SIMPLE);
            return Optional.ofNullable(comptes.get(id));
        }

        @Override
        public Optional<Compte> trouverParIdPourModification(UUID id) {
            journal.add(CHARGEMENT_COMPTE);
            return Optional.ofNullable(comptes.get(id));
        }

        @Override
        public void mettreAJour(Compte compte) {
            journal.add(MISE_A_JOUR);
            misesAJour++;
            comptes.put(compte.id(), compte);
        }

        @Override
        public void supprimer(UUID id) {
            journal.add(SUPPRESSION);
            if (echecSuppression != null) {
                throw echecSuppression;
            }
            suppressions.add(id);
            comptes.remove(id);
        }
    }

    /**
     * Port de désaffectation : renvoie un nombre de retraits, lève l'exception du domaine si des Courses EN_COURS
     * sont bloquantes, ou une panne inattendue. Ne sert que la méthode de retrait total de 9.3.
     */
    static final class DesaffectationTotaleEspion implements DesaffectationBenevole {
        final List<UUID> appels = new ArrayList<>();
        int nombreRetourne;
        BenevoleAffecteCourseEnCoursException refus;
        RuntimeException panne;
        private final List<String> journal;

        DesaffectationTotaleEspion(List<String> journal) {
            this.journal = journal;
        }

        @Override
        public int desaffecterDesCoursesEnPreparation(UUID benevoleId) {
            throw new AssertionError("La suppression ne doit pas appeler la désaffectation partielle de 8.1");
        }

        /** Nom attendu de la nouvelle méthode du port (contrat de nommage 9.3). */
        @Override
        public int desaffecterDeToutesLesCourses(UUID benevoleId) {
            journal.add(RETRAIT_TOUTES_AFFECTATIONS);
            appels.add(benevoleId);
            if (panne != null) {
                throw panne;
            }
            if (refus != null) {
                throw refus;
            }
            return nombreRetourne;
        }
    }

    static final class SessionsEspion implements InvalidationAutresSessions {
        final List<UUID> toutesLesSessions = new ArrayList<>();
        final List<UUID> autresSessions = new ArrayList<>();
        private final List<String> journal;

        SessionsEspion(List<String> journal) {
            this.journal = journal;
        }

        @Override
        public void invaliderAutresSessions(UUID compteId) {
            autresSessions.add(compteId);
        }

        @Override
        public void invaliderToutesLesSessions(UUID compteId) {
            journal.add(FERMETURE_TOUTES_SESSIONS);
            toutesLesSessions.add(compteId);
        }
    }

    static final class RegistreEspion implements RegistreTentativesConnexion {
        private final Map<String, TentativesConnexion> entrees = new HashMap<>();
        private final List<String> journal;
        final List<String> effacements = new ArrayList<>();

        RegistreEspion(List<String> journal) {
            this.journal = journal;
        }

        @Override
        public TentativesConnexion constater(String cle, Instant maintenant) {
            return entrees.getOrDefault(cle, TentativesConnexion.aucune());
        }

        @Override
        public TentativesConnexion enregistrerEchec(String cle, Instant maintenant, PolitiqueBlocage politique) {
            return entrees.compute(cle, (k, actuelle) ->
                    (actuelle == null ? TentativesConnexion.aucune() : actuelle).apresEchec(maintenant, politique));
        }

        @Override
        public void effacer(String cle) {
            journal.add(REMISE_A_ZERO_COMPTEUR);
            effacements.add(cle);
            entrees.remove(cle);
        }
    }
}
