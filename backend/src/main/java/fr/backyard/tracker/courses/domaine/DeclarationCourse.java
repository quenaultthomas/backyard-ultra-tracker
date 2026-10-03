package fr.backyard.tracker.courses.domaine;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * Règles de saisie de la déclaration et de la modification d'une Course : seul endroit où sont définies les bornes.
 * Collecte au plus une violation par champ, dans l'ordre des champs du formulaire.
 */
final class DeclarationCourse {

    private static final int NOM_LONGUEUR_MIN = 3;
    private static final int NOM_LONGUEUR_MAX = 100;
    private static final int DATE_ANNEES_MAX = 5;

    private static final Borne DISTANCE = Borne.de("distanceBoucleMetres", 1, 50_000,
            "DISTANCE_REQUISE", "La distance d'une boucle est obligatoire.",
            "DISTANCE_HORS_BORNES", "La distance d'une boucle doit être comprise entre 1 et 50000 m.");
    private static final Borne DUREE = Borne.de("dureeBoucleMinutes", 1, 1_440,
            "DUREE_REQUISE", "La durée d'une boucle est obligatoire.",
            "DUREE_HORS_BORNES", "La durée d'une boucle doit être comprise entre 1 et 1440 min.");
    /** Exception explicite : une Boucle plate (dénivelé 0) est acceptée. */
    private static final Borne DENIVELE = Borne.de("denivelePositifBoucleMetres", 0, 10_000,
            "DENIVELE_REQUIS", "Le dénivelé positif d'une boucle est obligatoire.",
            "DENIVELE_HORS_BORNES", "Le dénivelé positif d'une boucle doit être compris entre 0 et 10000 m.");
    private static final Borne PARTICIPANTS = Borne.de("nombreMaxParticipants", 1, 5_000,
            "NOMBRE_MAX_PARTICIPANTS_REQUIS", "Le nombre maximum de participants est obligatoire.",
            "NOMBRE_MAX_PARTICIPANTS_HORS_BORNES", "Le nombre maximum de participants doit être compris entre 1 et 5000.");
    private static final Borne BOUCLES = Borne.de("nombreMaxBoucles", 1, 500,
            "NOMBRE_MAX_BOUCLES_REQUIS", "Le nombre maximum de boucles est obligatoire.",
            "NOMBRE_MAX_BOUCLES_HORS_BORNES", "Le nombre maximum de boucles doit être compris entre 1 et 500.");

    private final LocalDate aujourdhui;
    private final List<ViolationValidation> violations = new ArrayList<>();

    DeclarationCourse(LocalDate aujourdhui) {
        this.aujourdhui = aujourdhui;
    }

    /** @param nom nom déjà débarrassé de ses espaces de début et de fin */
    DeclarationCourse verifierNom(String nom) {
        int longueur = nom.codePointCount(0, nom.length());
        if (nom.isEmpty()) {
            violations.add(new ViolationValidation("nom", "NOM_REQUIS", "Le nom est obligatoire."));
        } else if (longueur < NOM_LONGUEUR_MIN || longueur > NOM_LONGUEUR_MAX) {
            violations.add(new ViolationValidation("nom", "NOM_LONGUEUR",
                    "Le nom doit faire entre 3 et 100 caractères."));
        } else if (nom.codePoints().anyMatch(Character::isISOControl)) {
            violations.add(new ViolationValidation("nom", "NOM_CARACTERES",
                    "Le nom ne doit pas contenir de caractère de contrôle."));
        }
        return this;
    }

    DeclarationCourse verifierDate(LocalDate date) {
        if (date == null) {
            violations.add(new ViolationValidation("date", "DATE_REQUISE", "La date est obligatoire."));
        } else if (date.isBefore(aujourdhui)) {
            violations.add(new ViolationValidation("date", "DATE_PASSEE", "La date ne peut pas être dans le passé."));
        } else if (date.isAfter(aujourdhui.plusYears(DATE_ANNEES_MAX))) {
            violations.add(new ViolationValidation("date", "DATE_TROP_LOINTAINE",
                    "La date ne peut pas dépasser de plus de 5 ans la date du jour."));
        }
        return this;
    }

    /**
     * Date d'une Course déjà déclarée : une date inchangée est toujours acceptée (elle a été contrôlée à la
     * déclaration et peut être arrivée ou passée depuis) ; une date changée est contrôlée comme à la déclaration.
     */
    DeclarationCourse verifierNouvelleDate(LocalDate date, LocalDate dateEnregistree) {
        return dateEnregistree.equals(date) ? this : verifierDate(date);
    }

    DeclarationCourse verifierParametresBoucle(Integer distance, Integer duree, Integer denivele) {
        DISTANCE.verifier(distance, violations);
        DUREE.verifier(duree, violations);
        DENIVELE.verifier(denivele, violations);
        return this;
    }

    DeclarationCourse verifierLimites(Integer nombreMaxParticipants, Integer nombreMaxBoucles) {
        PARTICIPANTS.verifier(nombreMaxParticipants, violations);
        BOUCLES.verifier(nombreMaxBoucles, violations);
        return this;
    }

    void rejeterSiInvalide() {
        if (!violations.isEmpty()) {
            throw new DonneesCourseInvalidesException(violations);
        }
    }

    /** Valeur entière obligatoire comprise entre deux bornes incluses. */
    private record Borne(int minimum, int maximum, ViolationValidation requise, ViolationValidation horsBornes) {

        static Borne de(String champ, int minimum, int maximum, String codeRequis, String messageRequis,
                        String codeHorsBornes, String messageHorsBornes) {
            return new Borne(minimum, maximum, new ViolationValidation(champ, codeRequis, messageRequis),
                    new ViolationValidation(champ, codeHorsBornes, messageHorsBornes));
        }

        void verifier(Integer valeur, List<ViolationValidation> violations) {
            if (valeur == null) {
                violations.add(requise);
            } else if (valeur < minimum || valeur > maximum) {
                violations.add(horsBornes);
            }
        }
    }
}
