package fr.backyard.tracker.courses.exposition;

import fr.backyard.tracker.courses.domaine.BenevoleInconnuException;
import fr.backyard.tracker.courses.domaine.BenevoleNonAffecteException;
import fr.backyard.tracker.courses.domaine.CourseTermineePourPassageException;
import fr.backyard.tracker.courses.domaine.HeureDeDepartInconnueException;
import fr.backyard.tracker.courses.domaine.IdentifiantScanDejaUtiliseException;
import fr.backyard.tracker.courses.domaine.InscriptionNonEnCourseException;
import fr.backyard.tracker.courses.domaine.JetonInconnuException;
import fr.backyard.tracker.courses.domaine.PassageDejaEnregistreException;
import fr.backyard.tracker.courses.domaine.PassagePrecedentManquantException;
import fr.backyard.tracker.courses.domaine.RefusPassageCoureurException;
import fr.backyard.tracker.courses.domaine.ScanAnterieurAuDepartException;
import fr.backyard.tracker.courses.domaine.TempsDeCourseEcouleException;
import fr.backyard.tracker.courses.domaine.CourseCompleteException;
import fr.backyard.tracker.courses.domaine.CourseHorsDateException;
import fr.backyard.tracker.courses.domaine.CourseIntrouvableException;
import fr.backyard.tracker.courses.domaine.CourseNonDemarrableException;
import fr.backyard.tracker.courses.domaine.CourseNonDemarreeException;
import fr.backyard.tracker.courses.domaine.CourseNonModifiableException;
import fr.backyard.tracker.courses.domaine.CourseNonOuverteException;
import fr.backyard.tracker.courses.domaine.CourseNonSupprimableException;
import fr.backyard.tracker.courses.domaine.CourseSansInscritException;
import fr.backyard.tracker.courses.domaine.CourseTermineeException;
import fr.backyard.tracker.courses.domaine.DesinscriptionImpossibleException;
import fr.backyard.tracker.courses.domaine.DonneesCourseInvalidesException;
import fr.backyard.tracker.courses.domaine.InscriptionDejaExistanteException;
import fr.backyard.tracker.courses.domaine.InscriptionIntrouvableException;
import fr.backyard.tracker.courses.domaine.ViolationValidation;
import java.util.List;
import java.net.URI;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Erreurs métier du contexte courses en ProblemDetail (même format que le reste de l'API). Le corps illisible,
 * le 404 générique et la sécurité restent traités globalement. Les messages ne reprennent jamais la valeur saisie.
 *
 * <p>Ordre explicite : consulté avant le gestionnaire de dernier recours des erreurs inattendues (500).
 */
@RestControllerAdvice(assignableTypes = {CourseController.class, LogoCourseController.class,
        FicheCourseController.class, CoursesDuBenevoleController.class, InscriptionsCoureurController.class,
        MesInscriptionsController.class, PassageController.class})
@Order(0)
public class GestionnaireErreursCourses {

    private static final URI TYPE_GENERIQUE = URI.create("about:blank");

    @ExceptionHandler(DonneesCourseInvalidesException.class)
    ProblemDetail donneesInvalides(DonneesCourseInvalidesException exception) {
        return validationEchouee(exception.violations());
    }

    /** Aucun identifiant dans la réponse : une seule erreur pour toute la liste. */
    @ExceptionHandler(BenevoleInconnuException.class)
    ProblemDetail benevoleInconnu() {
        return validationEchouee(List.of(new ViolationValidation("benevoleIds", "BENEVOLE_INCONNU",
                "Un des comptes choisis n'est pas un bénévole.")));
    }

    @ExceptionHandler(CourseTermineeException.class)
    ProblemDetail courseTerminee() {
        return probleme(HttpStatus.CONFLICT, "Conflit",
                "La course est terminée : ses bénévoles ne peuvent plus être modifiés.", "COURSE_TERMINEE");
    }

    private static ProblemDetail validationEchouee(List<ViolationValidation> violations) {
        ProblemDetail probleme = probleme(HttpStatus.BAD_REQUEST, "Requête invalide",
                "Certains champs sont invalides.", "VALIDATION_ECHOUEE");
        probleme.setProperty("erreurs", violations.stream().map(ErreurChamp::depuis).toList());
        return probleme;
    }

    @ExceptionHandler(CourseIntrouvableException.class)
    ProblemDetail courseIntrouvable() {
        return probleme(HttpStatus.NOT_FOUND, "Introuvable", "La course est introuvable.", "COURSE_INTROUVABLE");
    }

    @ExceptionHandler(CourseNonModifiableException.class)
    ProblemDetail courseNonModifiable() {
        return probleme(HttpStatus.CONFLICT, "Conflit",
                "La course n'est plus en préparation : elle ne peut plus être modifiée.", "COURSE_NON_MODIFIABLE");
    }

    @ExceptionHandler(CourseNonSupprimableException.class)
    ProblemDetail courseNonSupprimable() {
        return probleme(HttpStatus.CONFLICT, "Conflit",
                "La course n'est plus en préparation : elle ne peut plus être supprimée.", "COURSE_NON_SUPPRIMABLE");
    }

    @ExceptionHandler(CourseNonDemarrableException.class)
    ProblemDetail courseNonDemarrable() {
        return probleme(HttpStatus.CONFLICT, "Conflit",
                "La course n'est plus en préparation : elle ne peut plus être démarrée.", "COURSE_NON_DEMARRABLE");
    }

    @ExceptionHandler(CourseNonDemarreeException.class)
    ProblemDetail courseNonDemarree() {
        return probleme(HttpStatus.CONFLICT, "Conflit", "La course n'est pas démarrée.", "COURSE_NON_DEMARREE");
    }

    @ExceptionHandler(CourseHorsDateException.class)
    ProblemDetail courseHorsDate() {
        return probleme(HttpStatus.CONFLICT, "Conflit", "La course ne peut être démarrée que le jour de sa date.",
                "COURSE_HORS_DATE");
    }

    @ExceptionHandler(CourseSansInscritException.class)
    ProblemDetail courseSansInscrit() {
        return probleme(HttpStatus.CONFLICT, "Conflit",
                "La course ne peut pas être démarrée : aucun coureur n'est inscrit.", "COURSE_SANS_INSCRIT");
    }

    @ExceptionHandler(InscriptionDejaExistanteException.class)
    ProblemDetail inscriptionDejaExistante() {
        return probleme(HttpStatus.CONFLICT, "Conflit", "Vous êtes déjà inscrit à cette course.",
                "INSCRIPTION_DEJA_EXISTANTE");
    }

    @ExceptionHandler(CourseNonOuverteException.class)
    ProblemDetail courseNonOuverte() {
        return probleme(HttpStatus.CONFLICT, "Conflit", "La course n'est plus ouverte aux inscriptions.",
                "COURSE_NON_OUVERTE");
    }

    @ExceptionHandler(CourseCompleteException.class)
    ProblemDetail courseComplete() {
        return probleme(HttpStatus.CONFLICT, "Conflit", "La course est complète.", "COURSE_COMPLETE");
    }

    @ExceptionHandler(InscriptionIntrouvableException.class)
    ProblemDetail inscriptionIntrouvable() {
        return probleme(HttpStatus.NOT_FOUND, "Introuvable", "L'inscription est introuvable.",
                "INSCRIPTION_INTROUVABLE");
    }

    @ExceptionHandler(DesinscriptionImpossibleException.class)
    ProblemDetail desinscriptionImpossible() {
        return probleme(HttpStatus.CONFLICT, "Conflit",
                "La course n'est plus en préparation : la désinscription est impossible.", "DESINSCRIPTION_IMPOSSIBLE");
    }

    /** Même réponse pour un bénévole non affecté et une Course inconnue : rien ne révèle l'existence d'une Course. */
    @ExceptionHandler(BenevoleNonAffecteException.class)
    ProblemDetail benevoleNonAffecte() {
        return probleme(HttpStatus.FORBIDDEN, "Accès refusé", "Vous n'êtes pas affecté à cette course.",
                "BENEVOLE_NON_AFFECTE");
    }

    @ExceptionHandler(JetonInconnuException.class)
    ProblemDetail jetonInconnu() {
        return probleme(HttpStatus.NOT_FOUND, "Introuvable", "Jeton inconnu pour cette course.", "JETON_INCONNU");
    }

    /** Aucune donnée du Passage existant n'est divulguée. */
    @ExceptionHandler(IdentifiantScanDejaUtiliseException.class)
    ProblemDetail identifiantScanDejaUtilise() {
        return conflit("Cet identifiant de scan correspond à un autre passage.", "IDENTIFIANT_SCAN_DEJA_UTILISE");
    }

    @ExceptionHandler(CourseTermineePourPassageException.class)
    ProblemDetail courseTermineePourPassage() {
        return conflit("La course est terminée.", "COURSE_TERMINEE");
    }

    @ExceptionHandler(HeureDeDepartInconnueException.class)
    ProblemDetail heureDeDepartInconnue() {
        return conflit("L'heure de départ de la course est inconnue : le passage ne peut pas être placé sur une "
                + "boucle.", "HEURE_DE_DEPART_INCONNUE");
    }

    @ExceptionHandler(ScanAnterieurAuDepartException.class)
    ProblemDetail scanAnterieurAuDepart() {
        return conflit("Le scan est antérieur au départ de la course.", "SCAN_ANTERIEUR_AU_DEPART");
    }

    @ExceptionHandler(InscriptionNonEnCourseException.class)
    ProblemDetail inscriptionNonEnCourse(InscriptionNonEnCourseException refus) {
        ProblemDetail probleme = refusCoureur(refus, "Ce coureur n'est plus en course.", "INSCRIPTION_NON_EN_COURSE");
        probleme.setProperty("statut", refus.statut());
        return probleme;
    }

    @ExceptionHandler(TempsDeCourseEcouleException.class)
    ProblemDetail tempsDeCourseEcoule(TempsDeCourseEcouleException refus) {
        return refusCoureur(refus, "Le temps de la dernière boucle est écoulé.", "TEMPS_DE_COURSE_ECOULE");
    }

    @ExceptionHandler(PassageDejaEnregistreException.class)
    ProblemDetail passageDejaEnregistre(PassageDejaEnregistreException refus) {
        ProblemDetail probleme = refusCoureur(refus,
                "Ce coureur a déjà un passage sur la boucle " + refus.numeroBoucle() + ".", "PASSAGE_DEJA_ENREGISTRE");
        probleme.setProperty("numeroBoucle", refus.numeroBoucle());
        return probleme;
    }

    @ExceptionHandler(PassagePrecedentManquantException.class)
    ProblemDetail passagePrecedentManquant(PassagePrecedentManquantException refus) {
        ProblemDetail probleme = refusCoureur(refus, "Ce coureur n'a pas de passage sur la boucle précédente.",
                "PASSAGE_PRECEDENT_MANQUANT");
        probleme.setProperty("numeroBoucle", refus.numeroBoucle());
        return probleme;
    }

    /** Conflit lié à un coureur connu : dossard et pseudo en propriétés, visibles du seul bénévole qui le scanne. */
    private static ProblemDetail refusCoureur(RefusPassageCoureurException refus, String detail, String code) {
        ProblemDetail probleme = conflit(detail, code);
        refus.coureur().ifPresent(coureur -> {
            probleme.setProperty("dossard", coureur.dossard());
            probleme.setProperty("pseudo", coureur.pseudo());
        });
        return probleme;
    }

    private static ProblemDetail conflit(String detail, String code) {
        return probleme(HttpStatus.CONFLICT, "Conflit", detail, code);
    }

    /** ProblemDetail au format de l'API : type about:blank, titre, détail et code stable. */
    static ProblemDetail probleme(HttpStatus statut, String titre, String detail, String code) {
        ProblemDetail probleme = ProblemDetail.forStatusAndDetail(statut, detail);
        probleme.setType(TYPE_GENERIQUE);
        probleme.setTitle(titre);
        probleme.setProperty("code", code);
        return probleme;
    }
}
