package fr.backyard.tracker.courses.exposition;

import fr.backyard.tracker.courses.application.EnregistrerLogo;
import fr.backyard.tracker.courses.application.SupprimerLogo;
import java.io.IOException;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * Envoi, remplacement et suppression du logo d'une Course. Réservé à ADMIN et ADMIN_MASTER par la politique de
 * sécurité (/api/administration/**). Seuls les octets de la partie « fichier » sont lus : ni son nom ni son type
 * déclaré ne sont consultés, et le journal ne contient que l'identifiant de la Course.
 */
@RestController
@RequestMapping("/api/administration/courses/{id}/logo")
public class LogoCourseController {

    private static final Logger JOURNAL = LoggerFactory.getLogger(LogoCourseController.class);

    private final EnregistrerLogo enregistrerLogo;
    private final SupprimerLogo supprimerLogo;

    public LogoCourseController(EnregistrerLogo enregistrerLogo, SupprimerLogo supprimerLogo) {
        this.enregistrerLogo = enregistrerLogo;
        this.supprimerLogo = supprimerLogo;
    }

    /** Le corps multipart (taille, forme) est lu avant l'identifiant ; une partie absente est refusée par le domaine. */
    @PutMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public CourseReponse enregistrerLogo(@PathVariable("id") String id,
                                         @RequestPart(name = "fichier", required = false) MultipartFile fichier)
            throws IOException {
        EnregistrerLogo.Resultat resultat = enregistrerLogo.executer(IdentifiantCourse.deCourse(id), octets(fichier));
        JOURNAL.info("Logo de la course enregistré (course {})", resultat.course().id());
        return CourseReponse.depuis(resultat.course(), Optional.of(resultat.empreinte()));
    }

    @DeleteMapping
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void supprimerLogo(@PathVariable("id") String id) {
        UUID idCourse = IdentifiantCourse.deCourse(id);
        if (supprimerLogo.executer(idCourse)) {
            JOURNAL.info("Logo de la course supprimé (course {})", idCourse);
        }
    }

    private static byte[] octets(MultipartFile fichier) throws IOException {
        return fichier == null ? null : fichier.getBytes();
    }
}
