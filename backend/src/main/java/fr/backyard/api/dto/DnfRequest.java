package fr.backyard.api.dto;

import fr.backyard.domain.DnfReason;

/** DNF manuel (E17). Une raison absente ou TIMEOUT est rejetée par le service (RG21). */
public record DnfRequest(
    DnfReason reason
) {
}
