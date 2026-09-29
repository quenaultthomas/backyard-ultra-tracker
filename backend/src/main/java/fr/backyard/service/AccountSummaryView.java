package fr.backyard.service;

/** Compte vu par l'admin dans la liste des comptes (RG24 inc. 5) : aucun mot de passe ni hash. */
public record AccountSummaryView(Long accountId, String pseudo, long runnerCount) {
}
