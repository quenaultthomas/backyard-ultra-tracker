package fr.backyard.tracker.comptes.infrastructure;

import fr.backyard.tracker.comptes.domaine.AdresseSuivie;
import fr.backyard.tracker.comptes.domaine.EchecsParAdresse;
import fr.backyard.tracker.comptes.domaine.PlafondEchecsParAdresse;
import fr.backyard.tracker.comptes.domaine.RegistreEchecsParAdresse;
import java.time.Instant;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Registre des échecs de connexion par adresse en mémoire de l'api (SEC-14) : remis à zéro au redémarrage, non
 * partagé entre instances. Borné à {@value #CAPACITE} adresses ; à saturation, les adresses oubliées sont purgées,
 * puis la plus ancienne non plafonnée est évincée, une adresse plafonnée ne l'étant qu'en dernier recours. Chaque
 * entrée garde le plafond qui l'a enregistrée, pour en retirer les échecs expirés à la lecture. Opérations
 * sérialisées : atomiques par adresse et sans dépassement de la capacité.
 */
@Component
public class RegistreEchecsParAdresseEnMemoire implements RegistreEchecsParAdresse {

    public static final int CAPACITE = 10_000;

    private final Map<AdresseSuivie, Entree> entrees = new HashMap<>();

    @Override
    public synchronized EchecsParAdresse constater(AdresseSuivie adresse, Instant maintenant) {
        Entree entree = entrees.get(adresse);
        if (entree == null) {
            return EchecsParAdresse.aucun();
        }
        if (entree.estOubliee(maintenant)) {
            entrees.remove(adresse);
            return EchecsParAdresse.aucun();
        }
        Entree actualisee = new Entree(entree.echecs().sansEchecsExpires(maintenant, entree.plafond()), entree.plafond());
        entrees.put(adresse, actualisee);
        return actualisee.echecs();
    }

    @Override
    public synchronized EchecsParAdresse enregistrerEchec(AdresseSuivie adresse, Instant maintenant,
                                                          PlafondEchecsParAdresse plafond) {
        if (!entrees.containsKey(adresse) && entrees.size() >= CAPACITE) {
            libererUnePlace(maintenant);
        }
        EchecsParAdresse apres = constater(adresse, maintenant).apresEchec(maintenant, plafond);
        entrees.put(adresse, new Entree(apres, plafond));
        return apres;
    }

    /** Nombre d'adresses suivies (tests et supervision). */
    public synchronized int taille() {
        return entrees.size();
    }

    /** Oublie toutes les adresses (isolation des tests d'intégration ; hors contrat du domaine). */
    public synchronized void vider() {
        entrees.clear();
    }

    private void libererUnePlace(Instant maintenant) {
        entrees.values().removeIf(entree -> entree.estOubliee(maintenant));
        if (entrees.size() >= CAPACITE) {
            entrees.entrySet().stream()
                    .min(Comparator.<Map.Entry<AdresseSuivie, Entree>, Boolean>comparing(
                                    element -> element.getValue().estPlafonnee(maintenant))
                            .thenComparing(element -> element.getValue().echecs().dernierEchec().orElse(Instant.MIN)))
                    .map(Map.Entry::getKey)
                    .ifPresent(entrees::remove);
        }
    }

    private record Entree(EchecsParAdresse echecs, PlafondEchecsParAdresse plafond) {

        boolean estOubliee(Instant maintenant) {
            return echecs.estOubliee(maintenant, plafond);
        }

        boolean estPlafonnee(Instant maintenant) {
            return echecs.estPlafonnee(maintenant, plafond);
        }
    }
}
