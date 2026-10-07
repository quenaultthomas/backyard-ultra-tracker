package fr.backyard.tracker.comptes.infrastructure;

import fr.backyard.tracker.comptes.domaine.InvalidationAutresSessions;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import jakarta.servlet.http.HttpSessionAttributeListener;
import jakarta.servlet.http.HttpSessionBindingEvent;
import jakarta.servlet.http.HttpSessionEvent;
import jakarta.servlet.http.HttpSessionIdListener;
import jakarta.servlet.http.HttpSessionListener;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * Registre en mémoire des sessions serveur par compte, alimenté par les événements du conteneur :
 * une session y entre quand un contexte de sécurité authentifié y est enregistré (connexion), suit ses
 * changements d'identifiant et en sort à sa destruction. En mémoire comme les sessions elles-mêmes :
 * valable pour une instance unique de l'api.
 */
@Component
public class RegistreSessionsEnMemoire
        implements InvalidationAutresSessions, HttpSessionAttributeListener, HttpSessionIdListener,
        HttpSessionListener {

    private static final Logger JOURNAL = LoggerFactory.getLogger(RegistreSessionsEnMemoire.class);
    private static final String ATTRIBUT_CONTEXTE = HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY;

    private record SessionDeCompte(UUID compteId, HttpSession session) {
    }

    private final Map<String, SessionDeCompte> sessions = new HashMap<>();

    /** Les sessions du compte autres que celle de la requête en cours sont invalidées hors verrou. */
    @Override
    public void invaliderAutresSessions(UUID compteId) {
        Optional<String> courante = identifiantSessionCourante();
        sessionsDuCompte(compteId, courante).forEach(RegistreSessionsEnMemoire::invalider);
    }

    /** Toutes les sessions du compte, celle de la requête en cours comprise, sont invalidées hors verrou. */
    @Override
    public void invaliderToutesLesSessions(UUID compteId) {
        sessionsDuCompte(compteId, Optional.empty()).forEach(RegistreSessionsEnMemoire::invalider);
    }

    @Override
    public void attributeAdded(HttpSessionBindingEvent evenement) {
        suivre(evenement);
    }

    @Override
    public void attributeReplaced(HttpSessionBindingEvent evenement) {
        suivre(evenement);
    }

    @Override
    public synchronized void sessionIdChanged(HttpSessionEvent evenement, String ancienIdentifiant) {
        SessionDeCompte suivie = sessions.remove(ancienIdentifiant);
        if (suivie != null) {
            sessions.put(evenement.getSession().getId(), suivie);
        }
    }

    @Override
    public void sessionDestroyed(HttpSessionEvent evenement) {
        oublier(evenement.getSession().getId());
    }

    /** Nombre de sessions suivies (tests et supervision). */
    public synchronized int taille() {
        return sessions.size();
    }

    /**
     * Valeur du contexte de sécurité relue dans la session (et non dans l'événement) : l'attribut
     * remplacé porte l'ancienne valeur dans {@link HttpSessionBindingEvent#getValue()}. Une session qui
     * cesse d'être authentifiée sans être détruite peut rester suivie : l'invalider n'a alors aucun effet
     * sur un compte.
     */
    private void suivre(HttpSessionBindingEvent evenement) {
        if (ATTRIBUT_CONTEXTE.equals(evenement.getName())) {
            HttpSession session = evenement.getSession();
            compteAuthentifie(session.getAttribute(ATTRIBUT_CONTEXTE))
                    .ifPresent(compteId -> enregistrer(session, compteId));
        }
    }

    private synchronized void enregistrer(HttpSession session, UUID compteId) {
        sessions.put(session.getId(), new SessionDeCompte(compteId, session));
    }

    private synchronized void oublier(String identifiantSession) {
        sessions.remove(identifiantSession);
    }

    private synchronized List<HttpSession> sessionsDuCompte(UUID compteId, Optional<String> sauf) {
        return sessions.entrySet().stream()
                .filter(entree -> entree.getValue().compteId().equals(compteId))
                .filter(entree -> sauf.map(id -> !id.equals(entree.getKey())).orElse(true))
                .map(entree -> entree.getValue().session())
                .toList();
    }

    /** Une session déjà invalidée entre-temps (expiration, déconnexion) est l'état recherché. */
    private static void invalider(HttpSession session) {
        try {
            session.invalidate();
        } catch (IllegalStateException dejaInvalidee) {
            JOURNAL.debug("Session déjà invalidée", dejaInvalidee);
        }
    }

    private static Optional<UUID> compteAuthentifie(Object contexte) {
        return Optional.ofNullable(contexte)
                .filter(SecurityContext.class::isInstance)
                .map(valeur -> ((SecurityContext) valeur).getAuthentication())
                .filter(Authentication::isAuthenticated)
                .map(Authentication::getPrincipal)
                .filter(UUID.class::isInstance)
                .map(UUID.class::cast);
    }

    /** Appelé pendant le traitement d'une requête HTTP authentifiée (changement de mot de passe). */
    private static Optional<String> identifiantSessionCourante() {
        HttpServletRequest requete = ((ServletRequestAttributes) RequestContextHolder.currentRequestAttributes())
                .getRequest();
        return Optional.ofNullable(requete.getSession(false)).map(HttpSession::getId);
    }
}
