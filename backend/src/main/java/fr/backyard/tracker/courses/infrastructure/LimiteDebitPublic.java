package fr.backyard.tracker.courses.infrastructure;

import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Limite de débit par IP des {@code GET} d'une route publique et de ses sous-chemins ({@code prefixe} ou
 * {@code prefixe/...}), en fenêtre fixe d'une minute alignée sur la minute de l'horloge (7.2 RG4, RG5). Une instance
 * par route, chacune avec son plafond et ses compteurs : {@code /api/courses} et {@code /api/photos} (10.2 RG6).
 * Compteurs en mémoire, remplacés à chaque
 * nouvelle fenêtre : la mémoire est bornée par les IP d'une minute. Une limite de 0 désactive le contrôle. Aucune
 * logique métier : protection de la base, appliquée avant tout accès SQL. Au plus une ligne WARN par IP et par
 * fenêtre, sans cookie ni donnée de compte.
 */
public final class LimiteDebitPublic {

    /** Verdict : refusée, {@code reessayerDansSecondes} (>= 1) mène à la fin de la fenêtre ; autorisée, 0. */
    public record Decision(boolean autorisee, int reessayerDansSecondes) {

        static final Decision AUTORISEE = new Decision(true, 0);
    }

    private record Fenetre(long minute, Map<String, AtomicInteger> requetesParIp) {
    }

    private static final Logger JOURNAL = LoggerFactory.getLogger(LimiteDebitPublic.class);
    private static final long MILLISECONDES_PAR_MINUTE = 60_000L;

    private final String prefixe;
    private final int limiteParMinute;
    private final Clock horloge;
    private volatile Fenetre fenetre = new Fenetre(Long.MIN_VALUE, Map.of());

    /**
     * @param prefixe         route limitée, sans barre finale (ex. {@code /api/courses})
     * @param limiteParMinute requêtes admises par IP et par minute, 0 pour aucune limite (jamais négatif)
     */
    public LimiteDebitPublic(String prefixe, int limiteParMinute, Clock horloge) {
        if (limiteParMinute < 0) {
            throw new IllegalArgumentException("La limite de débit public doit être >= 0 (reçu : " + limiteParMinute + ")");
        }
        this.prefixe = prefixe;
        this.limiteParMinute = limiteParMinute;
        this.horloge = horloge;
    }

    /** Compte la requête si elle est publique et limitée, puis l'autorise ou la refuse. */
    public Decision verifier(String methodeHttp, String chemin, String ip) {
        if (limiteParMinute == 0 || !estPublique(methodeHttp, chemin)) {
            return Decision.AUTORISEE;
        }
        Instant maintenant = horloge.instant();
        long minute = Math.floorDiv(maintenant.toEpochMilli(), MILLISECONDES_PAR_MINUTE);
        int rang = fenetreDe(minute).requetesParIp().computeIfAbsent(ip, cle -> new AtomicInteger()).incrementAndGet();
        if (rang <= limiteParMinute) {
            return Decision.AUTORISEE;
        }
        if (rang == limiteParMinute + 1) {
            JOURNAL.warn("Débit public dépassé sur {} pour l'IP {} : plus de {} requêtes dans la minute, refus jusqu'à "
                    + "la minute suivante", prefixe, ip, limiteParMinute);
        }
        return new Decision(false, secondesAvantFinDeFenetre(maintenant, minute));
    }

    private boolean estPublique(String methodeHttp, String chemin) {
        return "GET".equals(methodeHttp) && (prefixe.equals(chemin) || chemin.startsWith(prefixe + "/"));
    }

    private Fenetre fenetreDe(long minute) {
        Fenetre courante = fenetre;
        if (courante.minute() == minute) {
            return courante;
        }
        synchronized (this) {
            if (fenetre.minute() != minute) {
                fenetre = new Fenetre(minute, new ConcurrentHashMap<>());
            }
            return fenetre;
        }
    }

    /** Arrondi au-dessus, jamais moins d'une seconde. */
    private static int secondesAvantFinDeFenetre(Instant maintenant, long minute) {
        long restantMillisecondes = (minute + 1) * MILLISECONDES_PAR_MINUTE - maintenant.toEpochMilli();
        return (int) Math.max(1, Math.ceilDiv(restantMillisecondes, 1000L));
    }
}
