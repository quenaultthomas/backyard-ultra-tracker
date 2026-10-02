package fr.backyard.tracker.comptes.exposition;

import fr.backyard.tracker.comptes.domaine.Compte;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.util.List;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.context.SecurityContextHolderStrategy;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.stereotype.Component;

/**
 * Ouverture et fermeture de la session serveur d'un compte connecté. La session porte l'identifiant
 * du compte (principal) et son rôle (autorité ROLE_&lt;role&gt;), jamais le pseudo ni l'empreinte.
 */
@Component
public class SessionConnexion {

    static final String COOKIE_SESSION = "JSESSIONID";

    private final SecurityContextRepository depotContexte;
    private final SecurityContextHolderStrategy contexteCourant = SecurityContextHolder.getContextHolderStrategy();

    public SessionConnexion(SecurityContextRepository depotContexte) {
        this.depotContexte = depotContexte;
    }

    /** Renouvelle l'identifiant d'une session préexistante (anti-fixation), puis y enregistre l'identité. */
    void ouvrir(Compte compte, HttpServletRequest requete, HttpServletResponse reponse) {
        if (requete.getSession(false) != null) {
            requete.changeSessionId();
        }
        SecurityContext contexte = contexteCourant.createEmptyContext();
        contexte.setAuthentication(authentification(compte));
        contexteCourant.setContext(contexte);
        depotContexte.saveContext(contexte, requete, reponse);
    }

    /** Idempotent : invalide la session éventuelle et efface le cookie de session. */
    void fermer(HttpServletRequest requete, HttpServletResponse reponse) {
        HttpSession session = requete.getSession(false);
        if (session != null) {
            session.invalidate();
        }
        contexteCourant.clearContext();
        reponse.addHeader(HttpHeaders.SET_COOKIE, cookieEfface(requete).toString());
    }

    private static Authentication authentification(Compte compte) {
        return UsernamePasswordAuthenticationToken.authenticated(compte.id(), null,
                List.of(new SimpleGrantedAuthority("ROLE_" + compte.role().name())));
    }

    private static ResponseCookie cookieEfface(HttpServletRequest requete) {
        return ResponseCookie.from(COOKIE_SESSION, "")
                .path("/")
                .maxAge(0)
                .httpOnly(true)
                .sameSite("Lax")
                .secure(requete.isSecure())
                .build();
    }
}
