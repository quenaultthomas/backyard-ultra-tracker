package fr.backyard.tracker.comptes.infrastructure;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.annotation.web.configurers.RequestCacheConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.context.DelegatingSecurityContextRepository;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.RequestAttributeSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfFilter;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.OrRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;

/**
 * Politique de sécurité HTTP : tout /api/** exige une authentification sauf les endpoints publics,
 * CSRF actif en mode cookie pour l'application Angular, session serveur ouverte uniquement par une
 * connexion réussie (cookie JSESSIONID, paramètres dans application.yml).
 */
@Configuration(proxyBeanMethods = false)
public class SecuriteConfiguration {

    /** Paramètres Argon2id, recommandation OWASP : 19 Mio, 2 itérations, parallélisme 1. */
    static final int ARGON2_LONGUEUR_SEL_OCTETS = 16;
    static final int ARGON2_LONGUEUR_HACHAGE_OCTETS = 32;
    static final int ARGON2_PARALLELISME = 1;
    static final int ARGON2_MEMOIRE_KIO = 19_456;
    static final int ARGON2_ITERATIONS = 2;

    @Bean
    SecurityFilterChain filtreSecurite(HttpSecurity http, ReponsesErreurSecurite reponsesErreur,
                                       SecurityContextRepository depotContexteSecurite) {
        return http
                .authorizeHttpRequests(autorisations -> autorisations
                        .requestMatchers(HttpMethod.GET, "/api/sante", "/api/csrf").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/comptes", "/api/connexion", "/api/deconnexion")
                        .permitAll()
                        .requestMatchers("/api/**").authenticated()
                        .anyRequest().permitAll())
                .csrf(csrf -> csrf
                        .csrfTokenRepository(depotJetonCsrf())
                        // Angular renvoie la valeur brute du cookie dans X-XSRF-TOKEN.
                        .csrfTokenRequestHandler(new CsrfTokenRequestAttributeHandler()))
                .addFilterAfter(new FiltreDejaConnecte(requetesReserveesAuxAnonymes(), reponsesErreur),
                        CsrfFilter.class)
                .securityContext(contexte -> contexte.securityContextRepository(depotContexteSecurite))
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED))
                // Aucune requête mémorisée en session pour un refus : une requête anonyme ne crée jamais de session.
                .requestCache(RequestCacheConfigurer::disable)
                .exceptionHandling(erreurs -> erreurs
                        .authenticationEntryPoint(reponsesErreur)
                        .accessDeniedHandler(reponsesErreur))
                .httpBasic(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .build();
    }

    /** Contexte de sécurité conservé en session serveur, enregistré explicitement à la connexion. */
    @Bean
    SecurityContextRepository depotContexteSecurite() {
        return new DelegatingSecurityContextRepository(
                new RequestAttributeSecurityContextRepository(), new HttpSessionSecurityContextRepository());
    }

    @Bean
    PasswordEncoder passwordEncoder() {
        return new Argon2PasswordEncoder(ARGON2_LONGUEUR_SEL_OCTETS, ARGON2_LONGUEUR_HACHAGE_OCTETS,
                ARGON2_PARALLELISME, ARGON2_MEMOIRE_KIO, ARGON2_ITERATIONS);
    }

    private static RequestMatcher requetesReserveesAuxAnonymes() {
        PathPatternRequestMatcher.Builder chemins = PathPatternRequestMatcher.withDefaults();
        return new OrRequestMatcher(
                chemins.matcher(HttpMethod.POST, "/api/connexion"),
                chemins.matcher(HttpMethod.POST, "/api/comptes"));
    }

    /** Cookie XSRF-TOKEN lisible par Angular, SameSite=Lax, Secure si la requête (ou le proxy) est en HTTPS. */
    private static CookieCsrfTokenRepository depotJetonCsrf() {
        CookieCsrfTokenRepository depot = CookieCsrfTokenRepository.withHttpOnlyFalse();
        depot.setCookieCustomizer(cookie -> cookie.sameSite("Lax"));
        return depot;
    }
}
