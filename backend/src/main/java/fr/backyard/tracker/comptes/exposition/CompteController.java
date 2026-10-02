package fr.backyard.tracker.comptes.exposition;

import fr.backyard.tracker.comptes.application.ConsulterCompteConnecte;
import fr.backyard.tracker.comptes.application.CreerCompteCoureur;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/comptes")
public class CompteController {

    private final CreerCompteCoureur creerCompteCoureur;
    private final ConsulterCompteConnecte consulterCompteConnecte;

    public CompteController(CreerCompteCoureur creerCompteCoureur, ConsulterCompteConnecte consulterCompteConnecte) {
        this.creerCompteCoureur = creerCompteCoureur;
        this.consulterCompteConnecte = consulterCompteConnecte;
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public CompteReponse creerCompteCoureur(@RequestBody CreerCompteRequete requete) {
        return CompteReponse.depuis(creerCompteCoureur.executer(requete.pseudo(), requete.motDePasse()));
    }

    /** Compte de la session, relu en base. Principal : identifiant du compte (voir {@link SessionConnexion}). */
    @GetMapping("/moi")
    public CompteReponse consulterCompteConnecte(@AuthenticationPrincipal UUID idCompte) {
        return CompteReponse.depuis(consulterCompteConnecte.executer(idCompte));
    }
}
