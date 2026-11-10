package fr.backyard.tracker.comptes.application;

import fr.backyard.tracker.comptes.domaine.FichierPhoto;
import fr.backyard.tracker.comptes.domaine.JetonsPhotosPortes;
import fr.backyard.tracker.comptes.domaine.PlanNettoyage;
import fr.backyard.tracker.comptes.domaine.PolitiqueNettoyagePhotos;
import fr.backyard.tracker.comptes.domaine.RepertoirePhotos;
import fr.backyard.tracker.partage.domaine.FormatImage;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.stereotype.Service;

/**
 * Nettoyage des fichiers de photo orphelins (correctif audit jalon 10 RG4 à RG7), lancé une fois au démarrage : liste
 * le répertoire, vérifie en une seule recherche les jetons portés, puis supprime ce que décide
 * {@link PolitiqueNettoyagePhotos}. Ne lève jamais d'exception : un répertoire illisible ou une base inaccessible donne
 * un WARN unique sans aucune suppression ; un échec sur un fichier est compté et le balayage continue. Le journal ne
 * porte que des nombres (jamais de nom, jeton, chemin ni Compte). Aucune écriture en base.
 */
@Service
public class NettoyerPhotosOrphelines {

    private static final Logger JOURNAL = System.getLogger(NettoyerPhotosOrphelines.class.getName());

    /** Bilan d'un balayage. */
    public record Bilan(int orphelinsSupprimes, int temporairesSupprimes, int ignores, int erreurs) {

        static final Bilan VIDE = new Bilan(0, 0, 0, 0);
        static final Bilan IMPOSSIBLE = new Bilan(0, 0, 0, 1);
    }

    private final RepertoirePhotos repertoire;
    private final JetonsPhotosPortes jetonsPhotosPortes;
    private final PolitiqueNettoyagePhotos politique;

    public NettoyerPhotosOrphelines(RepertoirePhotos repertoire, JetonsPhotosPortes jetonsPhotosPortes, Clock horloge) {
        this.repertoire = repertoire;
        this.jetonsPhotosPortes = jetonsPhotosPortes;
        this.politique = new PolitiqueNettoyagePhotos(horloge);
    }

    public Bilan executer() {
        Optional<List<FichierPhoto>> fichiers;
        try {
            fichiers = repertoire.lister();
        } catch (RuntimeException echec) {
            return impossible("lecture du répertoire en échec", echec);
        }
        if (fichiers.isEmpty()) {
            return journaliser(Bilan.VIDE);
        }
        Map<String, FormatImage> portes;
        try {
            portes = jetonsPortes(politique.jetonsConformes(fichiers.get()));
        } catch (RuntimeException echec) {
            return impossible("lecture des photos en base en échec", echec);
        }
        return journaliser(appliquer(politique.planifier(fichiers.get(), portes)));
    }

    /** Aucune recherche s'il n'y a aucun fichier de photo conforme. */
    private Map<String, FormatImage> jetonsPortes(Set<String> jetons) {
        return jetons.isEmpty() ? Map.of() : jetonsPhotosPortes.portesParmi(jetons);
    }

    private Bilan appliquer(PlanNettoyage plan) {
        if (plan.suspendu()) {
            JOURNAL.log(Level.WARNING, "Nettoyage des photos suspendu : trop de fichiers orphelins ({0} sur {1})",
                    nombre(plan.nombreOrphelins()), nombre(plan.nombreConformes()));
        }
        int orphelins = supprimer(plan.orphelinsASupprimer());
        int temporaires = supprimer(plan.temporairesASupprimer());
        int erreurs = plan.orphelinsASupprimer().size() + plan.temporairesASupprimer().size() - orphelins - temporaires;
        return new Bilan(orphelins, temporaires, plan.ignores(), erreurs);
    }

    /** Nombre de fichiers effectivement supprimés ; un échec n'interrompt pas le balayage. */
    private int supprimer(List<String> noms) {
        int supprimes = 0;
        for (String nom : noms) {
            try {
                repertoire.supprimer(nom);
                supprimes++;
            } catch (RuntimeException echec) {
                JOURNAL.log(Level.DEBUG, "Nettoyage des photos : suppression d''un fichier en échec ({0})",
                        echec.getClass().getSimpleName());
            }
        }
        return supprimes;
    }

    private static Bilan journaliser(Bilan bilan) {
        JOURNAL.log(Level.INFO, "Nettoyage des photos : {0} fichier(s) orphelin(s) supprimé(s), {1} fichier(s) "
                        + "temporaire(s) supprimé(s), {2} fichier(s) ignoré(s)", nombre(bilan.orphelinsSupprimes()),
                nombre(bilan.temporairesSupprimes()), nombre(bilan.ignores()));
        if (bilan.erreurs() > 0) {
            JOURNAL.log(Level.WARNING, "Nettoyage des photos : {0} erreur(s)", nombre(bilan.erreurs()));
        }
        return bilan;
    }

    /** WARN unique, sans le message de l'exception (il peut citer un chemin) : seul son type est journalisé. */
    private static Bilan impossible(String cause, RuntimeException echec) {
        JOURNAL.log(Level.WARNING, "Nettoyage des photos impossible, aucun fichier supprimé : {0} ({1})", cause,
                echec.getClass().getSimpleName());
        return Bilan.IMPOSSIBLE;
    }

    /** Sans séparateur de milliers. */
    private static String nombre(int valeur) {
        return String.valueOf(valeur);
    }
}
