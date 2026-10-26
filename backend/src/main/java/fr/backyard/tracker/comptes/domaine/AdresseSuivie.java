package fr.backyard.tracker.comptes.domaine;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/**
 * Clé de comptage d'une adresse cliente pour le plafond d'échecs (SEC-14) : IPv4 telle quelle (y compris sous sa
 * forme IPv6 {@code ::ffff:a.b.c.d}), IPv6 regroupée par préfixe /64 (un abonné change librement les 64 derniers
 * bits), identifiant de zone retiré. Texte illisible : conservé, tronqué. Au plus {@value #LONGUEUR_MAX} caractères.
 */
public record AdresseSuivie(String valeur) {

    static final int LONGUEUR_MAX = 64;
    private static final int GROUPES_DU_PREFIXE_64 = 4;

    public static AdresseSuivie depuis(String texte) {
        String sansZone = texte.contains("%") ? texte.substring(0, texte.indexOf('%')) : texte;
        try {
            return new AdresseSuivie(cle(InetAddress.ofLiteral(sansZone.trim())));
        } catch (IllegalArgumentException illisible) {
            return new AdresseSuivie(texte.substring(0, Math.min(texte.length(), LONGUEUR_MAX)));
        }
    }

    private static String cle(InetAddress adresse) {
        return switch (adresse) {
            case Inet4Address ipv4 -> ipv4.getHostAddress();
            case Inet6Address ipv6 -> prefixe64(ipv6.getAddress());
            default -> adresse.getHostAddress();
        };
    }

    /** Forme canonique du préfixe /64 : quatre premiers groupes en hexadécimal minuscule sans zéro inutile. */
    private static String prefixe64(byte[] octets) {
        return IntStream.range(0, GROUPES_DU_PREFIXE_64)
                .mapToObj(groupe -> Integer.toHexString(groupe16Bits(octets, groupe)))
                .collect(Collectors.joining(":", "", "::/64"));
    }

    private static int groupe16Bits(byte[] octets, int groupe) {
        return ((octets[2 * groupe] & 0xff) << 8) | (octets[2 * groupe + 1] & 0xff);
    }
}
