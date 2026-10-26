package fr.backyard.tracker.comptes.domaine;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Incrément 8.4, CA4 : clé de comptage normalisée d'une adresse cliente (RG8). */
class AdresseSuivieTest {

    @Test
    @DisplayName("CA4 - une adresse IPv4 est conservée telle quelle")
    void doit_conserver_une_adresse_ipv4() {
        assertThat(AdresseSuivie.depuis("203.0.113.7").valeur()).isEqualTo("203.0.113.7");
    }

    @Test
    @DisplayName("CA4 - deux adresses IPv6 du même préfixe /64 donnent la même clé, quelle que soit la casse")
    void doit_regrouper_les_adresses_ipv6_d_un_meme_prefixe_64() {
        assertThat(AdresseSuivie.depuis("2001:DB8:1:2::1"))
                .isEqualTo(AdresseSuivie.depuis("2001:db8:1:2:aaaa:bbbb:cccc:dddd"));
    }

    @Test
    @DisplayName("CA4 - un autre préfixe IPv6 /64 donne une autre clé")
    void doit_distinguer_deux_prefixes_ipv6_differents() {
        assertThat(AdresseSuivie.depuis("2001:db8:1:3::1")).isNotEqualTo(AdresseSuivie.depuis("2001:db8:1:2::1"));
    }

    @Test
    @DisplayName("CA4 - une IPv6 de forme IPv4 donne la même clé que l'IPv4")
    void doit_traiter_une_ipv6_de_forme_ipv4_comme_l_ipv4() {
        assertThat(AdresseSuivie.depuis("::ffff:203.0.113.7")).isEqualTo(AdresseSuivie.depuis("203.0.113.7"));
    }

    @Test
    @DisplayName("CA4 - l'identifiant de zone IPv6 est retiré")
    void doit_retirer_l_identifiant_de_zone() {
        assertThat(AdresseSuivie.depuis("fe80::1%eth0")).isEqualTo(AdresseSuivie.depuis("fe80::1"));
    }

    @Test
    @DisplayName("CA4 - un texte illisible de 200 caractères donne une clé de 64 caractères au plus, sans exception")
    void doit_limiter_a_64_caracteres_un_texte_illisible() {
        AdresseSuivie adresse = AdresseSuivie.depuis("x".repeat(200));

        assertThat(adresse.valeur()).hasSizeLessThanOrEqualTo(64);
    }

    @Test
    @DisplayName("CA4 - la même entrée donne toujours la même clé")
    void doit_etre_deterministe() {
        assertThat(AdresseSuivie.depuis("2001:db8:1:2::1")).isEqualTo(AdresseSuivie.depuis("2001:db8:1:2::1"));
        assertThat(AdresseSuivie.depuis("n'importe quoi")).isEqualTo(AdresseSuivie.depuis("n'importe quoi"));
    }
}
