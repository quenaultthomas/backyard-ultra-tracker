package fr.backyard.tracker.comptes.infrastructure;

import fr.backyard.tracker.comptes.domaine.PolitiqueBlocage;
import fr.backyard.tracker.comptes.domaine.RegistreTentativesConnexion;
import fr.backyard.tracker.comptes.domaine.TentativesConnexion;
import java.time.Instant;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Registre des tentatives de connexion en mémoire de l'api : remis à zéro au redémarrage, non partagé
 * entre instances. Borné à {@value #CAPACITE} clés ; à saturation, les entrées oubliées sont purgées,
 * puis la plus ancienne non bloquée est évincée, une clé bloquée ne l'étant qu'en dernier recours.
 * Opérations sérialisées : atomiques par clé et sans dépassement de la capacité.
 */
@Component
public class RegistreTentativesConnexionEnMemoire implements RegistreTentativesConnexion {

    public static final int CAPACITE = 10_000;

    private final Map<String, TentativesConnexion> entrees = new HashMap<>();

    @Override
    public synchronized TentativesConnexion constater(String cle, Instant maintenant) {
        return entrees.getOrDefault(cle, TentativesConnexion.aucune());
    }

    @Override
    public synchronized TentativesConnexion enregistrerEchec(String cle, Instant maintenant,
                                                             PolitiqueBlocage politique) {
        if (!entrees.containsKey(cle) && entrees.size() >= CAPACITE) {
            libererUnePlace(maintenant, politique);
        }
        TentativesConnexion apres = constater(cle, maintenant).apresEchec(maintenant, politique);
        entrees.put(cle, apres);
        return apres;
    }

    @Override
    public synchronized void effacer(String cle) {
        entrees.remove(cle);
    }

    /** Nombre de clés suivies (tests et supervision). */
    public synchronized int taille() {
        return entrees.size();
    }

    /** Oublie toutes les clés (isolation des tests d'intégration ; hors contrat du domaine). */
    public synchronized void vider() {
        entrees.clear();
    }

    private void libererUnePlace(Instant maintenant, PolitiqueBlocage politique) {
        entrees.values().removeIf(tentatives -> tentatives.estOubliee(maintenant, politique));
        if (entrees.size() >= CAPACITE) {
            entrees.entrySet().stream()
                    .min(Comparator.<Map.Entry<String, TentativesConnexion>, Boolean>comparing(
                                    entree -> entree.getValue().estBloquee(maintenant))
                            .thenComparing(entree -> entree.getValue().dernierEchec().orElse(Instant.MIN)))
                    .map(Map.Entry::getKey)
                    .ifPresent(entrees::remove);
        }
    }
}
