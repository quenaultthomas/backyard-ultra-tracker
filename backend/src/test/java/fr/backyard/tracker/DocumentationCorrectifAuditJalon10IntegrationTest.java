package fr.backyard.tracker;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Contrôles documentaires du correctif de l'audit du jalon 10 : CA1 (variable des photos pour mémoire), CA13
 * (procédures d'exploitation et décision de modération), CA22 (référentiel d'audit). Lecture des fichiers du dépôt.
 */
class DocumentationCorrectifAuditJalon10IntegrationTest {

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

    /** Texte compris entre un titre de section « ## » et le titre « ## » suivant. */
    private static String section(String document, String titre) {
        int debut = document.indexOf("\n## " + titre);
        assertThat(debut).as("section « " + titre + " »").isGreaterThanOrEqualTo(0);
        int fin = document.indexOf("\n## ", debut + 4);
        return document.substring(debut, fin < 0 ? document.length() : fin);
    }

    // ---------------------------------------------------------------- CA1

    @Test
    @DisplayName("CA1 : .env.example porte une ligne commentée # BACKYARD_PHOTOS_REPERTOIRE=/donnees/photos avec son commentaire « pour mémoire » et aucune valeur active ; deploiement.md a sa ligne de tableau ; docker-compose.yml fixe /donnees/photos")
    void ca1_variable_du_repertoire_des_photos_pour_memoire() throws IOException {
        String env = lire(".env.example");

        assertThat(env).contains("# BACKYARD_PHOTOS_REPERTOIRE=/donnees/photos");
        assertThat(env).doesNotContainPattern("(?m)^\\s*BACKYARD_PHOTOS_REPERTOIRE\\s*=");
        int position = env.indexOf("# BACKYARD_PHOTOS_REPERTOIRE=/donnees/photos");
        String commentaire = env.substring(Math.max(0, position - 500), position);
        assertThat(commentaire).contains("pour mémoire").contains("docker-compose.yml");
        assertThat(env).as("aucune valeur secrète").doesNotContainPattern("(?m)^BACKYARD_PHOTOS_REPERTOIRE");

        assertThat(lire("docs/deploiement.md")).containsPattern(
                "(?m)^\\| `BACKYARD_PHOTOS_REPERTOIRE` \\|[^\\n]*`/donnees/photos`[^\\n]*\\|");
        assertThat(lire("docker-compose.yml")).contains("BACKYARD_PHOTOS_REPERTOIRE: /donnees/photos");
    }

    // ---------------------------------------------------------------- CA13

    @Test
    @DisplayName("CA13 : deploiement.md décrit le nettoyage au démarrage (noms conformes, seuil d'une heure, garde-fou, commande logs api, ordre de restauration base puis api)")
    void ca13_section_de_nettoyage_au_demarrage() throws IOException {
        String nettoyage = section(lire("docs/deploiement.md"), "Photos de profil : nettoyage au démarrage");

        assertThat(nettoyage).contains("32 caractères hexadécimaux").contains(".png").contains(".jpg").contains(".webp")
                .contains(".tmp");
        assertThat(nettoyage).contains("une heure");
        assertThat(nettoyage).contains("Garde-fou").contains("10").contains("la moitié")
                .contains("Nettoyage des photos suspendu");
        assertThat(nettoyage).contains("logs api | grep \"Nettoyage des photos\"");
        assertThat(nettoyage).containsIgnoringCase("la base avant de démarrer `api`");
        assertThat(nettoyage).contains("jamais supprimé");
    }

    @Test
    @DisplayName("CA13 : deploiement.md décrit « Retirer une photo en urgence » avec les trois commandes (select, update des deux colonnes à null, exec api rm), les 5 minutes de cache et le rattrapage au redémarrage ; roadmap.md porte la ligne « Modération des photos »")
    void ca13_retrait_en_urgence_et_decision_de_moderation() throws IOException {
        String urgence = section(lire("docs/deploiement.md"), "Retirer une photo en urgence");

        assertThat(urgence).contains(
                "exec base psql -U backyard -d backyard -c \"select id, photo_jeton, photo_format from compte where pseudo = '<pseudo>'\"");
        assertThat(urgence).contains("update compte set photo_jeton = null, photo_format = null where pseudo = '<pseudo>'");
        assertThat(urgence).contains("exec api rm /donnees/photos/<photo_jeton>.<ext>");
        assertThat(urgence).contains("ck_compte_photo_coherente");
        assertThat(urgence).contains("5 minutes");
        assertThat(urgence).containsIgnoringCase("prochain démarrage de `api`");

        String roadmap = lire("docs/roadmap.md");
        String decisions = roadmap.substring(roadmap.indexOf("## Décisions prises"),
                roadmap.indexOf("## Décisions en attente"));
        Matcher ligne = Pattern.compile("(?m)^\\| Modération des photos \\|.*$").matcher(decisions);
        assertThat(ligne.find()).as("ligne « Modération des photos » dans Décisions prises").isTrue();
        assertThat(ligne.group()).contains("seul propriétaire").contains("aucune action admin")
                .contains("docs/deploiement.md").contains("5 minutes").contains("confidentialité");
        assertThat(decisions.split("\\| Modération des photos \\|", -1)).as("une seule ligne").hasSize(2);
    }

    // ---------------------------------------------------------------- CA22

    @Test
    @DisplayName("CA22 : référentiel d'audit : SEC-23, SEC-24, MET-16, PER-06, DEP-09, PER-07, FRT-08 présents une fois, dans la table de leur axe, avec la priorité et l'incrément prévus ; aucun identifiant en double ; les identifiants existants sont toujours présents")
    void ca22_referentiel_d_audit() throws IOException {
        String referentiel = lire("docs/audits/referentiel.md");
        Map<String, List<String>> lignes = new LinkedHashMap<>();
        String axe = null;
        for (String ligne : referentiel.split("\n")) {
            Matcher titre = Pattern.compile("^## ([A-Z]{3}) :").matcher(ligne);
            if (titre.find()) {
                axe = titre.group(1);
            }
            Matcher identifiant = Pattern.compile("^\\| ([A-Z]{3}-\\d{2}) \\|").matcher(ligne);
            if (identifiant.find()) {
                lignes.computeIfAbsent(identifiant.group(1), k -> new ArrayList<>()).add(ligne);
                assertThat(identifiant.group(1)).as("l'identifiant est dans la table de son axe").startsWith(axe);
            }
        }

        assertThat(lignes.entrySet()).as("aucun identifiant en double")
                .allSatisfy(e -> assertThat(e.getValue()).as(e.getKey()).hasSize(1));
        Map<String, String[]> attendus = Map.of(
                "SEC-23", new String[] {"P1", "10.1a"}, "SEC-24", new String[] {"P1", "10.1a"},
                "MET-16", new String[] {"P1", "10.1a"}, "PER-06", new String[] {"P2", "10.1a"},
                "DEP-09", new String[] {"P2", "10.1a"}, "PER-07", new String[] {"P2", "10.2"},
                "FRT-08", new String[] {"P2", "10.1b"});
        attendus.forEach((id, valeurs) -> {
            assertThat(lignes).containsKey(id);
            assertThat(lignes.get(id).get(0)).as(id).matches(
                    "^\\| " + id + " \\|.*\\| " + valeurs[0] + " \\| " + Pattern.quote(valeurs[1]) + " \\|\\s*$");
        });
        assertThat(lignes.get("SEC-24").get(0)).contains("HEAD");
        assertThat(lignes.get("PER-06").get(0)).contains("orphelins");

        for (String id : identifiantsExistants()) {
            assertThat(lignes).as("identifiant existant " + id).containsKey(id);
        }
    }

    private static List<String> identifiantsExistants() {
        List<String> ids = new ArrayList<>();
        ajouter(ids, "SEC", 22);
        ajouter(ids, "ARC", 10);
        ajouter(ids, "MET", 15);
        ajouter(ids, "PER", 5);
        ajouter(ids, "DEP", 8);
        ajouter(ids, "TST", 6);
        ajouter(ids, "FRT", 7);
        return ids;
    }

    private static void ajouter(List<String> ids, String axe, int dernier) {
        for (int i = 1; i <= dernier; i++) {
            ids.add(String.format("%s-%02d", axe, i));
        }
    }
}
