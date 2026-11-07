package fr.backyard.tracker.comptes.domaine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import fr.backyard.tracker.partage.domaine.FormatImage;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * La photo, attribut du Compte (10.1a, CA3, RG1, RG3, RG9, RG10). Le Compte ne porte que le jeton et le format
 * ({@link ReferencePhoto}), jamais les octets. Convention : {@code compte.photo()} vaut null sans photo.
 */
class ComptePhotoTest {

    private static final Instant CREE_LE = Instant.parse("2026-10-07T09:00:00Z");
    private static final String JETON = "3f2a9c1d7b64e8a05c21d4f6b9a7e310";
    private static final String AUTRE_JETON = "00112233445566778899aabbccddeeff";

    private static Compte compte(Role role) {
        return Compte.reconstituer(UUID.randomUUID(), new Pseudo("Karim"), "empreinte", role, CREE_LE, true);
    }

    private static Compte compteAvecPhoto(Role role) {
        return Compte.reconstituer(UUID.randomUUID(), new Pseudo("Karim"), "empreinte", role, CREE_LE, true,
                new ReferencePhoto(JETON, FormatImage.PNG));
    }

    // ---------- CA3 : définir ----------

    @Test
    @DisplayName("CA3 - un coureur qui définit une photo garde id, pseudo, empreinte, rôle, création et état actif")
    void doit_definir_la_photo_d_un_coureur_sans_changer_le_reste() {
        Compte coureur = compte(Role.COUREUR);

        Compte avecPhoto = coureur.avecPhoto(JETON, FormatImage.JPEG);

        assertThat(avecPhoto.photo()).isEqualTo(new ReferencePhoto(JETON, FormatImage.JPEG));
        assertThat(avecPhoto.photo().jeton()).isEqualTo(JETON);
        assertThat(avecPhoto.photo().format()).isEqualTo(FormatImage.JPEG);
        assertThat(avecPhoto.id()).isEqualTo(coureur.id());
        assertThat(avecPhoto.pseudo()).isEqualTo(coureur.pseudo());
        assertThat(avecPhoto.empreinteMotDePasse()).isEqualTo("empreinte");
        assertThat(avecPhoto.role()).isEqualTo(Role.COUREUR);
        assertThat(avecPhoto.creeLe()).isEqualTo(CREE_LE);
        assertThat(avecPhoto.actif()).isTrue();
    }

    @Test
    @DisplayName("CA3 - définir une photo ne modifie pas le Compte d'origine")
    void doit_laisser_le_compte_d_origine_intact() {
        Compte coureur = compte(Role.COUREUR);

        coureur.avecPhoto(JETON, FormatImage.PNG);

        assertThat(coureur.photo()).isNull();
    }

    @Test
    @DisplayName("CA3 - un coureur qui a déjà une photo la remplace : un seul jeton, le nouveau")
    void doit_remplacer_la_photo_existante() {
        Compte coureur = compteAvecPhoto(Role.COUREUR);

        Compte remplace = coureur.avecPhoto(AUTRE_JETON, FormatImage.WEBP);

        assertThat(remplace.photo()).isEqualTo(new ReferencePhoto(AUTRE_JETON, FormatImage.WEBP));
        assertThat(coureur.photo().jeton()).isEqualTo(JETON);
    }

    @ParameterizedTest(name = "CA3 - {0} ne peut pas définir de photo")
    @EnumSource(value = Role.class, names = {"BENEVOLE", "ADMIN", "ADMIN_MASTER"})
    @DisplayName("CA3 - un BENEVOLE, un ADMIN ou l'ADMIN_MASTER ne peut pas définir de photo")
    void doit_refuser_la_photo_a_un_compte_qui_n_est_pas_coureur(Role role) {
        Compte compte = compte(role);

        assertThatThrownBy(() -> compte.avecPhoto(JETON, FormatImage.PNG))
                .isInstanceOf(PhotoNonAutoriseeException.class);

        assertThat(compte.photo()).isNull();
    }

    @ParameterizedTest(name = "CA3 - {0} ex-coureur remplaçant sa photo est refusé")
    @EnumSource(value = Role.class, names = {"BENEVOLE", "ADMIN"})
    @DisplayName("CA3 - un BENEVOLE ou ADMIN ex-coureur garde sa photo mais ne peut plus la remplacer")
    void doit_refuser_le_remplacement_a_un_ex_coureur(Role role) {
        Compte exCoureur = compteAvecPhoto(role);

        assertThatThrownBy(() -> exCoureur.avecPhoto(AUTRE_JETON, FormatImage.PNG))
                .isInstanceOf(PhotoNonAutoriseeException.class);

        assertThat(exCoureur.photo().jeton()).isEqualTo(JETON);
    }

    // ---------- CA3 : retirer ----------

    @Test
    @DisplayName("CA3 - retirerPhoto met la photo à nulle et garde le reste du Compte")
    void doit_retirer_la_photo() {
        Compte coureur = compteAvecPhoto(Role.COUREUR);

        Compte sansPhoto = coureur.retirerPhoto();

        assertThat(sansPhoto.photo()).isNull();
        assertThat(sansPhoto.id()).isEqualTo(coureur.id());
        assertThat(sansPhoto.pseudo()).isEqualTo(coureur.pseudo());
        assertThat(sansPhoto.role()).isEqualTo(Role.COUREUR);
        assertThat(coureur.photo()).isNotNull();
    }

    @Test
    @DisplayName("CA3 - retirerPhoto est idempotent : sans photo, le Compte reste sans photo, sans erreur")
    void doit_retirer_sans_erreur_quand_il_n_y_a_pas_de_photo() {
        Compte coureur = compte(Role.COUREUR);

        assertThat(coureur.retirerPhoto().photo()).isNull();
        assertThat(coureur.retirerPhoto().retirerPhoto().photo()).isNull();
    }

    @ParameterizedTest(name = "CA3 - {0} ex-coureur peut retirer sa photo")
    @EnumSource(value = Role.class, names = {"BENEVOLE", "ADMIN"})
    @DisplayName("CA3 - un BENEVOLE ou ADMIN ex-coureur peut retirer sa photo (RG1)")
    void doit_permettre_le_retrait_a_un_ex_coureur(Role role) {
        Compte exCoureur = compteAvecPhoto(role);

        assertThat(exCoureur.retirerPhoto().photo()).isNull();
    }

    // ---------- CA3 : anonymisation et conservation ----------

    @Test
    @DisplayName("CA3 - anonymiser un coureur avec photo rend un Compte sans photo")
    void doit_anonymiser_un_coureur_sans_photo() {
        Compte anonyme = compteAvecPhoto(Role.COUREUR).anonymiser();

        assertThat(anonyme.photo()).isNull();
        assertThat(anonyme.estAnonyme()).isTrue();
    }

    @ParameterizedTest(name = "CA3 - {0} ex-coureur anonymisé n'a plus de photo")
    @EnumSource(value = Role.class, names = {"BENEVOLE", "ADMIN"})
    @DisplayName("CA3 - anonymiser un bénévole ou un admin ex-coureur avec photo rend un Compte sans photo (RG9)")
    void doit_anonymiser_un_benevole_ou_admin_sans_photo(Role role) {
        Compte anonyme = compteAvecPhoto(role).anonymiserBenevoleOuAdmin();

        assertThat(anonyme.photo()).isNull();
        assertThat(anonyme.estAnonyme()).isTrue();
    }

    @Test
    @DisplayName("CA3 - le changement de rôle d'un coureur avec photo la conserve (RG10)")
    void doit_conserver_la_photo_au_changement_de_role() {
        Compte coureur = compteAvecPhoto(Role.COUREUR);

        assertThat(coureur.passerEnBenevole().photo()).isEqualTo(coureur.photo());
        assertThat(coureur.passerEnAdmin().photo()).isEqualTo(coureur.photo());
    }

    @Test
    @DisplayName("CA3 - désactivation, réactivation et changement de mot de passe conservent la photo (RG10)")
    void doit_conserver_la_photo_a_la_desactivation_et_au_changement_de_mot_de_passe() {
        Compte benevole = compteAvecPhoto(Role.BENEVOLE);

        assertThat(benevole.desactiver().photo()).isEqualTo(benevole.photo());
        assertThat(benevole.desactiver().reactiver().photo()).isEqualTo(benevole.photo());
        assertThat(compteAvecPhoto(Role.COUREUR).changerMotDePasse("nouvelle").photo()).isNotNull();
    }

    // ---------- CA3 : jeton ----------

    @Test
    @DisplayName("CA3 - un jeton généré fait 32 caractères hexadécimaux minuscules")
    void doit_generer_un_jeton_de_32_hexadecimaux_minuscules() {
        assertThat(JetonPhoto.generer()).matches("^[0-9a-f]{32}$");
    }

    @Test
    @DisplayName("CA3 - deux jetons consécutifs diffèrent et ne sont jamais l'identifiant du Compte")
    void doit_generer_des_jetons_distincts_de_l_identifiant_du_compte() {
        UUID id = UUID.randomUUID();
        String premier = JetonPhoto.generer();
        String second = JetonPhoto.generer();

        assertThat(premier).isNotEqualTo(second);
        assertThat(premier).isNotEqualTo(id.toString()).isNotEqualTo(id.toString().replace("-", ""));
        assertThat(second).isNotEqualTo(id.toString()).isNotEqualTo(id.toString().replace("-", ""));
    }

    @Test
    @DisplayName("CA3 - JetonPhoto.estValide accepte 32 hexadécimaux minuscules et rien d'autre")
    void doit_valider_le_format_du_jeton() {
        assertThat(JetonPhoto.estValide(JETON)).isTrue();
        assertThat(JetonPhoto.estValide(JETON.toUpperCase())).isFalse();
        assertThat(JetonPhoto.estValide(JETON.substring(1))).isFalse();
        assertThat(JetonPhoto.estValide(JETON + "0")).isFalse();
        assertThat(JetonPhoto.estValide(JETON + ".png")).isFalse();
        assertThat(JetonPhoto.estValide("..")).isFalse();
        assertThat(JetonPhoto.estValide("")).isFalse();
        assertThat(JetonPhoto.estValide(null)).isFalse();
        assertThat(JetonPhoto.estValide("g".repeat(32))).isFalse();
    }
}
