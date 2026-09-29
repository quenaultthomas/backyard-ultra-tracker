package fr.backyard.api.dto;

import fr.backyard.service.AccountSummaryView;

/** Élément de la liste des comptes (E25, RG24 inc. 5) : exactement accountId, pseudo et runnerCount. */
public record AccountSummaryResponse(
    Long accountId,
    String pseudo,
    long runnerCount
) {

    public static AccountSummaryResponse from(AccountSummaryView view) {
        return new AccountSummaryResponse(view.accountId(), view.pseudo(), view.runnerCount());
    }
}
