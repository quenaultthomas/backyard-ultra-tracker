package fr.backyard.tracker.courses.domaine;

import static fr.backyard.tracker.courses.OctetsDeLogo.TAILLE_MAXIMALE;
import static fr.backyard.tracker.courses.OctetsDeLogo.ascii;
import static fr.backyard.tracker.courses.OctetsDeLogo.jpeg;
import static fr.backyard.tracker.courses.OctetsDeLogo.png;
import static fr.backyard.tracker.courses.OctetsDeLogo.pngDeTaille;
import static fr.backyard.tracker.courses.OctetsDeLogo.texteDeTaille;
import static fr.backyard.tracker.courses.OctetsDeLogo.webp;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import fr.backyard.tracker.courses.domaine.LogoInvalideException.Motif;
import fr.backyard.tracker.partage.domaine.FormatImage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 10.1a CA2 / RG15a : le logo garde ses exceptions et ses verdicts, la détection du format est celle de FormatImage. */
class LogoMemesVerdictsQueFormatImageTest {

    @Test
    @DisplayName("CA2 - Logo.depuis accepte exactement les octets dont FormatImage reconnaît le format")
    void doit_accepter_les_formats_reconnus_par_format_image() {
        for (byte[] octets : new byte[][] {png(), jpeg(), webp(), pngDeTaille(TAILLE_MAXIMALE)}) {
            assertThat(FormatImage.detecter(octets)).isPresent();
            assertThat(Logo.depuis(octets).taille()).isEqualTo(octets.length);
        }
    }

    @Test
    @DisplayName("CA2 - Logo.depuis refuse en FORMAT_INVALIDE ce que FormatImage ne reconnaît pas")
    void doit_refuser_en_format_invalide_ce_que_format_image_ne_reconnait_pas() {
        byte[] gif = ascii("GIF89a" + "x".repeat(100));

        assertThat(FormatImage.detecter(gif)).isEmpty();
        assertThatThrownBy(() -> Logo.depuis(gif))
                .isInstanceOfSatisfying(LogoInvalideException.class, e -> assertThat(e.motif()).isEqualTo(Motif.FORMAT_INVALIDE));
    }

    @Test
    @DisplayName("CA2 - Logo.depuis garde ses motifs : REQUIS pour le vide, TROP_VOLUMINEUX avant FORMAT_INVALIDE")
    void doit_conserver_les_motifs_du_logo() {
        assertThatThrownBy(() -> Logo.depuis(new byte[0]))
                .isInstanceOfSatisfying(LogoInvalideException.class, e -> assertThat(e.motif()).isEqualTo(Motif.REQUIS));
        assertThatThrownBy(() -> Logo.depuis(texteDeTaille("texte", TAILLE_MAXIMALE + 1)))
                .isInstanceOfSatisfying(LogoInvalideException.class,
                        e -> assertThat(e.motif()).isEqualTo(Motif.TROP_VOLUMINEUX));
        assertThatThrownBy(() -> Logo.depuis(pngDeTaille(TAILLE_MAXIMALE + 1)))
                .isInstanceOfSatisfying(LogoInvalideException.class,
                        e -> assertThat(e.motif()).isEqualTo(Motif.TROP_VOLUMINEUX));
    }
}
