package fr.backyard.api;

import fr.backyard.api.dto.AccountCreationRequest;
import fr.backyard.api.dto.AccountCreationResponse;
import fr.backyard.domain.Account;
import fr.backyard.service.AccountService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Création autonome d'un compte coureur, sans course (E26, RG5 inc. 7), en accès libre. Toutes les règles
 * (format, normalisation, conflit, hachage) sont celles de {@link AccountService#create}.
 */
@RestController
public class PublicAccountController {

    private final AccountService accountService;

    public PublicAccountController(AccountService accountService) {
        this.accountService = accountService;
    }

    @PostMapping("/api/public/accounts")
    @ResponseStatus(HttpStatus.CREATED)
    public AccountCreationResponse create(@Valid @RequestBody AccountCreationRequest request) {
        Account account = accountService.create(request.pseudo(), request.password());
        return AccountCreationResponse.from(account);
    }
}
