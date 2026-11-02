package fr.backyard.tracker.comptes.application;

import fr.backyard.tracker.comptes.domaine.Compte;
import fr.backyard.tracker.comptes.domaine.DepotComptes;
import fr.backyard.tracker.comptes.domaine.DonneesCompteInvalidesException;
import fr.backyard.tracker.comptes.domaine.RechercheCoureurs;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Recherche, en lecture seule, des coureurs connectables dont le pseudo contient un fragment (9.5), au plus
 * {@value #LIMITE} résultats triés par pseudo normalisé. Un résultat de plus est demandé au dépôt pour savoir s'il en
 * existe d'autres. Journal sans le fragment ni les pseudos trouvés.
 */
@Service
public class RechercherCoureurs {

    /** Nombre maximal de coureurs renvoyés. */
    public static final int LIMITE = 10;

    private static final Logger JOURNAL = System.getLogger(RechercherCoureurs.class.getName());

    private final DepotComptes depotComptes;

    public RechercherCoureurs(DepotComptes depotComptes) {
        this.depotComptes = depotComptes;
    }

    /** @throws DonneesCompteInvalidesException saisie absente, trop courte ou trop longue : dépôt non interrogé */
    @Transactional(readOnly = true)
    public Resultat executer(UUID appelantId, String saisie) {
        List<Compte> trouves = coureursConnectables(RechercheCoureurs.de(saisie));
        Resultat resultat = new Resultat(trouves.stream().limit(LIMITE).toList(), trouves.size() > LIMITE);
        JOURNAL.log(Level.INFO, "Recherche de coureurs (appelant {0}, {1} résultats)", appelantId,
                resultat.coureurs().size());
        return resultat;
    }

    /** Recontrôle chaque Compte lu : une divergence du filtre du dépôt ne laisse jamais passer un Compte non éligible. */
    private List<Compte> coureursConnectables(RechercheCoureurs recherche) {
        if (recherche.aucunPseudoPossible()) {
            return List.of();
        }
        return depotComptes.rechercherCoureursConnectables(recherche.fragment(), LIMITE + 1).stream()
                .filter(Compte::estCoureurConnectable)
                .toList();
    }

    /** Au plus {@value #LIMITE} coureurs, et vrai si d'autres correspondent. */
    public record Resultat(List<Compte> coureurs, boolean plusDeResultats) {

        public Resultat {
            coureurs = List.copyOf(coureurs);
        }
    }
}
