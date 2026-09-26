package fr.backyard.service;

import java.time.Instant;

/**
 * Session authentifiée (RG52 inc. 4).
 *
 * @param username   nom du compte
 * @param role       rôle du compte sans le préfixe {@code ROLE_} ({@code ADMIN} ou {@code SCANNER})
 * @param serverTime heure du serveur, pour la mesure du décalage d'horloge de l'appareil (RG4 inc. 4)
 */
public record SessionView(String username, String role, Instant serverTime) {
}
