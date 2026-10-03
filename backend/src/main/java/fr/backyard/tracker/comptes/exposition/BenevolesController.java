package fr.backyard.tracker.comptes.exposition;

import fr.backyard.tracker.comptes.application.CreerBenevole;
import fr.backyard.tracker.comptes.application.ListerBenevoles;
import fr.backyard.tracker.comptes.domaine.Compte;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Gestion des comptes BENEVOLE. Réservé à ADMIN et ADMIN_MASTER par la politique de sécurité
 * (/api/administration/**), avant ce contrôleur. Le journal ne contient que des identifiants.
 */
@RestController
@RequestMapping("/api/administration/benevoles")
public class BenevolesController {

    private static final Logger JOURNAL = LoggerFactory.getLogger(BenevolesController.class);

    private final CreerBenevole creerBenevole;
    private final ListerBenevoles listerBenevoles;

    public BenevolesController(CreerBenevole creerBenevole, ListerBenevoles listerBenevoles) {
        this.creerBenevole = creerBenevole;
        this.listerBenevoles = listerBenevoles;
    }

    /** Principal : identifiant du compte de l'admin connecté (voir {@link SessionConnexion}). */
    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public CompteReponse creerBenevole(@RequestBody CreerBenevoleRequete requete,
                                       @AuthenticationPrincipal UUID idAdmin) {
        Compte benevole = creerBenevole.executer(
                new CreerBenevole.Commande(requete.pseudo(), requete.motDePasse()));
        JOURNAL.info("Bénévole créé (compte {} par {})", benevole.id(), idAdmin);
        return CompteReponse.depuis(benevole);
    }

    @GetMapping
    public List<CompteReponse> listerBenevoles() {
        return listerBenevoles.executer().stream().map(CompteReponse::depuis).toList();
    }
}
