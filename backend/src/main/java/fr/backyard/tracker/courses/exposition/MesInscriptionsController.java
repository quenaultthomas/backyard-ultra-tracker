package fr.backyard.tracker.courses.exposition;

import fr.backyard.tracker.courses.application.ListerCourses;
import fr.backyard.tracker.courses.application.ListerMesInscriptions;
import fr.backyard.tracker.courses.application.ListerMesInscriptions.MonInscription;
import fr.backyard.tracker.courses.application.SeDesinscrire;
import fr.backyard.tracker.courses.domaine.Inscription;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Inscriptions du coureur connecté, jeton QR compris, et désinscription. Réservé à COUREUR par la politique de
 * sécurité (/api/coureur/**). L'identité vient uniquement de la session, jamais d'un paramètre. La liste n'est jamais
 * journalisée (elle contient un secret) ; la désinscription l'est sans Compte, pseudo, jeton ni nom de Course.
 */
@RestController
@RequestMapping("/api/coureur/inscriptions")
public class MesInscriptionsController {

    private static final Logger JOURNAL = LoggerFactory.getLogger(MesInscriptionsController.class);

    private final ListerMesInscriptions listerMesInscriptions;
    private final ListerCourses listerCourses;
    private final SeDesinscrire seDesinscrire;

    public MesInscriptionsController(ListerMesInscriptions listerMesInscriptions, ListerCourses listerCourses,
                                     SeDesinscrire seDesinscrire) {
        this.listerMesInscriptions = listerMesInscriptions;
        this.listerCourses = listerCourses;
        this.seDesinscrire = seDesinscrire;
    }

    @GetMapping
    public List<MonInscriptionReponse> listerMesInscriptions(@AuthenticationPrincipal UUID compteId) {
        return listerMesInscriptions.executer(compteId).stream().map(this::versReponse).toList();
    }

    /** Un corps ou un paramètre éventuel est ignoré ; un identifiant qui n'est pas un UUID donne 404. */
    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void seDesinscrire(@AuthenticationPrincipal UUID compteId, @PathVariable("id") String id) {
        Inscription inscription = seDesinscrire.executer(compteId, IdentifiantCourse.dInscription(id));
        JOURNAL.info("Désinscription enregistrée (course {}, dossard {})", inscription.courseId(),
                inscription.dossard());
    }

    private MonInscriptionReponse versReponse(MonInscription monInscription) {
        return MonInscriptionReponse.depuis(listerCourses.avecEmpreinteLogo(monInscription.course()),
                monInscription.inscription());
    }
}
