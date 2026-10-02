package fr.backyard.tracker.comptes.infrastructure;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;

/**
 * Politique de sécurité HTTP : tout /api/** exige une authentification sauf les endpoints publics,
 * CSRF actif en mode cookie pour l'application Angular, aucune session serveur (remplacé en 1.2).
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
    SecurityFilterChain filtreSecurite(HttpSecurity http, ReponsesErreurSecurite reponsesErreur) {
        return http
                .authorizeHttpRequests(autorisations -> autorisations
                        .requestMatchers(HttpMethod.GET, "/api/sante", "/api/csrf").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/comptes").permitAll()
                        .requestMatchers("/api/**").authenticated()
                        .anyRequest().permitAll())
                .csrf(csrf -> csrf
                        .csrfTokenRepository(depotJetonCsrf())
                        // Angular renvoie la valeur brute du cookie dans X-XSRF-TOKEN.
                        .csrfTokenRequestHandler(new CsrfTokenRequestAttributeHandler()))
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .exceptionHandling(erreurs -> erreurs
                        .authenticationEntryPoint(reponsesErreur)
                        .accessDeniedHandler(reponsesErreur))
                .httpBasic(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .build();
    }

    @Bean
    PasswordEncoder passwordEncoder() {
        return new Argon2PasswordEncoder(ARGON2_LONGUEUR_SEL_OCTETS, ARGON2_LONGUEUR_HACHAGE_OCTETS,
                ARGON2_PARALLELISME, ARGON2_MEMOIRE_KIO, ARGON2_ITERATIONS);
    }

    /** Cookie XSRF-TOKEN lisible par Angular, SameSite=Lax, Secure si la requête (ou le proxy) est en HTTPS. */
    private static CookieCsrfTokenRepository depotJetonCsrf() {
        CookieCsrfTokenRepository depot = CookieCsrfTokenRepository.withHttpOnlyFalse();
        depot.setCookieCustomizer(cookie -> cookie.sameSite("Lax"));
        return depot;
    }
}
