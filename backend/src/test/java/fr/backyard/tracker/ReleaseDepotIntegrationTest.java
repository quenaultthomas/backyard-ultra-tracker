package fr.backyard.tracker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Contrôles du dépôt pour la publication de la 0.5.0 (incrément 7.4) : versions, CHANGELOG, documentation, scripts. */
class ReleaseDepotIntegrationTest {

    private static final String VERSION = "0.5.0";
    private static final Path RACINE = racine();

    private static Path racine() {
        Path courant = Path.of("").toAbsolutePath();
        while (courant != null && !Files.exists(courant.resolve("CHANGELOG.md"))) {
            courant = courant.getParent();
        }
        if (courant == null) {
            throw new IllegalStateException("Racine du dépôt introuvable depuis " + Path.of("").toAbsolutePath());
        }
        return courant;
    }

    private static String lire(String chemin) throws IOException {
        return Files.readString(RACINE.resolve(chemin), StandardCharsets.UTF_8);
    }

    private static String sectionChangelog(String version) throws IOException {
        String texte = lire("CHANGELOG.md");
        int debut = texte.indexOf("## [" + version + "]");
        assertThat(debut).as("section [" + version + "]").isGreaterThanOrEqualTo(0);
        Matcher suivante = Pattern.compile("(?m)^## \\[").matcher(texte);
        int fin = texte.length();
        while (suivante.find()) {
            if (suivante.start() > debut) {
                fin = suivante.start();
                break;
            }
        }
        return texte.substring(debut, fin);
    }

    private static String sectionDeploiement(String titre) throws IOException {
        String texte = lire("docs/deploiement.md");
        int debut = texte.indexOf("\n## " + titre);
        assertThat(debut).as("section « " + titre + " » de deploiement.md").isGreaterThanOrEqualTo(0);
        int fin = texte.indexOf("\n## ", debut + 1);
        return texte.substring(debut, fin < 0 ? texte.length() : fin);
    }

    @Test
    @DisplayName("CA1 : pom, package.json et package-lock.json de frontend et e2e valent 0.5.0")
    void ca1_versions_des_sources() throws IOException {
        assertThat(lire("backend/pom.xml")).contains("\n    <version>" + VERSION + "</version>\n");
        for (String module : List.of("frontend", "e2e")) {
            String paquet = lire(module + "/package.json");
            assertThat(paquet).as(module + "/package.json").contains("\"version\": \"" + VERSION + "\"");
            String verrou = lire(module + "/package-lock.json");
            Matcher racineVerrou = Pattern.compile("(?s)\\A\\s*\\{\\s*\"name\":[^,]*,\\s*\"version\": \"([^\"]+)\".*?\"packages\": \\{\\s*\"\": \\{[^}]*?\"version\": \"([^\"]+)\"")
                    .matcher(verrou);
            assertThat(racineVerrou.find()).as(module + "/package-lock.json : entrées racine").isTrue();
            assertThat(racineVerrou.group(1)).as(module + " lock version").isEqualTo(VERSION);
            assertThat(racineVerrou.group(2)).as(module + " lock packages[\"\"].version").isEqualTo(VERSION);
        }
    }

    @Test
    @DisplayName("CA1 : aucune version 0.4.0 hors CHANGELOG, audit, specs et workflow (commentaire)")
    void ca1_plus_de_0_4_0_dans_les_sources() throws IOException {
        for (String chemin : List.of("backend/pom.xml", "frontend/package.json", "e2e/package.json", "e2e/package-lock.json",
                ".env.example", "docker-compose.yml", "docker-compose.prod.yml", "docker-compose.e2e.yml")) {
            assertThat(lire(chemin)).as(chemin).doesNotContain("0.4.0");
        }
        // Écart connu : le commentaire « v0.4.0 » de .github/workflows/release.yml est conservé (hors critère strict).
        // Le package-lock du frontend ne cite 0.4.0 que pour une dépendance tierce (gen-mapping 0.4.0-beta.0).
        assertThat(lire("frontend/package-lock.json").replace("0.4.0-beta.0", "")).doesNotContain("0.4.0");
        assertThat(lire("docs/deploiement.md")).doesNotContain("0.4.0");
    }

    @Test
    @DisplayName("CA3 : .env.example fixe VERSION=0.5.0 et cite 0.5.0 et 0.5.0-rc.1 ; deploiement.md cite les mêmes exemples")
    void ca3_env_example_et_deploiement() throws IOException {
        String env = lire(".env.example");
        assertThat(env).containsPattern("(?m)^VERSION=0\\.5\\.0$");
        assertThat(env).contains("0.5.0, ou 0.5.0-rc.1");
        String deploiement = lire("docs/deploiement.md");
        assertThat(deploiement).contains("0.5.0").contains("0.5.0-rc.1");
    }

    @Test
    @DisplayName("CA4 : la section [0.5.0] précède la 0.4.0, est datée, cite jalons 5 à 7 et les quatre variables")
    void ca4_changelog_0_5_0() throws IOException {
        String texte = lire("CHANGELOG.md");
        assertThat(texte.indexOf("## [0.5.0]")).isPositive().isLessThan(texte.indexOf("## [0.4.0]"));
        String section = sectionChangelog(VERSION);
        assertThat(section).containsPattern("(?m)^## \\[0\\.5\\.0\\] - \\d{4}-\\d{2}-\\d{2}$");
        assertThat(section).contains("Jalon 5", "Jalon 6", "Jalon 7", "BACKYARD_LIMITE_DEBIT_PUBLIC_PAR_MINUTE",
                "COOKIES_SECURE", "HACHAGE_CONCURRENCE_MAX_PAR_IP", "BACKYARD_PROXY_DE_CONFIANCE", "HSTS",
                "Changements à l'exploitation", "Limites connues", "### Sécurité");
        assertThat(section).contains("limite « pas d'en-tête HSTS » de la 0.4.0 est levée");
    }

    @Test
    @DisplayName("CA4 : le lien [0.5.0] existe (écart connu : liens regroupés en bas du fichier, pas en fin de section)")
    void ca4_lien_de_comparaison() throws IOException {
        assertThat(lire("CHANGELOG.md")).contains(
                "[0.5.0]: https://github.com/quenaultthomas/backyard-ultra-tracker/releases/tag/v0.5.0");
    }

    @Test
    @DisplayName("CA4 : toute variable de .env.example absente de la 0.4.0 figure dans la section 0.5.0")
    void ca4_variables_nouvelles_listees() throws IOException {
        String section0_5 = sectionChangelog(VERSION);
        // Variables de .env.example à la v0.4.0 (git show v0.4.0:.env.example) : la 0.4.0 ne les nomme pas toutes.
        List<String> deja0_4 = List.of("BASE_NOM", "BASE_UTILISATEUR", "BASE_MOT_DE_PASSE", "VERSION", "DOMAINE",
                "CONNEXION_ECHECS_MAX", "CONNEXION_BLOCAGE_SECONDES", "HACHAGE_CONCURRENCE_MAX", "API_MEMOIRE_MAX",
                "BASE_MEMOIRE_MAX", "WEB_MEMOIRE_MAX", "ADMIN_MASTER_PSEUDO", "ADMIN_MASTER_MOT_DE_PASSE", "BASE_URL",
                "DEMO_FORCER",
                // Ajoutées après la 0.5.0 (8.2 RG13) : décrites dans [Unreleased] du CHANGELOG, pas dans la 0.5.0.
                "SESSION_INACTIVITE_ADMIN_SECONDES", "SESSION_INACTIVITE_COUREUR_BENEVOLE_SECONDES",
                // Ajoutées après la 0.5.0 (8.4 RG15) : décrites dans [Unreleased] du CHANGELOG, pas dans la 0.5.0.
                "CONNEXION_ECHECS_PAR_IP_MAX", "CONNEXION_ECHECS_PAR_IP_FENETRE_SECONDES");
        Matcher variables = Pattern.compile("(?m)^#?\\s*([A-Z][A-Z0-9_]+)=").matcher(lire(".env.example"));
        List<String> manquantes = new ArrayList<>();
        while (variables.find()) {
            String nom = variables.group(1);
            if (!deja0_4.contains(nom) && !section0_5.contains(nom)) {
                manquantes.add(nom);
            }
        }
        assertThat(manquantes).as("variables de .env.example ni en 0.4.0 ni en 0.5.0").isEmpty();
    }

    @ParameterizedTest(name = "CA5 : tag {0} accepté par la vérification du workflow Release")
    @ValueSource(strings = {"v0.5.0-rc.1", "v0.5.0"})
    @DisplayName("CA5 : tags acceptés")
    void ca5_tags_acceptes(String tag) throws Exception {
        Resultat resultat = rejouerVerificationRelease(tag);
        assertThat(resultat.code).as(resultat.sortie).isZero();
    }

    @ParameterizedTest(name = "CA5 : tag {0} refusé par la vérification du workflow Release")
    @ValueSource(strings = {"v0.5.1", "v0.4.0", "v0.5", "v0.5.0-beta.1"})
    @DisplayName("CA5 : tags refusés")
    void ca5_tags_refuses(String tag) throws Exception {
        Resultat resultat = rejouerVerificationRelease(tag);
        assertThat(resultat.code).as(resultat.sortie).isNotZero();
        assertThat(resultat.sortie).contains("::error::");
    }

    @Test
    @DisplayName("CA7 : vite >= 8.3.3 résolu dans le package-lock, @angular/core en version majeure 22")
    void ca7_outillage_de_build() throws IOException {
        String verrou = lire("frontend/package-lock.json");
        int[] vite = versionResolue(verrou, "vite");
        assertThat(vite[0] * 1_000_000 + vite[1] * 1_000 + vite[2]).as("vite résolu").isGreaterThanOrEqualTo(8_003_003);
        assertThat(versionResolue(verrou, "@angular/build")[0]).isEqualTo(22);
        assertThat(versionResolue(verrou, "@angular/core")[0]).isEqualTo(22);
        assertThat(lire("frontend/package.json")).contains("\"overrides\"");
    }

    @Test
    @DisplayName("CA9 : la section « Contrôle des vulnérabilités » donne date, commandes npm, Maven et images épinglées")
    void ca9_controle_des_vulnerabilites() throws IOException {
        String section = sectionDeploiement("Contrôle des vulnérabilités");
        assertThat(section).contains("npm audit --omit=dev && npm audit", "versions-maven-plugin", "aquasec/trivy:",
                "postgres:17.11-alpine", "Résultats du 2026-", "Acceptées", "HIGH");
        assertThat(section).doesNotContain("trivy:latest");
        assertThat(section).as("chaque vulnérabilité restante est justifiée par son exposition").contains("Exposition réelle");
    }

    @Test
    @DisplayName("CA16 : Sauvegarde, Restauration et Répétition générale contiennent les consignes attendues")
    void ca16_sections_de_documentation() throws IOException {
        String texte = lire("docs/deploiement.md");
        assertThat(texte).doesNotContain("aucune tâche planifiée fournie");
        String sauvegarde = sectionDeploiement("Sauvegarde");
        assertThat(sauvegarde).contains("juste avant le départ de la première Boucle", "annonce du Vainqueur",
                "0 * * * *", "0 3 * * *", "scp ", "rsync ", "cron", "ne protège pas d'une panne du disque",
                "--verifier-restauration", "SAUVEGARDE_DOSSIER", "SAUVEGARDE_CONSERVER", "COMPOSE_FICHIERS");
        assertThat(sectionDeploiement("Restauration")).contains("pg_restore", "--clean --if-exists --no-owner",
                "$PROD ps", "/api/version");
        String repetition = sectionDeploiement("Répétition générale d'une version");
        for (int etape = 1; etape <= 9; etape++) {
            assertThat(repetition).as("étape " + etape).containsPattern("(?m)^" + etape + "\\. \\*\\*");
        }
        assertThat(repetition).doesNotContainPattern("(?m)^10\\. ");
        assertThat(repetition.toLowerCase()).contains("v0.5.0-rc.1", "v0.5.0", "strict-transport-security", "pre-release",
                "jamais déplacé");
    }

    @Test
    @DisplayName("CA13 : SAUVEGARDE_CONSERVER à 0, négatif ou non numérique, code 2, message citant la variable, rien d'écrit")
    void ca13_conserver_invalide(@TempDir Path dossier) throws Exception {
        for (String valeur : List.of("0", "-3", "abc")) {
            Resultat resultat = lancerSauvegarde(dossier.resolve("dest"), valeur);
            assertThat(resultat.code).as(valeur + " : " + resultat.sortie).isEqualTo(2);
            assertThat(resultat.sortie).contains("SAUVEGARDE_CONSERVER");
            assertThat(dossier.resolve("dest")).doesNotExist();
        }
    }

    @Test
    @DisplayName("CA13 : une option inconnue fait échouer le script en code 2")
    void option_inconnue(@TempDir Path dossier) throws Exception {
        Resultat resultat = lancerSauvegarde(dossier.resolve("dest"), "3", "--nimporte-quoi");
        assertThat(resultat.code).as(resultat.sortie).isEqualTo(2);
        assertThat(dossier.resolve("dest")).doesNotExist();
    }

    @Test
    @DisplayName("CA8/RG8 : le script est exécutable et n'affiche aucun contenu de .env ni mot de passe")
    void script_executable_sans_secret() throws IOException {
        Path script = RACINE.resolve("scripts/sauvegarder.sh");
        assertThat(Files.isExecutable(script)).isTrue();
        String contenu = Files.readString(script);
        assertThat(contenu).doesNotContain("cat .env").doesNotContain("source .env").doesNotContain("PGPASSWORD");
        assertThat(contenu).contains("pg_restore --list", "restauration_verif", "-Fc");
    }

    // ---- utilitaires ----

    private record Resultat(int code, String sortie) {}

    private static int[] versionResolue(String verrou, String paquet) {
        Matcher m = Pattern.compile("\"node_modules/" + Pattern.quote(paquet) + "\": \\{\\s*\"version\": \"(\\d+)\\.(\\d+)\\.(\\d+)")
                .matcher(verrou);
        assertThat(m.find()).as(paquet + " dans package-lock.json").isTrue();
        return new int[] {Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)), Integer.parseInt(m.group(3))};
    }

    private static Resultat lancerSauvegarde(Path destination, String conserver, String... arguments) throws Exception {
        List<String> commande = new ArrayList<>(List.of("bash", RACINE.resolve("scripts/sauvegarder.sh").toString()));
        commande.addAll(List.of(arguments));
        ProcessBuilder constructeur = new ProcessBuilder(commande).directory(RACINE.toFile()).redirectErrorStream(true);
        constructeur.environment().put("SAUVEGARDE_DOSSIER", destination.toString());
        constructeur.environment().put("SAUVEGARDE_CONSERVER", conserver);
        return executer(constructeur);
    }

    /** Rejoue le script de l'étape « Vérifier que le tag correspond » de release.yml, extrait tel quel. */
    private static Resultat rejouerVerificationRelease(String tag) throws Exception {
        assumeTrue(commandeDisponible("jq"), "jq requis par le workflow");
        String workflow = lire(".github/workflows/release.yml");
        int debut = workflow.indexOf("id: lire");
        int run = workflow.indexOf("run: |\n", debut) + "run: |\n".length();
        int fin = workflow.indexOf("\n\n", run);
        String script = workflow.substring(run, fin).lines().map(l -> l.startsWith("          ") ? l.substring(10) : l)
                .reduce("", (a, b) -> a + b + "\n")
                // ${var,,} exige bash 4 (absent de macOS) et n'intervient pas dans la vérification des versions.
                .replace("${GITHUB_REPOSITORY_OWNER,,}", "${GITHUB_REPOSITORY_OWNER}");
        Path sortieGithub = Files.createTempFile("github-output", ".txt");
        try {
            ProcessBuilder constructeur = new ProcessBuilder("bash", "-c", script).directory(RACINE.toFile()).redirectErrorStream(true);
            Map<String, String> env = constructeur.environment();
            env.put("GITHUB_REF_NAME", tag);
            env.put("GITHUB_OUTPUT", sortieGithub.toString());
            env.put("GITHUB_REPOSITORY_OWNER", "Proprietaire");
            return executer(constructeur);
        } finally {
            Files.deleteIfExists(sortieGithub);
        }
    }

    private static boolean commandeDisponible(String nom) throws Exception {
        Process p = new ProcessBuilder("bash", "-c", "command -v " + nom).redirectErrorStream(true).start();
        p.getInputStream().readAllBytes();
        return p.waitFor(10, TimeUnit.SECONDS) && p.exitValue() == 0;
    }

    private static Resultat executer(ProcessBuilder constructeur) throws Exception {
        Process processus = constructeur.start();
        String sortie = new String(processus.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertThat(processus.waitFor(60, TimeUnit.SECONDS)).as("fin du processus dans les 60 s").isTrue();
        return new Resultat(processus.exitValue(), sortie);
    }
}
