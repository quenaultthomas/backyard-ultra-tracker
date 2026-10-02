package fr.backyard.tracker.comptes.infrastructure;

import fr.backyard.tracker.comptes.application.InitialiserAdminMaster;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Initialise l'admin master à chaque démarrage, après les migrations Liquibase. Une configuration invalide
 * fait échouer le démarrage : l'api n'est jamais déclarée saine.
 */
@Component
@EnableConfigurationProperties(AdminMasterProprietes.class)
public class InitialisationAdminMaster implements ApplicationRunner {

    private final InitialiserAdminMaster initialiserAdminMaster;
    private final AdminMasterProprietes proprietes;

    public InitialisationAdminMaster(InitialiserAdminMaster initialiserAdminMaster, AdminMasterProprietes proprietes) {
        this.initialiserAdminMaster = initialiserAdminMaster;
        this.proprietes = proprietes;
    }

    @Override
    public void run(ApplicationArguments arguments) {
        initialiserAdminMaster.executer(proprietes.pseudo(), proprietes.motDePasse());
    }
}
