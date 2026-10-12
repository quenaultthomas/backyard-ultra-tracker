package fr.backyard.tracker.comptes.exposition;

import fr.backyard.tracker.comptes.application.PiloterHorloge;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Horloge pilotable des tests de bout en bout (4.3), profil e2e uniquement ; rôle ADMIN_MASTER imposé par la
 * politique de sécurité HTTP. L'instant renvoyé est celui du bean {@link Clock} de l'application. Journal : une ligne
 * par avance ou réinitialisation, sans pseudo ni identifiant de compte ; rien pour une lecture.
 */
@RestController
@Profile("e2e")
@RequestMapping("/api/test/horloge")
public class HorlogeController {

    private static final Logger JOURNAL = LoggerFactory.getLogger(HorlogeController.class);

    private final PiloterHorloge piloterHorloge;
    private final Clock horloge;

    public HorlogeController(PiloterHorloge piloterHorloge, Clock horloge) {
        this.piloterHorloge = piloterHorloge;
        this.horloge = horloge;
    }

    @GetMapping
    public ResponseEntity<HorlogeReponse> lire() {
        return etat(piloterHorloge.lire());
    }

    /** La durée journalisée est le texte reçu, déjà validé par sa lecture (format ISO-8601 de durée). */
    @PostMapping(path = "/avance", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<HorlogeReponse> avancer(@RequestBody AvanceHorlogeRequete requete) {
        Duration decalage = piloterHorloge.avancer(requete.dureeLue());
        JOURNAL.info("Horloge avancée de {} (décalage cumulé {}).", requete.texte(), decalage);
        return etat(decalage);
    }

    @DeleteMapping
    public ResponseEntity<HorlogeReponse> reinitialiser() {
        Duration decalage = piloterHorloge.reinitialiser();
        JOURNAL.info("Horloge réinitialisée.");
        return etat(decalage);
    }

    private ResponseEntity<HorlogeReponse> etat(Duration decalage) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(HorlogeReponse.depuis(Instant.now(horloge), decalage));
    }
}
