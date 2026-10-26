package fr.backyard.tracker.comptes.infrastructure;

import fr.backyard.tracker.comptes.domaine.AdresseDeLaRequete;
import fr.backyard.tracker.partage.infrastructure.AdresseCliente;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * Adresse cliente de la requête HTTP en cours, selon la seule définition de l'application ({@link AdresseCliente},
 * 7.3 RG4) : un {@code X-Forwarded-For} n'est retenu que s'il vient du proxy de confiance.
 */
@Component
class AdresseDeLaRequeteHttp implements AdresseDeLaRequete {

    private final AdresseCliente adresseCliente;

    AdresseDeLaRequeteHttp(AdresseCliente adresseCliente) {
        this.adresseCliente = adresseCliente;
    }

    @Override
    public Optional<String> courante() {
        return adresseCliente.deLaRequeteEnCours();
    }
}
