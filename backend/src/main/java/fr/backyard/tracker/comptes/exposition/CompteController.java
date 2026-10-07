package fr.backyard.tracker.comptes.exposition;

import fr.backyard.tracker.comptes.application.ChangerMotDePasse;
import fr.backyard.tracker.comptes.application.ConsulterCompteConnecte;
import fr.backyard.tracker.comptes.application.CreerCompteCoureur;
import fr.backyard.tracker.comptes.application.SupprimerCompteCoureur;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/comptes")
public class CompteController {

    private final CreerCompteCoureur creerCompteCoureur;
    private final ConsulterCompteConnecte consulterCompteConnecte;
    private final ChangerMotDePasse changerMotDePasse;
    private final SupprimerCompteCoureur supprimerCompteCoureur;
    private final SessionConnexion sessionConnexion;

    public CompteController(CreerCompteCoureur creerCompteCoureur, ConsulterCompteConnecte consulterCompteConnecte,
                            ChangerMotDePasse changerMotDePasse, SupprimerCompteCoureur supprimerCompteCoureur,
                            SessionConnexion sessionConnexion) {
        this.creerCompteCoureur = creerCompteCoureur;
        this.consulterCompteConnecte = consulterCompteConnecte;
        this.changerMotDePasse = changerMotDePasse;
        this.supprimerCompteCoureur = supprimerCompteCoureur;
        this.sessionConnexion = sessionConnexion;
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

    /**
     * Change le mot de passe du compte de la session (jamais d'un autre) ; les autres sessions du compte
     * sont fermées par le cas d'usage, la session en cours reste ouverte avec un nouvel identifiant.
     */
    @PutMapping(path = "/moi/mot-de-passe", consumes = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void changerMotDePasse(@RequestBody ChangerMotDePasseRequete requete,
                                  @AuthenticationPrincipal UUID idCompte, HttpServletRequest requeteHttp) {
        changerMotDePasse.executer(new ChangerMotDePasse.Commande(
                idCompte, requete.motDePasseActuel(), requete.nouveauMotDePasse()));
        sessionConnexion.renouvelerIdentifiant(requeteHttp);
    }

    /**
     * Supprime (anonymise) le compte coureur de la session, jamais un autre ; toutes ses sessions sont fermées par le
     * cas d'usage, le cookie de la session en cours est effacé.
     */
    @PostMapping(path = "/moi/suppression", consumes = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void supprimerCompte(@RequestBody SupprimerCompteRequete requete, @AuthenticationPrincipal UUID idCompte,
                                HttpServletRequest requeteHttp, HttpServletResponse reponseHttp) {
        supprimerCompteCoureur.executer(new SupprimerCompteCoureur.Commande(idCompte, requete.motDePasseActuel()));
        sessionConnexion.fermer(requeteHttp, reponseHttp);
    }
}
