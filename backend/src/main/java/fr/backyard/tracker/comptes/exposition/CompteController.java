package fr.backyard.tracker.comptes.exposition;

import fr.backyard.tracker.comptes.application.CreerCompteCoureur;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/comptes")
public class CompteController {

    private final CreerCompteCoureur creerCompteCoureur;

    public CompteController(CreerCompteCoureur creerCompteCoureur) {
        this.creerCompteCoureur = creerCompteCoureur;
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public CompteReponse creerCompteCoureur(@RequestBody CreerCompteRequete requete) {
        return CompteReponse.depuis(creerCompteCoureur.executer(requete.pseudo(), requete.motDePasse()));
    }
}
