package fr.backyard.tracker.partage.domaine;

import static fr.backyard.tracker.courses.OctetsDeLogo.ascii;
import static fr.backyard.tracker.courses.OctetsDeLogo.concatener;
import static fr.backyard.tracker.courses.OctetsDeLogo.jpeg;
import static fr.backyard.tracker.courses.OctetsDeLogo.png;
import static fr.backyard.tracker.courses.OctetsDeLogo.webp;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Détection du format d'image par signature, partagée par le logo et la photo (10.1a, CA1, CA2, RG2, RG15a).
 * Signatures attendues : PNG {@code 89 50 4E 47 0D 0A 1A 0A}, JPEG {@code FF D8 FF}, WebP {@code RIFF ???? WEBP}.
 */
class FormatImageTest {

    @Test
    @DisplayName("CA1 - un PNG (signature + 100 octets) est détecté : PNG, image/png, extension png")
    void doit_detecter_un_png() {
        FormatImage format = FormatImage.detecter(png()).orElseThrow();

        assertThat(format).isEqualTo(FormatImage.PNG);
        assertThat(format.typeMime()).isEqualTo("image/png");
        assertThat(format.extension()).isEqualTo("png");
    }

    @Test
    @DisplayName("CA1 - un JPEG (FF D8 FF E0 + 100 octets) est détecté : JPEG, image/jpeg, extension jpg")
    void doit_detecter_un_jpeg() {
        FormatImage format = FormatImage.detecter(jpeg()).orElseThrow();

        assertThat(format).isEqualTo(FormatImage.JPEG);
        assertThat(format.typeMime()).isEqualTo("image/jpeg");
        assertThat(format.extension()).isEqualTo("jpg");
    }

    @Test
    @DisplayName("CA1 - un WebP (RIFF, 4 octets, WEBP, 100 octets) est détecté : WEBP, image/webp, extension webp")
    void doit_detecter_un_webp() {
        FormatImage format = FormatImage.detecter(webp()).orElseThrow();

        assertThat(format).isEqualTo(FormatImage.WEBP);
        assertThat(format.typeMime()).isEqualTo("image/webp");
        assertThat(format.extension()).isEqualTo("webp");
    }

    @Test
    @DisplayName("CA1 - la taille maximale est de 2 097 152 octets")
    void doit_fixer_la_taille_maximale_a_2_mo() {
        assertThat(FormatImage.TAILLE_MAXIMALE_OCTETS).isEqualTo(2_097_152);
    }

    @Test
    @DisplayName("CA2 - null et tableau vide ne correspondent à aucun format")
    void doit_ne_rien_detecter_dans_des_octets_absents_ou_vides() {
        assertThat(FormatImage.detecter(null)).isEmpty();
        assertThat(FormatImage.detecter(new byte[0])).isEmpty();
    }

    @ParameterizedTest(name = "CA2 - {0} : aucun format")
    @MethodSource("contenusSansFormat")
    @DisplayName("CA2 - SVG, XML, GIF, PDF, BMP, HTML, texte et signatures tronquées ne sont pas des formats d'image")
    void doit_refuser_les_contenus_sans_signature_valide(String description, byte[] octets) {
        assertThat(FormatImage.detecter(octets)).isEmpty();
    }

    static Stream<Object[]> contenusSansFormat() {
        return Stream.of(
                cas("SVG", ascii("<svg xmlns=\"http://www.w3.org/2000/svg\"></svg>")),
                cas("XML", ascii("<?xml version=\"1.0\"?><svg/>")),
                cas("GIF", ascii("GIF89a" + "x".repeat(100))),
                cas("PDF", ascii("%PDF-1.7 " + "x".repeat(100))),
                cas("BMP", ascii("BM" + "x".repeat(100))),
                cas("HTML", ascii("<html><body>x</body></html>")),
                cas("texte", ascii("juste du texte qui ressemble à rien")),
                cas("PNG tronqué à 7 octets", Arrays.copyOf(png(), 7)),
                cas("JPEG tronqué à FF D8", new byte[] {(byte) 0xFF, (byte) 0xD8}),
                cas("RIFF seul", ascii("RIFF")),
                cas("RIFF + 4 octets + WAVE", concatener(ascii("RIFF"), new byte[4], ascii("WAVE"), new byte[100])),
                cas("RIFF + WEBP tronqué (11 octets)", Arrays.copyOf(webp(), 11)));
    }

    private static Object[] cas(String description, byte[] octets) {
        return new Object[] {description, octets};
    }
}
