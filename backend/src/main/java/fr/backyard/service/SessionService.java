package fr.backyard.service;

import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.Collection;
import java.util.List;

/**
 * Description de la session authentifiée pour E19 (RG52 inc. 4) : nom du compte, rôle et heure du serveur.
 * Le rôle est le nom de l'unique autorité {@code ROLE_*} du compte, sans son préfixe. L'heure vient de la
 * {@link Clock} injectée, jamais de l'horloge système.
 */
@Service
public class SessionService {

    static final String ROLE_PREFIX = "ROLE_";

    private final Clock clock;

    public SessionService(Clock clock) {
        this.clock = clock;
    }

    /**
     * @param username    nom du compte authentifié
     * @param authorities autorités du compte, telles que fournies par Spring Security
     * @throws IllegalStateException si le compte n'a pas exactement un rôle : la configuration de sécurité
     *                               n'en attribue qu'un par compte, tout autre cas est une incohérence interne
     */
    public SessionView describe(String username, Collection<String> authorities) {
        return new SessionView(username, singleRole(username, authorities), Instant.now(clock));
    }

    private static String singleRole(String username, Collection<String> authorities) {
        List<String> roles = authorities.stream()
            .filter(authority -> authority.startsWith(ROLE_PREFIX))
            .map(authority -> authority.substring(ROLE_PREFIX.length()))
            .toList();
        if (roles.size() != 1) {
            throw new IllegalStateException("Le compte authentifié « " + username + " » doit avoir exactement un rôle,"
                + " il en a " + roles.size());
        }
        return roles.getFirst();
    }
}
