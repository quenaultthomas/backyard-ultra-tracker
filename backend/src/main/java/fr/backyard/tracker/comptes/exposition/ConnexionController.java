package fr.backyard.tracker.comptes.exposition;

import fr.backyard.tracker.comptes.application.Connecter;
import fr.backyard.tracker.comptes.domaine.Compte;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class ConnexionController {

    private static final Logger JOURNAL = LoggerFactory.getLogger(ConnexionController.class);

    private final Connecter connecter;
    private final SessionConnexion sessionConnexion;

    public ConnexionController(Connecter connecter, SessionConnexion sessionConnexion) {
        this.connecter = connecter;
        this.sessionConnexion = sessionConnexion;
    }

    @PostMapping(path = "/api/connexion", consumes = MediaType.APPLICATION_JSON_VALUE)
    public CompteReponse connecter(@RequestBody ConnexionRequete requete, HttpServletRequest requeteHttp,
                                   HttpServletResponse reponseHttp) {
        Compte compte = connecter.executer(requete.pseudo(), requete.motDePasse());
        sessionConnexion.ouvrir(compte, requeteHttp, reponseHttp);
        JOURNAL.info("Connexion du compte {}", compte.id());
        return CompteReponse.depuis(compte);
    }

    @PostMapping("/api/deconnexion")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deconnecter(HttpServletRequest requeteHttp, HttpServletResponse reponseHttp) {
        sessionConnexion.fermer(requeteHttp, reponseHttp);
    }
}
