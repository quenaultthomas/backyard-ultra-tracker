package fr.backyard.tracker.comptes.application;

import fr.backyard.tracker.comptes.domaine.AnnulationInscriptions;
import fr.backyard.tracker.comptes.domaine.Compte;
import fr.backyard.tracker.comptes.domaine.ConsultationInscriptions;
import fr.backyard.tracker.comptes.domaine.CourseNonTerminee;
import fr.backyard.tracker.comptes.domaine.DepotComptes;
import fr.backyard.tracker.comptes.domaine.DesaffectationBenevole;
import fr.backyard.tracker.comptes.domaine.EncodeurMotDePasse;
import fr.backyard.tracker.comptes.domaine.InvalidationAutresSessions;
import fr.backyard.tracker.comptes.domaine.MotDePasse;
import fr.backyard.tracker.comptes.domaine.PolitiqueBlocage;
import fr.backyard.tracker.comptes.domaine.Pseudo;
import fr.backyard.tracker.comptes.domaine.ReferencePhoto;
import fr.backyard.tracker.comptes.domaine.RegistreTentativesConnexion;
import fr.backyard.tracker.comptes.domaine.Role;
import fr.backyard.tracker.comptes.domaine.StockagePhotos;
import fr.backyard.tracker.comptes.domaine.TentativesConnexion;
import fr.backyard.tracker.partage.domaine.FormatImage;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Doubles en mémoire communs aux tests de la photo de profil (10.1a). Un journal partagé enregistre l'ordre des appels
 * : {@code ecrire:<jeton>.<ext>}, {@code maj-compte}, {@code suppression-compte}, {@code effacer:<jeton>.<ext>}.
 */
final class DoublesPhoto {

    static final Instant T = Instant.parse("2026-10-07T10:00:00Z");
    static final String ECRITURE = "ecrire:";
    static final String EFFACEMENT = "effacer:";
    static final String MISE_A_JOUR = "maj-compte";
    static final String SUPPRESSION = "suppression-compte";

    private DoublesPhoto() {
    }

    static Compte compte(DepotEnMemoire depot, String pseudo, Role role, ReferencePhoto photo) {
        Compte compte = Compte.reconstituer(UUID.randomUUID(), new Pseudo(pseudo), "empreinte:un-mot-de-passe-12", role,
                T.minusSeconds(3600), true, photo);
        depot.comptes.put(compte.id(), compte);
        return compte;
    }

    static String cle(String jeton, FormatImage format) {
        return jeton + "." + format.extension();
    }

    static final class DepotEnMemoire implements DepotComptes {
        final Map<UUID, Compte> comptes = new LinkedHashMap<>();
        final List<String> journal;
        int misesAJour;
        int recherchesParJeton;
        RuntimeException panneMiseAJour;

        DepotEnMemoire(List<String> journal) {
            this.journal = journal;
        }

        @Override
        public boolean existeParPseudoNormalise(String pseudoNormalise) {
            return false;
        }

        @Override
        public void enregistrer(Compte compte) {
            comptes.put(compte.id(), compte);
        }

        @Override
        public Optional<Compte> trouverParId(UUID id) {
            return Optional.ofNullable(comptes.get(id));
        }

        @Override
        public Optional<Compte> trouverParJetonPhoto(String jeton) {
            recherchesParJeton++;
            return comptes.values().stream()
                    .filter(c -> c.photo() != null && c.photo().jeton().equals(jeton))
                    .findFirst();
        }

        @Override
        public List<Compte> trouverParRolePourModification(Role role) {
            return comptes.values().stream().filter(c -> c.role() == role).toList();
        }

        @Override
        public void mettreAJour(Compte compte) {
            if (panneMiseAJour != null) {
                throw panneMiseAJour;
            }
            journal.add(MISE_A_JOUR);
            misesAJour++;
            comptes.put(compte.id(), compte);
        }

        @Override
        public void supprimer(UUID id) {
            journal.add(SUPPRESSION);
            comptes.remove(id);
        }
    }

    /** Stockage en mémoire : les fichiers présents sont indexés par {@code <jeton>.<ext>}. */
    static final class StockageEnMemoire implements StockagePhotos {
        final Map<String, byte[]> fichiers = new LinkedHashMap<>();
        final List<String> journal;
        final List<String> ecritures = new ArrayList<>();
        final List<String> effacements = new ArrayList<>();
        int lectures;
        RuntimeException panneEcriture;
        RuntimeException panneEffacement;

        StockageEnMemoire(List<String> journal) {
            this.journal = journal;
        }

        @Override
        public void enregistrer(String jeton, FormatImage format, byte[] octets) {
            if (panneEcriture != null) {
                throw panneEcriture;
            }
            journal.add(ECRITURE + cle(jeton, format));
            ecritures.add(cle(jeton, format));
            fichiers.put(cle(jeton, format), octets.clone());
        }

        @Override
        public Optional<byte[]> lire(String jeton, FormatImage format) {
            lectures++;
            return Optional.ofNullable(fichiers.get(cle(jeton, format))).map(byte[]::clone);
        }

        @Override
        public void effacer(String jeton, FormatImage format) {
            journal.add(EFFACEMENT + cle(jeton, format));
            effacements.add(cle(jeton, format));
            if (panneEffacement != null) {
                throw panneEffacement;
            }
            fichiers.remove(cle(jeton, format));
        }

        void poserFichier(ReferencePhoto photo, byte[] octets) {
            fichiers.put(cle(photo.jeton(), photo.format()), octets.clone());
        }

        int appels() {
            return ecritures.size() + effacements.size() + lectures;
        }
    }

    static final class AnnulationNeutre implements AnnulationInscriptions {
        @Override
        public int annulerPour(UUID compteId) {
            return 0;
        }
    }

    static final class InvalidationNeutre implements InvalidationAutresSessions {
        final List<UUID> fermees = new ArrayList<>();

        @Override
        public void invaliderAutresSessions(UUID compteId) {
        }

        @Override
        public void invaliderToutesLesSessions(UUID compteId) {
            fermees.add(compteId);
        }
    }

    static final class RegistreNeutre implements RegistreTentativesConnexion {
        private final Map<String, TentativesConnexion> entrees = new HashMap<>();

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
            entrees.remove(cle);
        }
    }

    static final class EncodeurPrefixe implements EncodeurMotDePasse {
        @Override
        public String encoder(MotDePasse motDePasse) {
            return "empreinte:" + motDePasse.valeur();
        }

        @Override
        public boolean verifier(String motDePasseEnClair, String empreinte) {
            return empreinte != null && empreinte.equals("empreinte:" + motDePasseEnClair);
        }
    }

    static final class DesaffectationFactice implements DesaffectationBenevole {
        RuntimeException refus;

        @Override
        public int desaffecterDesCoursesEnPreparation(UUID benevoleId) {
            return 0;
        }

        @Override
        public int desaffecterDeToutesLesCourses(UUID benevoleId) {
            if (refus != null) {
                throw refus;
            }
            return 0;
        }
    }

    static final class ConsultationFactice implements ConsultationInscriptions {
        final java.util.Set<UUID> avecInscriptions = new java.util.HashSet<>();

        @Override
        public boolean aDesInscriptions(UUID compteId) {
            return avecInscriptions.contains(compteId);
        }

        @Override
        public List<CourseNonTerminee> coursesNonTerminees(UUID compteId) {
            return List.of();
        }
    }

    static String jointure(List<String> journal) {
        return journal.stream().collect(Collectors.joining(" | "));
    }
}
