package fr.backyard.config;

import fr.backyard.api.error.ProblemDetailResponseWriter;
import jakarta.servlet.DispatcherType;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
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

/**
 * Sécurité de l'API (RG28 à RG33 inc. 3) : HTTP Basic, deux comptes ADMIN et SCANNER en mémoire,
 * matrice d'accès par préfixe avec refus par défaut, API sans état, erreurs 401/403 en ProblemDetail.
 */
@Configuration
@EnableWebSecurity
@EnableConfigurationProperties(BackyardSecurityProperties.class)
public class SecurityConfig {

    static final String ROLE_ADMIN = "ADMIN";
    static final String ROLE_SCANNER = "SCANNER";

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
                .anyRequest().denyAll())
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
