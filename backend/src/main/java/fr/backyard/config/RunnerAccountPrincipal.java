package fr.backyard.config;

import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.User;

import java.util.List;

/**
 * Compte coureur authentifié en HTTP Basic sur {@code /api/account/**} (RG9 inc. 5) : unique autorité
 * {@code ROLE_RUNNER}, et identifiant du compte, seule source de l'identité pour les endpoints du coureur (RG10).
 */
public final class RunnerAccountPrincipal extends User {

    private static final long serialVersionUID = 1L;

    static final String ROLE_RUNNER = "RUNNER";

    private final Long accountId;

    RunnerAccountPrincipal(Long accountId, String pseudo, String passwordHash) {
        super(pseudo, passwordHash, List.of(new SimpleGrantedAuthority("ROLE_" + ROLE_RUNNER)));
        this.accountId = accountId;
    }

    public Long accountId() {
        return accountId;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof RunnerAccountPrincipal principal && super.equals(other)
            && accountId.equals(principal.accountId);
    }

    @Override
    public int hashCode() {
        return 31 * super.hashCode() + accountId.hashCode();
    }
}
