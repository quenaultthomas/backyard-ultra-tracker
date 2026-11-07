package fr.backyard.tracker.comptes.domaine;

import static fr.backyard.tracker.courses.OctetsDeLogo.TAILLE_MAXIMALE;
import static fr.backyard.tracker.courses.OctetsDeLogo.ascii;
import static fr.backyard.tracker.courses.OctetsDeLogo.concatener;
import static fr.backyard.tracker.courses.OctetsDeLogo.jpeg;
import static fr.backyard.tracker.courses.OctetsDeLogo.png;
import static fr.backyard.tracker.courses.OctetsDeLogo.pngDeTaille;
import static fr.backyard.tracker.courses.OctetsDeLogo.texteDeTaille;
import static fr.backyard.tracker.courses.OctetsDeLogo.webp;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import fr.backyard.tracker.comptes.domaine.PhotoInvalideException.Motif;
import fr.backyard.tracker.partage.domaine.FormatImage;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/** Value object Photo (10.1a, CA1 et CA2, RG2). Aucun octet n'est lu depuis un fichier. */
class PhotoTest {

    // ---------- CA1 ----------

    @Test
    @DisplayName("CA1 - un PNG (signature + 100 octets) donne le format PNG, image/png, extension png et sa taille")
    void doit_creer_une_photo_png() {
        byte[] octets = png();

        Photo photo = Photo.depuis(octets);

        assertThat(photo.format()).isEqualTo(FormatImage.PNG);
        assertThat(photo.typeMime()).isEqualTo("image/png");
        assertThat(photo.extension()).isEqualTo("png");
        assertThat(photo.taille()).isEqualTo(octets.length).isEqualTo(108);
    }

    @Test
    @DisplayName("CA1 - un JPEG (FF D8 FF E0 + 100 octets) donne le format JPEG, image/jpeg, extension jpg et sa taille")
    void doit_creer_une_photo_jpeg() {
        byte[] octets = jpeg();

        Photo photo = Photo.depuis(octets);

        assertThat(photo.format()).isEqualTo(FormatImage.JPEG);
        assertThat(photo.typeMime()).isEqualTo("image/jpeg");
        assertThat(photo.extension()).isEqualTo("jpg");
        assertThat(photo.taille()).isEqualTo(octets.length).isEqualTo(104);
    }

    @Test
    @DisplayName("CA1 - un WebP (RIFF, 4 octets, WEBP, 100 octets) donne le format WEBP, image/webp, extension webp")
    void doit_creer_une_photo_webp() {
        byte[] octets = webp();

        Photo photo = Photo.depuis(octets);

        assertThat(photo.format()).isEqualTo(FormatImage.WEBP);
        assertThat(photo.typeMime()).isEqualTo("image/webp");
        assertThat(photo.extension()).isEqualTo("webp");
        assertThat(photo.taille()).isEqualTo(octets.length);
    }

    @Test
    @DisplayName("CA1 - exactement 2 097 152 octets à signature PNG : accepté (borne incluse)")
    void doit_accepter_une_photo_de_exactement_2_mo() {
        Photo photo = Photo.depuis(pngDeTaille(TAILLE_MAXIMALE));

        assertThat(photo.taille()).isEqualTo(2_097_152);
    }

    @Test
    @DisplayName("CA1 - copie défensive : modifier le tableau d'origine ne change pas la photo")
    void doit_copier_les_octets_a_la_creation() {
        byte[] origine = png();
        byte[] attendu = origine.clone();

        Photo photo = Photo.depuis(origine);
        origine[10] = (byte) (origine[10] + 1);
        origine[0] = 0;

        assertThat(photo.octets()).isEqualTo(attendu);
        assertThat(photo.format()).isEqualTo(FormatImage.PNG);
    }

    @Test
    @DisplayName("CA1 - copie défensive : modifier le tableau lu ne change pas la photo")
    void doit_copier_les_octets_a_la_lecture() {
        Photo photo = Photo.depuis(png());
        byte[] attendu = photo.octets();

        byte[] lu = photo.octets();
        lu[10] = (byte) (lu[10] + 1);

        assertThat(photo.octets()).isEqualTo(attendu);
    }

    @Test
    @DisplayName("CA1 - toString n'affiche aucun octet (ni tableau, ni hexadécimal, ni base64)")
    void doit_masquer_les_octets_dans_to_string() {
        byte[] octets = png();

        String texte = Photo.depuis(octets).toString();

        assertThat(texte)
                .doesNotContain(Arrays.toString(octets))
                .doesNotContain(HexFormat.of().formatHex(octets))
                .doesNotContain(java.util.Base64.getEncoder().encodeToString(octets))
                .doesNotContain("[B@");
    }

    @Test
    @DisplayName("CA1 - deux photos aux mêmes octets sont égales")
    void doit_comparer_les_photos_par_leurs_octets() {
        assertThat(Photo.depuis(png())).isEqualTo(Photo.depuis(png())).hasSameHashCodeAs(Photo.depuis(png()));
        assertThat(Photo.depuis(png())).isNotEqualTo(Photo.depuis(jpeg()));
    }

    // ---------- CA2 ----------

    @Test
    @DisplayName("CA2 - null donne REQUISE")
    void doit_refuser_des_octets_absents() {
        assertThatThrownBy(() -> Photo.depuis(null)).isInstanceOfSatisfying(PhotoInvalideException.class,
                e -> assertThat(e.motif()).isEqualTo(Motif.REQUISE));
    }

    @Test
    @DisplayName("CA2 - un tableau vide donne REQUISE")
    void doit_refuser_un_tableau_vide() {
        assertThatThrownBy(() -> Photo.depuis(new byte[0])).isInstanceOfSatisfying(PhotoInvalideException.class,
                e -> assertThat(e.motif()).isEqualTo(Motif.REQUISE));
    }

    @Test
    @DisplayName("CA2 - 2 097 153 octets à signature PNG donnent TROP_VOLUMINEUSE (borne exclue)")
    void doit_refuser_un_png_d_un_octet_de_trop() {
        assertThatThrownBy(() -> Photo.depuis(pngDeTaille(TAILLE_MAXIMALE + 1)))
                .isInstanceOfSatisfying(PhotoInvalideException.class,
                        e -> assertThat(e.motif()).isEqualTo(Motif.TROP_VOLUMINEUSE));
    }

    @Test
    @DisplayName("CA2 - 2 097 153 octets de texte donnent TROP_VOLUMINEUSE : la taille prime sur le format")
    void doit_controler_la_taille_avant_le_format() {
        assertThatThrownBy(() -> Photo.depuis(texteDeTaille("juste du texte", TAILLE_MAXIMALE + 1)))
                .isInstanceOfSatisfying(PhotoInvalideException.class,
                        e -> assertThat(e.motif()).isEqualTo(Motif.TROP_VOLUMINEUSE));
    }

    @ParameterizedTest(name = "CA2 - {0} : FORMAT_INVALIDE")
    @MethodSource("contenusQuiNeSontPasDesPhotos")
    @DisplayName("CA2 - SVG, XML, GIF, PDF, BMP, HTML, texte et signatures tronquées donnent FORMAT_INVALIDE")
    void doit_refuser_un_format_inconnu_et_ne_jamais_citer_les_octets(String description, byte[] octets) {
        assertThatThrownBy(() -> Photo.depuis(octets)).isInstanceOfSatisfying(PhotoInvalideException.class, e -> {
            assertThat(e.motif()).isEqualTo(Motif.FORMAT_INVALIDE);
            String texte = String.valueOf(e.getMessage()) + e;
            assertThat(texte).doesNotContain(new String(octets, java.nio.charset.StandardCharsets.ISO_8859_1))
                    .doesNotContain(Arrays.toString(octets));
        });
    }

    static Stream<Object[]> contenusQuiNeSontPasDesPhotos() {
        return Stream.of(
                cas("SVG", ascii("<svg xmlns=\"http://www.w3.org/2000/svg\"></svg>")),
                cas("XML", ascii("<?xml version=\"1.0\"?><svg/>")),
                cas("GIF", ascii("GIF89a" + "x".repeat(100))),
                cas("PDF", ascii("%PDF-1.7 " + "x".repeat(100))),
                cas("BMP", ascii("BM" + "x".repeat(100))),
                cas("HTML", ascii("<html><body>secret-hostile</body></html>")),
                cas("texte", ascii("juste du texte qui ressemble à rien")),
                cas("PNG tronqué à 7 octets", Arrays.copyOf(png(), 7)),
                cas("JPEG tronqué à FF D8", new byte[] {(byte) 0xFF, (byte) 0xD8}),
                cas("RIFF seul", ascii("RIFF")),
                cas("RIFF + 4 octets + WAVE", concatener(ascii("RIFF"), new byte[4], ascii("WAVE"), new byte[100])));
    }

    private static Object[] cas(String description, byte[] octets) {
        return new Object[] {description, octets};
    }
}
