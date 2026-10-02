package fr.backyard.tracker.comptes.infrastructure;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Identifiants de l'admin master lus dans backyard.admin-master.* (variables ADMIN_MASTER_PSEUDO et
 * ADMIN_MASTER_MOT_DE_PASSE, défaut vide). Simples chaînes, sans validation Spring : les règles sont celles
 * du domaine, appliquées par {@code InitialiserAdminMaster}. Le mot de passe est masqué dans {@link #toString()}.
 */
@ConfigurationProperties("backyard.admin-master")
public record AdminMasterProprietes(String pseudo, String motDePasse) {

    @Override
    public String toString() {
        return "AdminMasterProprietes[pseudo=" + pseudo + ", motDePasse=[masqué]]";
    }
}
