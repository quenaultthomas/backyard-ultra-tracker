package fr.backyard.tracker.courses.application;

import fr.backyard.tracker.courses.domaine.AnnuairePseudos;
import fr.backyard.tracker.courses.domaine.BenevoleNonAffecteException;
import fr.backyard.tracker.courses.domaine.CoureurScanne;
import fr.backyard.tracker.courses.domaine.Course;
import fr.backyard.tracker.courses.domaine.DepotCourses;
import fr.backyard.tracker.courses.domaine.DepotInscriptions;
import fr.backyard.tracker.courses.domaine.DepotPassages;
import fr.backyard.tracker.courses.domaine.IdentifiantScanDejaUtiliseException;
import fr.backyard.tracker.courses.domaine.Inscription;
import fr.backyard.tracker.courses.domaine.JetonInconnuException;
import fr.backyard.tracker.courses.domaine.JetonQr;
import fr.backyard.tracker.courses.domaine.Passage;
import fr.backyard.tracker.courses.domaine.PassageDejaEnregistreException;
import fr.backyard.tracker.courses.domaine.RefusPassageCoureurException;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Enregistrement du Passage d'un coureur par un bénévole affecté à la Course (le rôle est contrôlé avant ce cas
 * d'usage). Idempotent par identifiant de scan : le même identifiant pour la même Inscription renvoie le Passage
 * existant, quel que soit l'état actuel. Ordre des contrôles : affectation, jeton, rejeu, Course, Inscription,
 * Boucle, déjà passé, continuité, anti-doublon. Aucun journal (chemin fréquent ; ni jeton ni pseudo).
 */
@Service
public class EnregistrerPassage {

    /** Passage créé ou rejoué, avec le coureur restitué au bénévole. Le pseudo n'apparaît pas dans toString. */
    public record Resultat(Passage passage, int dossard, String pseudo, boolean rejeu) {

        @Override
        public String toString() {
            return "Resultat[passage=" + passage + ", rejeu=" + rejeu + "]";
        }
    }

    private final DepotCourses depotCourses;
    private final DepotInscriptions depotInscriptions;
    private final DepotPassages depotPassages;
    private final AnnuairePseudos annuairePseudos;
    private final Clock horloge;

    public EnregistrerPassage(DepotCourses depotCourses, DepotInscriptions depotInscriptions,
                              DepotPassages depotPassages, AnnuairePseudos annuairePseudos, Clock horloge) {
        this.depotCourses = depotCourses;
        this.depotInscriptions = depotInscriptions;
        this.depotPassages = depotPassages;
        this.annuairePseudos = annuairePseudos;
        this.horloge = horloge;
    }

    /**
     * @param identifiantScan identifiant tiré par le client pour ce scan, qui devient celui du Passage
     * @param ageDuScanMillisecondes temps écoulé entre le scan et l'envoi : le scan a eu lieu à l'instant de
     *                               l'horloge (lue une seule fois) moins cet âge
     * @throws BenevoleNonAffecteException bénévole non affecté ou Course inconnue
     * @throws JetonInconnuException aucune Inscription de la Course n'a ce jeton
     * @throws IdentifiantScanDejaUtiliseException l'identifiant désigne le Passage d'une autre Inscription
     * @throws RefusPassageCoureurException refus rapporté au coureur scanné (statut, temps écoulé, Boucle,
     *                                     anti-doublon)
     */
    @Transactional
    public Resultat executer(UUID courseId, UUID benevoleId, JetonQr jeton, UUID identifiantScan,
                             long ageDuScanMillisecondes) {
        Course course = courseAffectee(courseId, benevoleId);
        Inscription inscription = depotInscriptions.parCourseEtJeton(courseId, jeton)
                .orElseThrow(JetonInconnuException::new);
        CoureurScanne coureur = new CoureurScanne(inscription.dossard(), pseudoDe(inscription));
        Optional<Passage> existant = depotPassages.parId(identifiantScan);
        if (existant.isPresent()) {
            return rejeu(existant.get(), inscription, coureur);
        }
        Instant scanneLe = Instant.now(horloge).minusMillis(ageDuScanMillisecondes).truncatedTo(ChronoUnit.MILLIS);
        Passage passage = nouveauPassage(course, inscription, identifiantScan, scanneLe, coureur);
        enregistrer(passage, coureur);
        return new Resultat(passage, coureur.dossard(), coureur.pseudo(), false);
    }

    /**
     * Course chargée sous verrou, avant toute lecture de l'Inscription : un scan et l'abandon automatique de la même
     * Course sont sérialisés (jamais un abandon sur une Boucle et un Passage sur cette même Boucle).
     */
    private Course courseAffectee(UUID courseId, UUID benevoleId) {
        Course course = depotCourses.parIdPourModification(courseId).orElseThrow(BenevoleNonAffecteException::new);
        course.autoriserScanPar(benevoleId);
        return course;
    }

    private String pseudoDe(Inscription inscription) {
        return annuairePseudos.pseudosDe(List.of(inscription.compteId()))
                .getOrDefault(inscription.compteId(), ListerInscritsCourse.PSEUDO_COMPTE_INCONNU);
    }

    private static Resultat rejeu(Passage existant, Inscription inscription, CoureurScanne coureur) {
        if (!existant.inscriptionId().equals(inscription.id())) {
            throw new IdentifiantScanDejaUtiliseException();
        }
        return new Resultat(existant, coureur.dossard(), coureur.pseudo(), true);
    }

    private Passage nouveauPassage(Course course, Inscription inscription, UUID identifiantScan, Instant scanneLe,
                                   CoureurScanne coureur) {
        course.autoriserPassage();
        try {
            inscription.autoriserPassage();
            int numeroBoucle = course.boucleDePassage(scanneLe);
            inscription.verifierBouclePassable(numeroBoucle,
                    boucle -> depotPassages.existe(inscription.id(), boucle));
            course.verifierDelaiEntrePassages(numeroBoucle, scanneLe,
                    boucle -> depotPassages.parInscriptionEtBoucle(inscription.id(), boucle));
            return Passage.scanner(identifiantScan, inscription, numeroBoucle, scanneLe);
        } catch (RefusPassageCoureurException refus) {
            throw refus.pour(coureur);
        }
    }

    /** Un scan concurrent du même coureur sur la même Boucle a gagné : même refus que « déjà passé ». */
    private void enregistrer(Passage passage, CoureurScanne coureur) {
        try {
            depotPassages.enregistrer(passage);
        } catch (PassageDejaEnregistreException concurrent) {
            throw new PassageDejaEnregistreException(passage.numeroBoucle(), coureur);
        }
    }
}
