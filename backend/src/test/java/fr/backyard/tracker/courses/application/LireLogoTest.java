package fr.backyard.tracker.courses.application;

import static fr.backyard.tracker.courses.OctetsDeLogo.jpeg;
import static fr.backyard.tracker.courses.OctetsDeLogo.png;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import fr.backyard.tracker.courses.domaine.Logo;
import fr.backyard.tracker.courses.domaine.LogoIntrouvableException;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Tests de l'incrément 2.3 : cas d'usage LireLogo (CA7). La lecture est indépendante du statut de la Course. */
class LireLogoTest {

    private static final UUID ID_A = UUID.fromString("00000000-0000-0000-0000-0000000000a1");
    private static final UUID ID_B = UUID.fromString("00000000-0000-0000-0000-0000000000b2");
    private static final UUID ID_INCONNU = UUID.fromString("00000000-0000-0000-0000-000000000000");

    private final DepotLogosDeTest depotLogos = new DepotLogosDeTest();
    private final LireLogo lireLogo = new LireLogo(depotLogos);

    @Test
    @DisplayName("CA7 - le logo de A est renvoyé avec ses octets, son type MIME et son empreinte")
    void doit_renvoyer_octets_type_mime_et_empreinte_du_logo() {
        depotLogos.enregistrer(ID_A, Logo.depuis(png()));
        depotLogos.enregistrer(ID_B, Logo.depuis(jpeg()));

        Logo logo = lireLogo.executer(ID_A);

        assertThat(logo.octets()).isEqualTo(png());
        assertThat(logo.typeMime()).isEqualTo("image/png");
        assertThat(logo.empreinte()).isEqualTo(Logo.depuis(png()).empreinte());
    }

    @Test
    @DisplayName("CA7 - Course sans logo : LogoIntrouvableException")
    void doit_lever_logo_introuvable_pour_une_course_sans_logo() {
        depotLogos.enregistrer(ID_A, Logo.depuis(png()));

        assertThatThrownBy(() -> lireLogo.executer(ID_B)).isInstanceOf(LogoIntrouvableException.class);
    }

    @Test
    @DisplayName("CA7 - id inconnu : LogoIntrouvableException (même exception, sans distinguer)")
    void doit_lever_logo_introuvable_pour_un_id_inconnu() {
        assertThatThrownBy(() -> lireLogo.executer(ID_INCONNU)).isInstanceOf(LogoIntrouvableException.class);
    }
}
