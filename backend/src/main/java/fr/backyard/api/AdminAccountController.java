package fr.backyard.api;

import fr.backyard.api.dto.AccountSummaryResponse;
import fr.backyard.api.dto.PasswordChangeRequest;
import fr.backyard.service.AccountService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** Comptes coureurs vus par l'admin : liste et recherche, réinitialisation, suppression (E22, E24, E25 ; inc. 5). */
@RestController
@RequestMapping("/api/admin/accounts")
public class AdminAccountController {

    private final AccountService accountService;

    public AdminAccountController(AccountService accountService) {
        this.accountService = accountService;
    }

    @GetMapping
    public List<AccountSummaryResponse> search(@RequestParam(name = "pseudo", required = false) String pseudo) {
        return accountService.search(pseudo).stream().map(AccountSummaryResponse::from).toList();
    }

    @PutMapping("/{accountId}/password")
    public ResponseEntity<Void> resetPassword(@PathVariable Long accountId,
                                              @Valid @RequestBody PasswordChangeRequest request) {
        accountService.resetPassword(accountId, request.newPassword());
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/{accountId}")
    public ResponseEntity<Void> delete(@PathVariable Long accountId) {
        accountService.delete(accountId);
        return ResponseEntity.noContent().build();
    }
}
