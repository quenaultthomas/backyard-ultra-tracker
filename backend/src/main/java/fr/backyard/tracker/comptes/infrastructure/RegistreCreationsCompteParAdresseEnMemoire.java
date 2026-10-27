package fr.backyard.tracker.comptes.infrastructure;

import fr.backyard.tracker.comptes.domaine.AdmissionCreationCompte;
import fr.backyard.tracker.comptes.domaine.AdresseSuivie;
import fr.backyard.tracker.comptes.domaine.EchecsParAdresse;
import fr.backyard.tracker.comptes.domaine.PlafondEchecsParAdresse;
import fr.backyard.tracker.comptes.domaine.RegistreCreationsCompteParAdresse;
import java.time.Instant;
import org.springframework.stereotype.Component;

/**
 * Registre des tentatives de création de Compte coureur par adresse en mémoire de l'api (SEC-15). Compose sa propre
 * instance de {@link RegistreEchecsParAdresseEnMemoire} (capacité, purge, éviction), distincte de celle de la
 * connexion : les deux compteurs sont indépendants. N'ajoute que l'admission, sérialisée pour qu'au plus
 * {@code echecsMax} tentatives soient admises par fenêtre sous accès concurrents.
 */
@Component
public class RegistreCreationsCompteParAdresseEnMemoire implements RegistreCreationsCompteParAdresse {

    private final RegistreEchecsParAdresseEnMemoire tentatives = new RegistreEchecsParAdresseEnMemoire();

    @Override
    public synchronized AdmissionCreationCompte admettre(AdresseSuivie adresse, Instant maintenant,
                                                          PlafondEchecsParAdresse plafond) {
        EchecsParAdresse avant = tentatives.constater(adresse, maintenant);
        if (avant.estPlafonnee(maintenant, plafond)) {
            return AdmissionCreationCompte.refusee(avant.tempsRestant(maintenant, plafond));
        }
        EchecsParAdresse apres = tentatives.enregistrerEchec(adresse, maintenant, plafond);
        return AdmissionCreationCompte.admise(apres.atteintLePlafondA(maintenant, plafond));
    }

    /** Nombre d'adresses suivies (tests et supervision). */
    public int taille() {
        return tentatives.taille();
    }

    /** Oublie toutes les adresses (isolation des tests d'intégration ; hors contrat du domaine). */
    public synchronized void vider() {
        tentatives.vider();
    }
}
