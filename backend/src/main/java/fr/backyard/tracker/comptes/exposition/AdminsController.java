package fr.backyard.tracker.comptes.exposition;

import fr.backyard.tracker.comptes.application.CreerAdmin;
import fr.backyard.tracker.comptes.application.ListerAdmins;
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
 * Gestion des comptes ADMIN. Réservé à ADMIN_MASTER par la politique de sécurité, avant ce contrôleur.
 * Le journal ne contient que des identifiants, jamais de pseudo ni de mot de passe.
 */
@RestController
@RequestMapping("/api/administration/admins")
public class AdminsController {

    private static final Logger JOURNAL = LoggerFactory.getLogger(AdminsController.class);

    private final CreerAdmin creerAdmin;
    private final ListerAdmins listerAdmins;

    public AdminsController(CreerAdmin creerAdmin, ListerAdmins listerAdmins) {
        this.creerAdmin = creerAdmin;
        this.listerAdmins = listerAdmins;
    }

    /** Principal : identifiant du compte de l'admin master (voir {@link SessionConnexion}). */
    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public CompteReponse creerAdmin(@RequestBody CreerAdminRequete requete,
                                    @AuthenticationPrincipal UUID idAdminMaster) {
        Compte admin = creerAdmin.executer(new CreerAdmin.Commande(requete.pseudo(), requete.motDePasse()));
        JOURNAL.info("Admin créé (compte {} par {})", admin.id(), idAdminMaster);
        return CompteReponse.depuis(admin);
    }

    @GetMapping
    public List<CompteReponse> listerAdmins() {
        return listerAdmins.executer().stream().map(CompteReponse::depuis).toList();
    }
}
