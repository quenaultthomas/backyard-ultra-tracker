package fr.backyard.tracker.partage.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import org.apache.catalina.core.StandardContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.tomcat.servlet.TomcatServletWebServerFactory;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;

/**
 * Correctif 431 après un 413 de téléversement : le contexte Tomcat doit continuer à lire le corps d'un envoi
 * refusé (RG4), sans Spring ni serveur démarré.
 */
class TeleversementConfigurationTest {

    private final WebServerFactoryCustomizer<TomcatServletWebServerFactory> customizer =
            new TeleversementConfiguration().absorptionDesTeleversementsRefuses();

    @Test
    @DisplayName("CA1 : doit activer l'absorption des envois refusés sur un contexte où elle était désactivée")
    void doit_activer_l_absorption_des_envois_refuses_sur_un_contexte_ou_elle_etait_desactivee() {
        StandardContext contexte = new StandardContext();
        contexte.setSwallowAbortedUploads(false);

        appliquer(contexte);

        assertThat(contexte.getSwallowAbortedUploads()).isTrue();
    }

    @Test
    @DisplayName("CA1 : doit donner le même résultat quand le customizer est appliqué deux fois")
    void doit_donner_le_meme_resultat_quand_le_customizer_est_applique_deux_fois() {
        StandardContext contexte = new StandardContext();
        contexte.setSwallowAbortedUploads(false);

        appliquer(contexte);
        appliquer(contexte);

        assertThat(contexte.getSwallowAbortedUploads()).isTrue();
    }

    private void appliquer(StandardContext contexte) {
        TomcatServletWebServerFactory fabrique = new TomcatServletWebServerFactory();
        customizer.customize(fabrique);
        assertThat(fabrique.getContextCustomizers()).isNotEmpty();
        fabrique.getContextCustomizers().forEach(c -> c.customize(contexte));
    }
}
