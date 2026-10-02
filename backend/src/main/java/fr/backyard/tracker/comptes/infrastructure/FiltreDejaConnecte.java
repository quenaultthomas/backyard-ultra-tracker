package fr.backyard.tracker.comptes.infrastructure;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.security.authentication.AuthenticationTrustResolver;
import org.springframework.security.authentication.AuthenticationTrustResolverImpl;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Refuse en 409 DEJA_CONNECTE les requêtes réservées aux anonymes (connexion, création de compte libre)
 * venant d'un utilisateur connecté. Placé après le contrôle CSRF et avant la lecture du corps.
 * Volontairement pas un bean : il n'existe que dans la chaîne de sécurité.
 */
class FiltreDejaConnecte extends OncePerRequestFilter {

    private final RequestMatcher requetesReserveesAuxAnonymes;
    private final ReponsesErreurSecurite reponsesErreur;
    private final AuthenticationTrustResolver resolveurAuthentification = new AuthenticationTrustResolverImpl();

    FiltreDejaConnecte(RequestMatcher requetesReserveesAuxAnonymes, ReponsesErreurSecurite reponsesErreur) {
        this.requetesReserveesAuxAnonymes = requetesReserveesAuxAnonymes;
        this.reponsesErreur = reponsesErreur;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest requete, HttpServletResponse reponse, FilterChain suite)
            throws ServletException, IOException {
        if (requetesReserveesAuxAnonymes.matches(requete) && estConnecte()) {
            reponsesErreur.dejaConnecte(requete, reponse);
            return;
        }
        suite.doFilter(requete, reponse);
    }

    private boolean estConnecte() {
        return resolveurAuthentification.isAuthenticated(SecurityContextHolder.getContext().getAuthentication());
    }
}
