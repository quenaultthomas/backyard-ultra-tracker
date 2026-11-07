package fr.backyard.tracker.comptes.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Contrat de l'incrément 10.1a : effacement de la photo aux trois suppressions (coureur 3.6, bénévole 9.3, admin 9.4),
 * y compris en cas d'anonymisation, et conservation si la suppression est refusée (CA14, CA15, CA16).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "backyard.admin-master.pseudo=Patron", "backyard.admin-master.mot-de-passe=mot-de-passe-patron-1"})
@Testcontainers
class PhotoSuppressionsIntegrationTest extends PhotoIntegrationBase {

    static final Path REPERTOIRE = repertoireTemporaire();
    static final String ANONYME = "Coureur anonyme";

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17.11-alpine");

    @DynamicPropertySource
    static void proprietes(DynamicPropertyRegistry registre) {
        registre.add(PROPRIETE_REPERTOIRE, REPERTOIRE::toString);
    }

    @Override
    Path repertoire() {
        return REPERTOIRE;
    }

    private static Path repertoireTemporaire() {
        try {
            return Files.createTempDirectory("photos-it-suppressions").resolve("photos");
        } catch (IOException echec) {
            throw new IllegalStateException(echec);
        }
    }

    // ---------------------------------------------------------------- CA14

    @Test
    @DisplayName("CA14 : Coureur2 avec photo, Inscriptions EN_PREPARATION et TERMINEE supprime son Compte : 204, anonymisé, colonnes nulles, fichier disparu, URL 404, Inscription TERMINEE et Passage conservés")
    void ca14_suppression_du_compte_par_le_coureur() throws Exception {
        byte[] photo = OutilsPhoto.png(400);
        String jeton = poserPhoto(coureur2, photo);
        String jetonAutre = poserPhoto(coureur1, OutilsPhoto.png(300));
        UUID preparation = insererCourse("Course preparation", "EN_PREPARATION");
        UUID terminee = insererCourse("Course terminee", "TERMINEE");
        inscrire(preparation, idCoureur2, "EN_COURSE");
        UUID inscription = inscrire(terminee, idCoureur2, "EN_COURSE");
        jdbc.update("insert into passage (id, inscription_id, numero_boucle, scanne_le, origine) "
                + "values (?, ?, 1, ?, 'SCAN')", UUID.randomUUID(), inscription,
                Timestamp.from(Instant.parse("2026-10-10T08:50:00Z")));
        Map<String, Object> inscriptionAvant = jdbc.queryForMap("select * from inscription where id = ?", inscription);
        Map<String, Object> passageAvant = jdbc.queryForMap("select * from passage where inscription_id = ?",
                inscription);

        HttpResponse<byte[]> reponse = supprimer(coureur2, MOT_DE_PASSE);

        assertThat(reponse.statusCode()).isEqualTo(204);
        Map<String, Object> compte = jdbc.queryForMap("select * from compte where id = ?", idCoureur2);
        assertThat(compte.get("pseudo")).isEqualTo(ANONYME);
        assertThat(compte.get("empreinte_mot_de_passe")).isNull();
        assertThat(compte.get("photo_jeton")).isNull();
        assertThat(compte.get("photo_format")).isNull();
        assertThat(OutilsPhoto.noms(REPERTOIRE)).containsExactly(jetonAutre + ".png");
        assertIntrouvable(jeton);
        assertThat(jdbc.queryForObject("select count(*) from inscription where compte_id = ? and course_id = ?",
                Integer.class, idCoureur2, preparation)).isZero();
        assertThat(jdbc.queryForMap("select * from inscription where id = ?", inscription))
                .isEqualTo(inscriptionAvant);
        assertThat(jdbc.queryForMap("select * from passage where inscription_id = ?", inscription))
                .isEqualTo(passageAvant);
        assertServie(jetonAutre, OutilsPhoto.png(300));
    }

    @Test
    @DisplayName("CA14 : un coureur sans Inscription avec photo est anonymisé aussi et son fichier disparaît ; un pseudo libéré réutilisé n'a aucune photo")
    void ca14_sans_inscription_et_pseudo_libere() throws Exception {
        String jeton = poserPhoto(coureur3, OutilsPhoto.jpeg(300));

        assertThat(supprimer(coureur3, MOT_DE_PASSE).statusCode()).isEqualTo(204);

        assertThat(jdbc.queryForObject("select pseudo from compte where id = ?", String.class, idCoureur3))
                .isEqualTo(ANONYME);
        assertThat(OutilsPhoto.noms(REPERTOIRE)).isEmpty();
        assertIntrouvable(jeton);
        UUID nouveau = insererCompte("Coureur3", "COUREUR");
        assertThat(colonnesPhoto(nouveau)).containsEntry("photo_jeton", null);
        assertThat(photoUrlMoi(api.ouvrir("Coureur3", MOT_DE_PASSE))).isNull();
    }

    @Test
    @DisplayName("CA14 : mot de passe faux (400) ou session absente (401) : photo et fichier intacts")
    void ca14_refus_laisse_la_photo_intacte() throws Exception {
        byte[] photo = OutilsPhoto.png(400);
        String jeton = poserPhoto(coureur1, photo);

        HttpResponse<byte[]> faux = supprimer(coureur1, "mauvais-mot-de-passe-1");
        assertThat(faux.statusCode()).isEqualTo(400);
        HttpResponse<byte[]> anonyme = api.requete("POST", "/api/comptes/moi/suppression",
                api.entetesAnonymesAvecCsrf(), "application/json", corpsSuppression(MOT_DE_PASSE));
        assertThat(anonyme.statusCode()).isEqualTo(401);
        HttpResponse<byte[]> autreRole = supprimer(leo, MOT_DE_PASSE);
        assertThat(autreRole.statusCode()).isEqualTo(403);

        assertThat(colonnesPhoto(idCoureur1)).containsEntry("photo_jeton", jeton);
        assertThat(OutilsPhoto.noms(REPERTOIRE)).containsExactly(jeton + ".png");
        assertServie(jeton, photo);
        assertThat(jdbc.queryForObject("select pseudo from compte where id = ?", String.class, idCoureur1))
                .isEqualTo("Coureur1");
    }

    // ---------------------------------------------------------------- CA15

    @Test
    @DisplayName("CA15 : Nadia supprime un bénévole ex-coureur avec photo et sans Inscription : 204, ligne supprimée, fichier effacé")
    void ca15_benevole_sans_inscription() throws Exception {
        UUID benevole = insererCompte("Marc", "BENEVOLE");
        String jeton = poserPhotoEnBase(benevole, OutilsPhoto.jpeg(300));

        HttpResponse<byte[]> reponse = supprimerBenevole(nadia, benevole);

        assertThat(reponse.statusCode()).isEqualTo(204);
        assertThat(existe(benevole)).isFalse();
        assertThat(OutilsPhoto.noms(REPERTOIRE)).isEmpty();
        assertIntrouvable(jeton);
    }

    @Test
    @DisplayName("CA15 : bénévole ex-coureur avec photo et une Inscription TERMINEE : 204, Compte anonymisé sans photo, fichier effacé, Inscription conservée")
    void ca15_benevole_avec_inscription_terminee() throws Exception {
        UUID benevole = insererCompte("Marc", "BENEVOLE");
        String jeton = poserPhotoEnBase(benevole, OutilsPhoto.jpeg(300));
        UUID terminee = insererCourse("Course terminee", "TERMINEE");
        UUID inscription = inscrire(terminee, benevole, "EN_COURSE");

        HttpResponse<byte[]> reponse = supprimerBenevole(nadia, benevole);

        assertThat(reponse.statusCode()).isEqualTo(204);
        assertThat(existe(benevole)).isTrue();
        Map<String, Object> compte = jdbc.queryForMap("select * from compte where id = ?", benevole);
        assertThat(compte.get("pseudo")).isEqualTo(ANONYME);
        assertThat(compte.get("empreinte_mot_de_passe")).isNull();
        assertThat(compte.get("photo_jeton")).isNull();
        assertThat(compte.get("photo_format")).isNull();
        assertThat(OutilsPhoto.noms(REPERTOIRE)).isEmpty();
        assertIntrouvable(jeton);
        assertThat(jdbc.queryForObject("select count(*) from inscription where id = ?", Integer.class, inscription))
                .isEqualTo(1);
    }

    @Test
    @DisplayName("CA15 : bénévole affecté à une Course EN_COURS : 409 BENEVOLE_AFFECTE_COURSE_EN_COURS, photo et fichier intacts, URL toujours servie ; 403 pour un coureur")
    void ca15_refus_laisse_la_photo_intacte() throws Exception {
        UUID benevole = insererCompte("Marc", "BENEVOLE");
        byte[] photo = OutilsPhoto.jpeg(300);
        String jeton = poserPhotoEnBase(benevole, photo);
        UUID enCours = insererCourse("Course en cours", "EN_COURS");
        affecter(enCours, benevole);

        assertProbleme(supprimerBenevole(nadia, benevole), 409, "BENEVOLE_AFFECTE_COURSE_EN_COURS");
        assertProbleme(supprimerBenevole(coureur1, benevole), 403, "ACCES_REFUSE");

        assertThat(existe(benevole)).isTrue();
        assertThat(colonnesPhoto(benevole)).containsEntry("photo_jeton", jeton).containsEntry("photo_format", "JPEG");
        assertThat(OutilsPhoto.noms(REPERTOIRE)).containsExactly(jeton + ".jpg");
        assertServie(jeton, photo);
    }

    // ---------------------------------------------------------------- CA16

    @Test
    @DisplayName("CA16 : Patron supprime un admin ex-coureur avec photo et sans Inscription : 204, fichier effacé ; avec une Inscription TERMINEE : anonymisé, fichier effacé")
    void ca16_admin_supprime_par_admin_master() throws Exception {
        UUID sansInscription = insererCompte("Paul", "ADMIN");
        String premier = poserPhotoEnBase(sansInscription, OutilsPhoto.jpeg(300));

        assertThat(supprimerAdmin(patron, sansInscription).statusCode()).isEqualTo(204);

        assertThat(existe(sansInscription)).isFalse();
        assertThat(OutilsPhoto.noms(REPERTOIRE)).isEmpty();
        assertIntrouvable(premier);

        UUID avecInscription = insererCompte("Pierre", "ADMIN");
        String second = poserPhotoEnBase(avecInscription, OutilsPhoto.jpeg(300));
        inscrire(insererCourse("Course terminee", "TERMINEE"), avecInscription, "EN_COURSE");

        assertThat(supprimerAdmin(patron, avecInscription).statusCode()).isEqualTo(204);

        Map<String, Object> compte = jdbc.queryForMap("select * from compte where id = ?", avecInscription);
        assertThat(compte.get("pseudo")).isEqualTo(ANONYME);
        assertThat(compte.get("photo_jeton")).isNull();
        assertThat(OutilsPhoto.noms(REPERTOIRE)).isEmpty();
        assertIntrouvable(second);
    }

    @Test
    @DisplayName("CA16 : Nadia (admin) appelante : 403 ACCES_REFUSE ; dernier admin actif : 409 DERNIER_ADMIN_ACTIF ; dans les deux cas photo et fichier intacts")
    void ca16_refus_laisse_la_photo_intacte() throws Exception {
        UUID paul = insererCompte("Paul", "ADMIN");
        byte[] photo = OutilsPhoto.jpeg(300);
        String jeton = poserPhotoEnBase(paul, photo);

        assertProbleme(supprimerAdmin(nadia, paul), 403, "ACCES_REFUSE");
        verifierIntacte(paul, jeton, photo);

        jdbc.update("update compte set actif = false where id = ?", idNadia);
        assertProbleme(supprimerAdmin(patron, paul), 409, "DERNIER_ADMIN_ACTIF");
        verifierIntacte(paul, jeton, photo);
    }

    private void verifierIntacte(UUID compte, String jeton, byte[] photo) throws Exception {
        assertThat(existe(compte)).isTrue();
        assertThat(colonnesPhoto(compte)).containsEntry("photo_jeton", jeton);
        assertThat(OutilsPhoto.noms(REPERTOIRE)).containsExactly(jeton + ".jpg");
        assertServie(jeton, photo);
    }

    // ---------------------------------------------------------------- outils

    private HttpResponse<byte[]> supprimer(OutilsPhoto.Session session, String motDePasse) throws Exception {
        return api.requete("POST", "/api/comptes/moi/suppression", session.entetes(), "application/json",
                corpsSuppression(motDePasse));
    }

    private static byte[] corpsSuppression(String motDePasse) {
        return OutilsPhoto.texte("{\"motDePasseActuel\":\"" + motDePasse + "\"}");
    }

    private HttpResponse<byte[]> supprimerBenevole(OutilsPhoto.Session session, UUID id) throws Exception {
        return api.requete("DELETE", "/api/administration/benevoles/" + id, session.entetes(), null, null);
    }

    private HttpResponse<byte[]> supprimerAdmin(OutilsPhoto.Session session, UUID id) throws Exception {
        return api.requete("DELETE", "/api/administration/admins/" + id, session.entetes(), null, null);
    }
}
