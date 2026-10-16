package fr.backyard.tracker;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import ch.qos.logback.core.spi.FilterReply;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import org.slf4j.LoggerFactory;

/**
 * Capture du journal Logback pour les tests d'intégration, stable face aux autres threads et aux autres contextes
 * Spring mis en cache par la suite de tests.
 *
 * <p>Deux sources d'instabilité sont neutralisées :
 *
 * <ul>
 *   <li><b>Bruit d'infrastructure d'autres contextes.</b> Les pools Hikari, les clients Docker et Testcontainers des
 *       contextes Spring conservés en cache continuent de journaliser en arrière-plan (par exemple des WARN
 *       {@code HikariPool-N - Failed to validate connection} sur une base dont le conteneur est arrêté). Ces lignes ne
 *       viennent jamais de la requête observée : elles sont écartées dès l'ajout (filtre de l'appender), pour toutes les
 *       lectures. Rien d'autre n'est écarté de {@link #evenements()}, afin que les contrôles « aucun jeton, pseudo, mot
 *       de passe, nom ou UUID dans le journal » continuent de couvrir aussi les journaux de Spring et d'Hibernate.
 *   <li><b>Lecture concurrente.</b> La liste est une {@link CopyOnWriteArrayList} et toutes les lectures renvoient une
 *       copie ({@link List#copyOf}) : jamais d'itération ni de {@code subList} sur la liste vivante (une vue
 *       {@code subList} d'une liste modifiée lève {@code ConcurrentModificationException}).
 * </ul>
 *
 * <p>Les assertions sur le niveau (« aucune ligne INFO ou supérieure », « une seule ligne WARN ») portent sur
 * {@link #evenementsApplicatifs()} et {@link #applicatifsDepuis(int)} : la portée est le code de l'application
 * ({@value #PAQUET_APPLICATION}), les lignes INFO ou WARN de Spring, d'Hibernate ou d'un autre contexte n'étant pas
 * l'objet de ces assertions.
 *
 * <p>Usage : créer avec {@link #demarrer()} (ou {@link #demarrerEnDebug()}) dans le test, appeler {@link #fermer()} dans
 * un {@code @AfterEach} (idempotent). Si un contexte Spring démarre pendant la capture, {@link #rattacher()} rétablit
 * l'appender et les niveaux, car Spring Boot réinitialise Logback.
 */
public final class JournalApplicatif implements AutoCloseable {

    /** Paquet racine du code de l'application. */
    public static final String PAQUET_APPLICATION = "fr.backyard.tracker";

    /** Loggers d'infrastructure dont les lignes de fond (autres contextes Spring) sont écartées. */
    private static final List<String> BRUIT_D_INFRASTRUCTURE =
            List.of("com.zaxxer.hikari", "org.testcontainers", "com.github.dockerjava", "tc.");

    private final ListAppender<ILoggingEvent> appender = new ListAppender<>();
    private final Logger racine = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
    /** Niveaux d'origine des loggers modifiés (null = hérité), restaurés par {@link #fermer()}. */
    private final Map<String, Level> niveauxInitiaux = new LinkedHashMap<>();

    private final Map<String, Level> niveauxDemandes = new LinkedHashMap<>();
    private boolean ferme;

    private JournalApplicatif() {
        appender.list = new CopyOnWriteArrayList<>();
        appender.addFilter(new ch.qos.logback.core.filter.Filter<ILoggingEvent>() {
            @Override
            public FilterReply decide(ILoggingEvent evenement) {
                String logger = evenement.getLoggerName();
                for (String bruit : BRUIT_D_INFRASTRUCTURE) {
                    if (logger.startsWith(bruit)) {
                        return FilterReply.DENY;
                    }
                }
                return FilterReply.NEUTRAL;
            }
        });
        appender.start();
        racine.addAppender(appender);
    }

    /** Capture sans toucher aux niveaux de journalisation. */
    public static JournalApplicatif demarrer() {
        return new JournalApplicatif();
    }

    /** Capture avec la racine en DEBUG et Hibernate en INFO (le SQL détaillé n'est pas l'objet des tests). */
    public static JournalApplicatif demarrerEnDebug() {
        return demarrer().niveau(Logger.ROOT_LOGGER_NAME, Level.DEBUG).niveau("org.hibernate", Level.INFO);
    }

    /** Fixe le niveau d'un logger jusqu'à {@link #fermer()}, qui rétablit le niveau d'origine. */
    public JournalApplicatif niveau(String logger, Level niveau) {
        Logger cible = (Logger) LoggerFactory.getLogger(logger);
        niveauxInitiaux.putIfAbsent(logger, cible.getLevel());
        niveauxDemandes.put(logger, niveau);
        cible.setLevel(niveau);
        return this;
    }

    /**
     * Rattache l'appender et réapplique les niveaux (idempotent) : le démarrage de Spring Boot réinitialise Logback, ce
     * qui détache et arrête les appenders existants.
     */
    public void rattacher() {
        if (ferme) {
            return;
        }
        if (!appender.isStarted()) {
            appender.start();
        }
        racine.detachAppender(appender);
        racine.addAppender(appender);
        niveauxDemandes.forEach((logger, niveau) -> ((Logger) LoggerFactory.getLogger(logger)).setLevel(niveau));
    }

    // ---------------------------------------------------------------- lectures (copies)

    /** Tous les événements capturés (hors bruit d'infrastructure), copie figée. */
    public List<ILoggingEvent> evenements() {
        return List.copyOf(appender.list);
    }

    /** Événements du code de l'application ({@value #PAQUET_APPLICATION}) uniquement, copie figée. */
    public List<ILoggingEvent> evenementsApplicatifs() {
        return applicatifs(evenements());
    }

    /** Repère pour {@link #depuis(int)} / {@link #entre(int, int)} : nombre d'événements capturés à cet instant. */
    public int marque() {
        return appender.list.size();
    }

    /** Événements capturés depuis un repère, copie figée. */
    public List<ILoggingEvent> depuis(int marque) {
        List<ILoggingEvent> tous = evenements();
        return tous.subList(Math.min(marque, tous.size()), tous.size());
    }

    /** Événements capturés entre deux repères, copie figée. */
    public List<ILoggingEvent> entre(int debut, int fin) {
        List<ILoggingEvent> tous = evenements();
        return tous.subList(Math.min(debut, tous.size()), Math.min(fin, tous.size()));
    }

    /** Événements du code de l'application capturés depuis un repère. */
    public List<ILoggingEvent> applicatifsDepuis(int marque) {
        return applicatifs(depuis(marque));
    }

    /** Événements du code de l'application capturés entre deux repères. */
    public List<ILoggingEvent> applicatifsEntre(int debut, int fin) {
        return applicatifs(entre(debut, fin));
    }

    /** Messages formatés des événements du code de l'application (messages nuls écartés). */
    public List<String> messages() {
        return evenementsApplicatifs().stream().map(ILoggingEvent::getFormattedMessage).filter(m -> m != null).toList();
    }

    /** Messages formatés et messages d'exception de tous les événements capturés, nuls écartés. */
    public List<String> messagesEtExceptions() {
        return evenements().stream().flatMap(e -> {
            var proxy = e.getThrowableProxy();
            return java.util.stream.Stream.of(e.getFormattedMessage(), proxy == null ? null : proxy.getMessage());
        }).filter(m -> m != null).toList();
    }

    /** Vide les événements capturés. */
    public void vider() {
        appender.list.clear();
    }

    private static List<ILoggingEvent> applicatifs(List<ILoggingEvent> evenements) {
        return evenements.stream().filter(e -> e.getLoggerName().startsWith(PAQUET_APPLICATION)).toList();
    }

    // ---------------------------------------------------------------- fin

    /** Détache l'appender et rétablit les niveaux d'origine ; sans effet si déjà fermé. */
    public void fermer() {
        if (ferme) {
            return;
        }
        ferme = true;
        racine.detachAppender(appender);
        appender.stop();
        niveauxInitiaux.forEach((logger, niveau) -> ((Logger) LoggerFactory.getLogger(logger)).setLevel(niveau));
    }

    @Override
    public void close() {
        fermer();
    }
}
