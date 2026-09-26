package fr.backyard.api;

import fr.backyard.api.dto.SessionResponse;
import fr.backyard.service.SessionService;
import org.springframework.context.annotation.Lazy;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Validation des identifiants et heure du serveur (E19, RG52 inc. 4), pour les comptes SCANNER et ADMIN.
 * Le controller transmet le nom et les autorités fournis par Spring Security au service, sans condition.
 *
 * <p>Le service est injecté en {@link Lazy} : les tests de slice de l'API existants ({@code @WebMvcTest}
 * sur tous les controllers) ne déclarent pas ce service et doivent continuer à démarrer sans modification
 * (CA2 inc. 4). Le service réel est résolu au premier appel.</p>
 */
@RestController
@RequestMapping("/api/scan/me")
public class SessionController {

    private final SessionService sessionService;

    public SessionController(@Lazy SessionService sessionService) {
        this.sessionService = sessionService;
    }

    @GetMapping
    public SessionResponse me(Authentication authentication) {
        List<String> authorities = authentication.getAuthorities().stream()
            .map(GrantedAuthority::getAuthority)
            .toList();
        return SessionResponse.from(sessionService.describe(authentication.getName(), authorities));
    }
}
