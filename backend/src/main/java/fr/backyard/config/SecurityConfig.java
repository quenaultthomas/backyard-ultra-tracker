package fr.backyard.config;

import fr.backyard.api.error.ProblemDetailResponseWriter;
import jakarta.servlet.DispatcherType;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter.ReferrerPolicy;
import org.springframework.security.web.header.writers.StaticHeadersWriter;

/**
 * Sécurité de l'API (RG28 à RG33 inc. 3) : HTTP Basic, deux comptes ADMIN et SCANNER en mémoire,
 * matrice d'accès par préfixe avec refus par défaut, API sans état, erreurs 401/403 en ProblemDetail.
 * La PWA est servie sur la même origine (RG53 inc. 4) : seule la liste fermée de {@link PwaPaths} est ouverte,
 * en GET et HEAD. Aucune configuration CORS (RG54 inc. 4). En-têtes de sécurité de RG55 inc. 4 sur toutes
 * les réponses (HSTS reste posé par le reverse proxy).
 */
@Configuration
@EnableWebSecurity
@EnableConfigurationProperties(BackyardSecurityProperties.class)
public class SecurityConfig {

    static final String ROLE_ADMIN = "ADMIN";
    static final String ROLE_SCANNER = "SCANNER";

    /** RG55 inc. 4 : aucun script ni style en ligne, aucune ressource tierce. */
    static final String CONTENT_SECURITY_POLICY = "default-src 'self'; script-src 'self'; style-src 'self'; "
        + "img-src 'self' data: blob:; media-src 'self' blob:; connect-src 'self'; worker-src 'self'; "
        + "manifest-src 'self'; frame-ancestors 'none'; base-uri 'self'; form-action 'self'";

    static final String PERMISSIONS_POLICY_HEADER = "Permissions-Policy";
    static final String PERMISSIONS_POLICY = "camera=(self), microphone=(), geolocation=()";

    @Bean
    public SecurityFilterChain apiSecurityFilterChain(HttpSecurity http,
                                                      ProblemDetailAuthenticationEntryPoint authenticationEntryPoint,
                                                      ProblemDetailAccessDeniedHandler accessDeniedHandler) {
        http
            .authorizeHttpRequests(auth -> auth
                .dispatcherTypeMatchers(DispatcherType.ERROR).permitAll()
                .requestMatchers("/api/public/**").permitAll()
                .requestMatchers("/api/scan/**").hasAnyRole(ROLE_SCANNER, ROLE_ADMIN)
                .requestMatchers("/api/admin/**").hasRole(ROLE_ADMIN)
                .requestMatchers(HttpMethod.GET, PwaPaths.publicPaths()).permitAll()
                .requestMatchers(HttpMethod.HEAD, PwaPaths.publicPaths()).permitAll()
                .anyRequest().denyAll())
            .headers(headers -> headers
                .contentSecurityPolicy(csp -> csp.policyDirectives(CONTENT_SECURITY_POLICY))
                .referrerPolicy(referrer -> referrer.policy(ReferrerPolicy.NO_REFERRER))
                .addHeaderWriter(new StaticHeadersWriter(PERMISSIONS_POLICY_HEADER, PERMISSIONS_POLICY)))
            .httpBasic(basic -> basic.authenticationEntryPoint(authenticationEntryPoint))
            .exceptionHandling(exceptions -> exceptions
                .authenticationEntryPoint(authenticationEntryPoint)
                .accessDeniedHandler(accessDeniedHandler))
            .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .csrf(csrf -> csrf.disable())
            .formLogin(formLogin -> formLogin.disable())
            .logout(logout -> logout.disable());
        return http.build();
    }

    /** Les deux comptes, après validation des variables d'environnement (refus de démarrer sinon, RG32). */
    @Bean
    public UserDetailsService userDetailsService(BackyardSecurityProperties properties) {
        properties.validate();
        return new InMemoryUserDetailsManager(
            account(properties.admin(), ROLE_ADMIN),
            account(properties.scanner(), ROLE_SCANNER));
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public ProblemDetailAuthenticationEntryPoint problemDetailAuthenticationEntryPoint() {
        return new ProblemDetailAuthenticationEntryPoint(new ProblemDetailResponseWriter());
    }

    @Bean
    public ProblemDetailAccessDeniedHandler problemDetailAccessDeniedHandler() {
        return new ProblemDetailAccessDeniedHandler(new ProblemDetailResponseWriter());
    }

    private static UserDetails account(BackyardSecurityProperties.Account account, String role) {
        return User.withUsername(account.username())
            .password(account.passwordHash())
            .roles(role)
            .build();
    }
}
