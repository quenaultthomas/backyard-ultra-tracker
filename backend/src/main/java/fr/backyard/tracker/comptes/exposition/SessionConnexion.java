package fr.backyard.tracker.comptes.exposition;

import fr.backyard.tracker.comptes.domaine.Compte;
import fr.backyard.tracker.comptes.domaine.PolitiqueSession;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
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
    private final boolean cookieSecure;
    private final PolitiqueSession politiqueSession;

    /**
     * @param cookieSecure drapeau Secure du cookie effacé, même propriété que JSESSIONID et XSRF-TOKEN (7.3 RG2),
     *                     validée au démarrage par la configuration de sécurité
     */
    public SessionConnexion(SecurityContextRepository depotContexte,
                            @Value("${server.servlet.session.cookie.secure}") String cookieSecure,
                            PolitiqueSession politiqueSession) {
        this.depotContexte = depotContexte;
        this.politiqueSession = politiqueSession;
        this.cookieSecure = "true".equals(cookieSecure.trim());
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
        appliquerDelaiInactivite(compte, requete.getSession());
    }

    /** Délai d'inactivité choisi par la politique selon le rôle, posé une fois à l'ouverture (8.2 RG5). */
    private void appliquerDelaiInactivite(Compte compte, HttpSession session) {
        session.setMaxInactiveInterval(Math.toIntExact(politiqueSession.delaiInactivite(compte.role()).toSeconds()));
    }

    /** Nouvel identifiant pour la session (existante) d'une requête authentifiée, avec la même identité. */
    void renouvelerIdentifiant(HttpServletRequest requete) {
        requete.changeSessionId();
    }

    /** Idempotent : invalide la session éventuelle et efface le cookie de session. */
    void fermer(HttpServletRequest requete, HttpServletResponse reponse) {
        HttpSession session = requete.getSession(false);
        if (session != null) {
            session.invalidate();
        }
        contexteCourant.clearContext();
        reponse.addHeader(HttpHeaders.SET_COOKIE, cookieEfface().toString());
    }

    private static Authentication authentification(Compte compte) {
        return UsernamePasswordAuthenticationToken.authenticated(compte.id(), null,
                List.of(new SimpleGrantedAuthority("ROLE_" + compte.role().name())));
    }

    private ResponseCookie cookieEfface() {
        return ResponseCookie.from(COOKIE_SESSION, "")
                .path("/")
                .maxAge(0)
                .httpOnly(true)
                .sameSite("Lax")
                .secure(cookieSecure)
                .build();
    }
}
