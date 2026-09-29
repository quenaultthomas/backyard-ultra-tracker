package fr.backyard.api;

import fr.backyard.api.dto.AccountRegistrationsResponse;
import fr.backyard.api.dto.PasswordChangeRequest;
import fr.backyard.api.dto.RegistrationResponse;
import fr.backyard.config.RunnerAccountPrincipal;
import fr.backyard.domain.Runner;
import fr.backyard.service.AccountService;
import fr.backyard.service.RunnerService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;

/**
 * Espace du compte coureur authentifié (E20, E21, E23 ; RG8, RG11, RG19 inc. 5). Le compte est toujours celui de
 * l'authentification : aucun endpoint ne prend l'identifiant d'un compte ou d'un coureur en paramètre (RG10).
 */
@RestController
@RequestMapping("/api/account")
public class AccountController {

    private final AccountService accountService;
    private final RunnerService runnerService;

    public AccountController(AccountService accountService, RunnerService runnerService) {
        this.accountService = accountService;
        this.runnerService = runnerService;
    }

    @PostMapping("/races/{raceId}/registrations")
    public ResponseEntity<RegistrationResponse> register(@AuthenticationPrincipal RunnerAccountPrincipal principal,
                                                         @PathVariable Long raceId) {
        Runner runner = runnerService.registerAccount(raceId, principal.accountId());
        return ResponseEntity.created(URI.create("/api/public/runners/" + runner.getId()))
            .body(RegistrationResponse.from(runner));
    }

    @GetMapping("/me")
    public AccountRegistrationsResponse me(@AuthenticationPrincipal RunnerAccountPrincipal principal) {
        return AccountRegistrationsResponse.from(accountService.registrations(principal.accountId()));
    }

    @PutMapping("/password")
    public ResponseEntity<Void> changePassword(@AuthenticationPrincipal RunnerAccountPrincipal principal,
                                               @Valid @RequestBody PasswordChangeRequest request) {
        accountService.changePassword(principal.accountId(), request.newPassword());
        return ResponseEntity.noContent().build();
    }
}
