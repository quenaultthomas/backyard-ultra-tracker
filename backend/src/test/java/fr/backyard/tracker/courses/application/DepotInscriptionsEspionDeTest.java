package fr.backyard.tracker.courses.application;

import fr.backyard.tracker.courses.domaine.DepotInscriptions;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;

/**
 * Espion du port DepotInscriptions (6.2) : délègue à un double en mémoire et enregistre le nom de chaque méthode
 * appelée, ainsi que "inscriptions" dans le journal partagé des lectures si il est posé.
 */
final class DepotInscriptionsEspionDeTest {

    final DepotInscriptionsEnMemoireDeTest enMemoire = new DepotInscriptionsEnMemoireDeTest();
    final List<String> appels = new ArrayList<>();
    /** Si posé, reçoit "inscriptions" à chaque lecture par Course (ordre des lectures). */
    List<String> journalDesLectures;
    final DepotInscriptions port = (DepotInscriptions) Proxy.newProxyInstance(
            DepotInscriptions.class.getClassLoader(), new Class<?>[] {DepotInscriptions.class},
            (proxy, methode, arguments) -> {
                appels.add(methode.getName());
                if (journalDesLectures != null && methode.getName().equals("parCourse")) {
                    journalDesLectures.add("inscriptions");
                }
                try {
                    return methode.invoke(enMemoire, arguments);
                } catch (InvocationTargetException e) {
                    throw e.getCause();
                }
            });
}
