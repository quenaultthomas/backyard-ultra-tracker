package fr.backyard.tracker.comptes.exposition;

import fr.backyard.tracker.comptes.application.EnregistrerPhoto;
import fr.backyard.tracker.comptes.application.RetirerPhoto;
import java.io.IOException;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * Photo de profil du Compte de la session, jamais d'un autre (aucun identifiant reçu). L'envoi est réservé à COUREUR par
 * la politique de sécurité, le retrait ouvert à tout Compte connecté. Seuls les octets de la partie « fichier » sont
 * lus : ni son nom ni son type déclaré ne sont consultés ; le journal est écrit par les cas d'usage.
 */
@RestController
@RequestMapping("/api/comptes/moi/photo")
public class PhotoController {

    private final EnregistrerPhoto enregistrerPhoto;
    private final RetirerPhoto retirerPhoto;

    public PhotoController(EnregistrerPhoto enregistrerPhoto, RetirerPhoto retirerPhoto) {
        this.enregistrerPhoto = enregistrerPhoto;
        this.retirerPhoto = retirerPhoto;
    }

    /** Partie absente ou vide : refusée par le domaine (PHOTO_REQUISE). */
    @PutMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public PhotoReponse enregistrerPhoto(@RequestPart(name = "fichier", required = false) MultipartFile fichier,
                                         @AuthenticationPrincipal UUID idCompte) throws IOException {
        return PhotoReponse.depuis(enregistrerPhoto.executer(idCompte, fichier == null ? null : fichier.getBytes()));
    }

    @DeleteMapping
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void retirerPhoto(@AuthenticationPrincipal UUID idCompte) {
        retirerPhoto.executer(idCompte);
    }
}
