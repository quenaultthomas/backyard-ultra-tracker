package fr.backyard.tracker.courses.exposition;

import fr.backyard.tracker.courses.application.ListerCourses;
import fr.backyard.tracker.courses.application.ListerMesInscriptions;
import fr.backyard.tracker.courses.application.ListerMesInscriptions.MonInscription;
import java.util.List;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Inscriptions du coureur connecté, jeton QR compris. Réservé à COUREUR par la politique de sécurité
 * (/api/coureur/**). L'identité vient uniquement de la session, jamais d'un paramètre. Aucune journalisation : la
 * réponse contient un secret (et n'est pas mise en cache, en-têtes par défaut de Spring Security).
 */
@RestController
@RequestMapping("/api/coureur/inscriptions")
public class MesInscriptionsController {

    private final ListerMesInscriptions listerMesInscriptions;
    private final ListerCourses listerCourses;

    public MesInscriptionsController(ListerMesInscriptions listerMesInscriptions, ListerCourses listerCourses) {
        this.listerMesInscriptions = listerMesInscriptions;
        this.listerCourses = listerCourses;
    }

    @GetMapping
    public List<MonInscriptionReponse> listerMesInscriptions(@AuthenticationPrincipal UUID compteId) {
        return listerMesInscriptions.executer(compteId).stream().map(this::versReponse).toList();
    }

    private MonInscriptionReponse versReponse(MonInscription monInscription) {
        return MonInscriptionReponse.depuis(listerCourses.avecEmpreinteLogo(monInscription.course()),
                monInscription.inscription());
    }
}
