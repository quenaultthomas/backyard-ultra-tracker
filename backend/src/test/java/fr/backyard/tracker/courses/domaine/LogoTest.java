package fr.backyard.tracker.courses.domaine;

import static fr.backyard.tracker.courses.OctetsDeLogo.TAILLE_MAXIMALE;
import static fr.backyard.tracker.courses.OctetsDeLogo.ascii;
import static fr.backyard.tracker.courses.OctetsDeLogo.concatener;
import static fr.backyard.tracker.courses.OctetsDeLogo.jpeg;
import static fr.backyard.tracker.courses.OctetsDeLogo.png;
import static fr.backyard.tracker.courses.OctetsDeLogo.pngDeTaille;
import static fr.backyard.tracker.courses.OctetsDeLogo.sha256;
import static fr.backyard.tracker.courses.OctetsDeLogo.texteDeTaille;
import static fr.backyard.tracker.courses.OctetsDeLogo.webp;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import fr.backyard.tracker.courses.domaine.LogoInvalideException.Motif;
import java.util.Arrays;
import java.util.Base64;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Tests de l'incrément 2.3 : value object Logo (CA1, CA2, CA3). Aucun octet n'est lu depuis un fichier. */
class LogoTest {

    // ---------- CA1 ----------

    @Test
    @DisplayName("CA1 - un PNG (signature + 100 octets) donne le format PNG, image/png, sa taille et son SHA-256")
    void doit_creer_un_logo_png_avec_format_type_mime_taille_et_empreinte() {
        byte[] octets = png();

        Logo logo = Logo.depuis(octets);

        assertThat(logo.format()).isEqualTo(FormatLogo.PNG);
        assertThat(logo.typeMime()).isEqualTo("image/png");
        assertThat(logo.taille()).isEqualTo(octets.length).isEqualTo(108);
        assertThat(logo.empreinte()).hasSize(64).matches("[0-9a-f]{64}").isEqualTo(sha256(octets));
    }

    @Test
    @DisplayName("CA1 - un JPEG (FF D8 FF E0 + 100 octets) donne le format JPEG, image/jpeg, sa taille et son SHA-256")
    void doit_creer_un_logo_jpeg_avec_format_type_mime_taille_et_empreinte() {
        byte[] octets = jpeg();

        Logo logo = Logo.depuis(octets);

        assertThat(logo.format()).isEqualTo(FormatLogo.JPEG);
        assertThat(logo.typeMime()).isEqualTo("image/jpeg");
        assertThat(logo.taille()).isEqualTo(octets.length).isEqualTo(104);
        assertThat(logo.empreinte()).matches("[0-9a-f]{64}").isEqualTo(sha256(octets));
    }

    @Test
    @DisplayName("CA1 - un WebP (RIFF, 4 octets, WEBP, 100 octets) donne le format WEBP, image/webp, sa taille et son SHA-256")
    void doit_creer_un_logo_webp_avec_format_type_mime_taille_et_empreinte() {
        byte[] octets = webp();

        Logo logo = Logo.depuis(octets);

        assertThat(logo.format()).isEqualTo(FormatLogo.WEBP);
        assertThat(logo.typeMime()).isEqualTo("image/webp");
        assertThat(logo.taille()).isEqualTo(octets.length).isEqualTo(112);
        assertThat(logo.empreinte()).matches("[0-9a-f]{64}").isEqualTo(sha256(octets));
    }

    @Test
    @DisplayName("CA1 - deux contenus identiques ont la même empreinte")
    void doit_donner_la_meme_empreinte_a_deux_contenus_identiques() {
        assertThat(Logo.depuis(png()).empreinte()).isEqualTo(Logo.depuis(png()).empreinte());
    }

    @Test
    @DisplayName("CA1 - un seul octet différent change l'empreinte")
    void doit_changer_d_empreinte_quand_un_octet_change() {
        byte[] modifie = png();
        modifie[50] = (byte) (modifie[50] + 1);

        assertThat(Logo.depuis(modifie).empreinte()).isNotEqualTo(Logo.depuis(png()).empreinte());
    }

    @Test
    @DisplayName("CA1 - exactement 2 097 152 octets à signature PNG valide : accepté")
    void doit_accepter_un_logo_de_exactement_2_mo() {
        Logo logo = Logo.depuis(pngDeTaille(TAILLE_MAXIMALE));

        assertThat(logo.taille()).isEqualTo(2_097_152);
        assertThat(logo.format()).isEqualTo(FormatLogo.PNG);
    }

    @Test
    @DisplayName("CA1 - la signature seule (PNG 8 octets, JPEG 3 octets) suffit")
    void doit_accepter_un_contenu_reduit_a_sa_signature() {
        assertThat(Logo.depuis(png(new byte[0])).format()).isEqualTo(FormatLogo.PNG);
        assertThat(Logo.depuis(new byte[] {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF}).format())
                .isEqualTo(FormatLogo.JPEG);
    }

    // ---------- CA2 ----------

    @Test
    @DisplayName("CA2 - octets null : LogoInvalideException motif REQUIS")
    void doit_refuser_des_octets_null_avec_le_motif_requis() {
        assertMotif(null, Motif.REQUIS);
    }

    @Test
    @DisplayName("CA2 - tableau vide : LogoInvalideException motif REQUIS")
    void doit_refuser_un_tableau_vide_avec_le_motif_requis() {
        assertMotif(new byte[0], Motif.REQUIS);
    }

    @Test
    @DisplayName("CA2 - 2 097 153 octets à signature PNG valide : motif TROP_VOLUMINEUX")
    void doit_refuser_un_png_d_un_octet_au_dessus_de_2_mo() {
        assertMotif(pngDeTaille(TAILLE_MAXIMALE + 1), Motif.TROP_VOLUMINEUX);
    }

    @Test
    @DisplayName("CA2 - 2 097 153 octets de texte : la taille prime sur le format (TROP_VOLUMINEUX)")
    void doit_privilegier_la_taille_sur_le_format() {
        assertMotif(texteDeTaille("logo.png", TAILLE_MAXIMALE + 1), Motif.TROP_VOLUMINEUX);
    }

    @Test
    @DisplayName("CA2 - SVG, XML, GIF, PDF, BMP, HTML, texte : motif FORMAT_INVALIDE")
    void doit_refuser_les_formats_non_autorises() {
        assertMotif(ascii("<svg xmlns=\"http://www.w3.org/2000/svg\"></svg>"), Motif.FORMAT_INVALIDE);
        assertMotif(ascii("<?xml version=\"1.0\"?><svg/>"), Motif.FORMAT_INVALIDE);
        assertMotif(ascii("GIF89a-contenu"), Motif.FORMAT_INVALIDE);
        assertMotif(ascii("%PDF-1.7 contenu"), Motif.FORMAT_INVALIDE);
        assertMotif(ascii("BM-contenu-bitmap"), Motif.FORMAT_INVALIDE);
        assertMotif(ascii("<html><body></body></html>"), Motif.FORMAT_INVALIDE);
        assertMotif(ascii("logo.png"), Motif.FORMAT_INVALIDE);
    }

    @Test
    @DisplayName("CA2 - PNG tronqué à 7 octets : FORMAT_INVALIDE")
    void doit_refuser_un_png_tronque_a_7_octets() {
        assertMotif(Arrays.copyOf(png(), 7), Motif.FORMAT_INVALIDE);
    }

    @Test
    @DisplayName("CA2 - un seul octet faux dans la signature PNG (le dernier) : FORMAT_INVALIDE")
    void doit_refuser_une_signature_png_dont_le_dernier_octet_est_faux() {
        byte[] octets = png();
        octets[7] = 0x0B;

        assertMotif(octets, Motif.FORMAT_INVALIDE);
    }

    @Test
    @DisplayName("CA2 - JPEG réduit à FF D8 : FORMAT_INVALIDE")
    void doit_refuser_un_jpeg_reduit_a_deux_octets() {
        assertMotif(new byte[] {(byte) 0xFF, (byte) 0xD8}, Motif.FORMAT_INVALIDE);
    }

    @Test
    @DisplayName("CA2 - RIFF seul : FORMAT_INVALIDE")
    void doit_refuser_riff_seul() {
        assertMotif(ascii("RIFF"), Motif.FORMAT_INVALIDE);
    }

    @Test
    @DisplayName("CA2 - RIFF + 4 octets + WAVE (audio) : FORMAT_INVALIDE")
    void doit_refuser_un_riff_wave() {
        byte[] octets = concatener(ascii("RIFF"), new byte[] {1, 2, 3, 4}, ascii("WAVE"), new byte[100]);

        assertMotif(octets, Motif.FORMAT_INVALIDE);
    }

    @Test
    @DisplayName("CA2 - RIFF + 4 octets + WEB (11 octets, signature WebP incomplète) : FORMAT_INVALIDE")
    void doit_refuser_une_signature_webp_incomplete() {
        byte[] octets = concatener(ascii("RIFF"), new byte[] {1, 2, 3, 4}, ascii("WEB"));

        assertMotif(octets, Motif.FORMAT_INVALIDE);
    }

    @Test
    @DisplayName("CA2 - aucun message d'exception ne contient les octets refusés")
    void doit_ne_jamais_reprendre_les_octets_dans_le_message_d_exception() {
        String secret = "SECRET-CONTENU-DU-FICHIER";
        byte[] octets = ascii("<svg>" + secret + "</svg>");

        assertThatThrownBy(() -> Logo.depuis(octets))
                .isInstanceOf(LogoInvalideException.class)
                .satisfies(e -> assertThat(String.valueOf(e.getMessage()))
                        .doesNotContain(secret)
                        .doesNotContain(Arrays.toString(octets))
                        .doesNotContain(Base64.getEncoder().encodeToString(octets)));
    }

    // ---------- CA3 ----------

    @Test
    @DisplayName("CA3 - modifier le tableau d'entrée après la création n'altère pas le Logo")
    void doit_copier_les_octets_a_la_construction() {
        byte[] entree = png();
        byte[] copieDeReference = entree.clone();
        Logo logo = Logo.depuis(entree);

        entree[10] = (byte) (entree[10] + 1);
        entree[0] = 0;

        assertThat(logo.octets()).isEqualTo(copieDeReference);
        assertThat(logo.empreinte()).isEqualTo(sha256(copieDeReference));
    }

    @Test
    @DisplayName("CA3 - modifier le tableau renvoyé par la lecture n'altère pas le Logo")
    void doit_copier_les_octets_a_la_lecture() {
        byte[] reference = png();
        Logo logo = Logo.depuis(reference);

        byte[] lus = logo.octets();
        lus[10] = (byte) (lus[10] + 1);

        assertThat(logo.octets()).isEqualTo(reference);
        assertThat(logo.octets()).isNotSameAs(logo.octets());
    }

    @Test
    @DisplayName("CA3 - deux Logo de mêmes octets sont égaux, de contenus différents ils ne le sont pas")
    void doit_comparer_les_logos_par_leurs_octets() {
        assertThat(Logo.depuis(png())).isEqualTo(Logo.depuis(png()));
        assertThat(Logo.depuis(png())).hasSameHashCodeAs(Logo.depuis(png()));
        assertThat(Logo.depuis(png())).isNotEqualTo(Logo.depuis(jpeg()));
    }

    @Test
    @DisplayName("CA3 - toString n'affiche ni les octets ni leur représentation, seulement type et taille")
    void doit_masquer_les_octets_dans_to_string() {
        byte[] octets = png(ascii("CONTENU-SECRET-DU-LOGO"));
        Logo logo = Logo.depuis(octets);

        String texte = logo.toString();

        assertThat(texte)
                .doesNotContain("CONTENU-SECRET-DU-LOGO")
                .doesNotContain(Arrays.toString(octets))
                .doesNotContain("-119")
                .doesNotContain("[B@")
                .doesNotContain(Base64.getEncoder().encodeToString(octets))
                .doesNotContain(logo.empreinte())
                .contains(String.valueOf(octets.length))
                .containsAnyOf("image/png", "PNG");
    }

    private static void assertMotif(byte[] octets, Motif motifAttendu) {
        assertThatThrownBy(() -> Logo.depuis(octets))
                .isInstanceOfSatisfying(LogoInvalideException.class, e -> assertThat(e.motif()).isEqualTo(motifAttendu));
    }
}
