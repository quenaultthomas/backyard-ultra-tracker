package fr.backyard.config;

import fr.backyard.api.error.ProblemDetailResponseWriter;
import fr.backyard.service.AccountService;
import jakarta.servlet.DispatcherType;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter.ReferrerPolicy;
import org.springframework.security.web.header.writers.StaticHeadersWriter;

/**
 * Sécurité de l'API (RG28 à RG33 inc. 3, RG9 et RG10 inc. 5) : HTTP Basic, API sans état, erreurs 401/403 en
 * ProblemDetail. Le référentiel d'authentification dépend du préfixe d'URL (PO15 inc. 5) :
 * <ul>
 *   <li>{@code /api/account/**} : comptes coureurs en base uniquement, rôle RUNNER ;</li>
 *   <li>tout autre chemin : comptes staff de configuration (ADMIN et SCANNER) uniquement, matrice d'accès par
 *       préfixe avec refus par défaut.</li>
 * </ul>
 * Des identifiants inconnus du référentiel du chemin donnent 401 « Identifiants invalides ».
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

    /** Chemins authentifiés par les comptes coureurs (RG9, RG10 inc. 5). */
    static final String ACCOUNT_PATHS = "/api/account/**";

    /** RG3 inc. 5 : coût BCrypt des hash produits par l'application, défini ici seulement. */
    static final int BCRYPT_COST = 12;

    /** RG55 inc. 4 : aucun script ni style en ligne, aucune ressource tierce. */
    static final String CONTENT_SECURITY_POLICY = "default-src 'self'; script-src 'self'; style-src 'self'; "
        + "img-src 'self' data: blob:; media-src 'self' blob:; connect-src 'self'; worker-src 'self'; "
        + "manifest-src 'self'; frame-ancestors 'none'; base-uri 'self'; form-action 'self'";

    static final String PERMISSIONS_POLICY_HEADER = "Permissions-Policy";
    static final String PERMISSIONS_POLICY = "camera=(self), microphone=(), geolocation=()";

    /**
     * Chaîne des comptes coureurs, prioritaire : elle ne traite que {@code /api/account/**} et n'interroge que les
     * comptes coureurs, lus en base à chaque requête (RG9 inc. 5).
     */
    @Bean
    @Order(1)
    public SecurityFilterChain accountSecurityFilterChain(HttpSecurity http,
                                                          ProblemDetailAuthenticationEntryPoint authenticationEntryPoint,
                                                          ProblemDetailAccessDeniedHandler accessDeniedHandler,
                                                          ObjectProvider<AccountService> accountService,
                                                          PasswordEncoder passwordEncoder) {
        DaoAuthenticationProvider runnerAccounts = new DaoAuthenticationProvider(runnerAccounts(accountService));
        runnerAccounts.setPasswordEncoder(passwordEncoder);
        http
            .securityMatcher(ACCOUNT_PATHS)
            .authenticationManager(new ProviderManager(runnerAccounts))
            .authorizeHttpRequests(auth -> auth
                .anyRequest().hasRole(RunnerAccountPrincipal.ROLE_RUNNER));
        return applyCommonSettings(http, authenticationEntryPoint, accessDeniedHandler).build();
    }

    /** Chaîne des comptes staff : tous les autres chemins (RG29 inc. 3, inchangée). */
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
                .anyRequest().denyAll());
        return applyCommonSettings(http, authenticationEntryPoint, accessDeniedHandler).build();
    }

    /** Les deux comptes staff, après validation des variables d'environnement (refus de démarrer sinon, RG32). */
    @Bean
    public UserDetailsService userDetailsService(BackyardSecurityProperties properties) {
        properties.validate();
        return new InMemoryUserDetailsManager(
            account(properties.admin(), ROLE_ADMIN),
            account(properties.scanner(), ROLE_SCANNER));
    }

    /**
     * Encodeur unique (RG3 inc. 5) : produit les hash des comptes coureurs au coût 12 et vérifie tous les hash,
     * staff comme coureurs, quel que soit leur coût (BCrypt lit le coût dans le hash).
     */
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(BCRYPT_COST);
    }

    @Bean
    public ProblemDetailAuthenticationEntryPoint problemDetailAuthenticationEntryPoint() {
        return new ProblemDetailAuthenticationEntryPoint(new ProblemDetailResponseWriter());
    }

    @Bean
    public ProblemDetailAccessDeniedHandler problemDetailAccessDeniedHandler() {
        return new ProblemDetailAccessDeniedHandler(new ProblemDetailResponseWriter());
    }

    /** En-têtes, HTTP Basic, erreurs en ProblemDetail et absence d'état, communs aux deux chaînes. */
    private static HttpSecurity applyCommonSettings(HttpSecurity http,
                                                    ProblemDetailAuthenticationEntryPoint authenticationEntryPoint,
                                                    ProblemDetailAccessDeniedHandler accessDeniedHandler) {
        return http
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
    }

    /**
     * Référentiel des comptes coureurs (RG9 inc. 5) : le pseudo présenté est normalisé et recherché par le service.
     * Le service est résolu à la requête, pour que la configuration de sécurité reste autonome au démarrage.
     * Le message d'échec ne cite jamais le pseudo présenté (RG16).
     */
    private static UserDetailsService runnerAccounts(ObjectProvider<AccountService> accountService) {
        return presentedPseudo -> accountService.getObject().findCredentials(presentedPseudo)
            .map(credentials -> new RunnerAccountPrincipal(credentials.accountId(), credentials.pseudo(),
                credentials.passwordHash()))
            .orElseThrow(() -> new UsernameNotFoundException("Compte coureur inconnu"));
    }

    private static UserDetails account(BackyardSecurityProperties.Account account, String role) {
        return User.withUsername(account.username())
            .password(account.passwordHash())
            .roles(role)
            .build();
    }
}
