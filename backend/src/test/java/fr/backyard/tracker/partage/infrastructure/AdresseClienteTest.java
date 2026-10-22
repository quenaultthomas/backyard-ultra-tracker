package fr.backyard.tracker.partage.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Incrément 7.3, CA6 (RG4) : adresse cliente partagée (comportement de 7.2 conservé). Convention de 7.2 : Tomcat
 * remplace {@code getRemoteAddr()} par l'adresse issue de {@code X-Forwarded-For} ; {@link ValveAdressePair} garde
 * l'adresse du pair TCP dans l'attribut {@link ValveAdressePair#ATTRIBUT_ADRESSE_PAIR}. Signatures attendues, publiques
 * (utilisées par comptes et courses) : {@code new AdresseCliente(String proxyDeConfiance)} et
 * {@code String de(HttpServletRequest)}.
 */
class AdresseClienteTest {

    private static final String PROXY = "10.0.0.5";
    private static final String CLIENT_FINAL = "203.0.113.7";
    private static final String AUTRE_PAIR = "198.51.100.9";

    private static HttpServletRequest requete(String pair, String adresseResolue) {
        HttpServletRequest requete = mock(HttpServletRequest.class);
        when(requete.getAttribute(ValveAdressePair.ATTRIBUT_ADRESSE_PAIR)).thenReturn(pair);
        when(requete.getRemoteAddr()).thenReturn(adresseResolue);
        return requete;
    }

    @Test
    @DisplayName("CA6 - pair = proxy de confiance : l'adresse retenue est celle de X-Forwarded-For")
    void doit_retenir_l_adresse_transmise_quand_le_pair_est_le_proxy() {
        AdresseCliente adresse = new AdresseCliente(PROXY);

        assertThat(adresse.de(requete(PROXY, CLIENT_FINAL))).isEqualTo(CLIENT_FINAL);
    }

    @Test
    @DisplayName("CA6 - pair différent du proxy : l'en-tête X-Forwarded-For est ignoré, adresse du pair retenue")
    void doit_ignorer_l_en_tete_quand_le_pair_n_est_pas_le_proxy() {
        AdresseCliente adresse = new AdresseCliente(PROXY);

        assertThat(adresse.de(requete(AUTRE_PAIR, CLIENT_FINAL))).isEqualTo(AUTRE_PAIR);
    }

    @Test
    @DisplayName("CA6 - sans proxy configuré : adresse du pair, même si une adresse transmise est présente")
    void doit_retenir_l_adresse_du_pair_sans_proxy_configure() {
        assertThat(new AdresseCliente("").de(requete(PROXY, CLIENT_FINAL))).isEqualTo(PROXY);
        assertThat(new AdresseCliente(null).de(requete(PROXY, CLIENT_FINAL))).isEqualTo(PROXY);
    }

    @Test
    @DisplayName("CA6 - attribut du pair absent : repli sur getRemoteAddr()")
    void doit_se_replier_sur_l_adresse_distante_sans_attribut() {
        assertThat(new AdresseCliente("").de(requete(null, AUTRE_PAIR))).isEqualTo(AUTRE_PAIR);
    }

    @Test
    @DisplayName("CA6 - proxy de confiance introuvable : non fiable, adresse du pair retenue")
    void doit_retenir_l_adresse_du_pair_quand_le_proxy_est_introuvable() {
        AdresseCliente adresse = new AdresseCliente("proxy-inexistant.invalid");

        assertThat(adresse.de(requete(PROXY, CLIENT_FINAL))).isEqualTo(PROXY);
    }
}
