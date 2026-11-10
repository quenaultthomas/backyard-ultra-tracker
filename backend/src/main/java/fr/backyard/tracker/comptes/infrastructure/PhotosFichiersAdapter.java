package fr.backyard.tracker.comptes.infrastructure;

import fr.backyard.tracker.comptes.domaine.JetonPhoto;
import fr.backyard.tracker.comptes.domaine.PolitiqueNettoyagePhotos;
import fr.backyard.tracker.comptes.domaine.StockagePhotos;
import fr.backyard.tracker.partage.domaine.FormatImage;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Stockage des photos de profil dans un répertoire du volume {@code photos} (BACKYARD_PHOTOS_REPERTOIRE). Le nom de
 * fichier est toujours {@code <jeton>.<extension>}, jeton et extension issus du serveur : aucune saisie ne forme un
 * chemin. Écriture dans {@code <nom>.tmp} puis déplacement atomique ; répertoire 700 et fichiers 600 (POSIX). Les
 * exceptions levées ne citent ni chemin ni jeton.
 */
@Component
public class PhotosFichiersAdapter implements StockagePhotos {

    /** Convention partagée avec le nettoyage des orphelins, qui rattrape les temporaires abandonnés. */
    private static final String SUFFIXE_TEMPORAIRE = PolitiqueNettoyagePhotos.SUFFIXE_TEMPORAIRE;
    private static final boolean POSIX = FileSystems.getDefault().supportedFileAttributeViews().contains("posix");

    private final Path repertoire;

    public PhotosFichiersAdapter(@Value("${backyard.photos.repertoire}") String repertoire) {
        this.repertoire = Path.of(repertoire).toAbsolutePath().normalize();
    }

    @Override
    public void enregistrer(String jeton, FormatImage format, byte[] octets) {
        Path definitif = fichier(jeton, format);
        Path temporaire = definitif.resolveSibling(definitif.getFileName() + SUFFIXE_TEMPORAIRE);
        try {
            creerRepertoireSiAbsent();
            Files.createFile(temporaire, droits("rw-------"));
            Files.write(temporaire, octets, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING);
            Files.move(temporaire, definitif, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException echec) {
            effacerTemporaire(temporaire, echec);
            throw new UncheckedIOException("Écriture de la photo impossible.", echec);
        }
    }

    @Override
    public Optional<byte[]> lire(String jeton, FormatImage format) {
        try {
            return Optional.of(Files.readAllBytes(fichier(jeton, format)));
        } catch (NoSuchFileException absent) {
            return Optional.empty();
        } catch (IOException echec) {
            throw new UncheckedIOException("Lecture de la photo impossible.", echec);
        }
    }

    @Override
    public void effacer(String jeton, FormatImage format) {
        try {
            Files.deleteIfExists(fichier(jeton, format));
        } catch (IOException echec) {
            throw new UncheckedIOException("Effacement de la photo impossible.", echec);
        }
    }

    /** Défense en profondeur : le jeton est recontrôlé et le fichier reste un enfant direct du répertoire. */
    private Path fichier(String jeton, FormatImage format) {
        if (!JetonPhoto.estValide(jeton)) {
            throw new IllegalArgumentException("Jeton de photo mal formé.");
        }
        Path chemin = repertoire.resolve(jeton + "." + format.extension());
        if (!repertoire.equals(chemin.getParent())) {
            throw new IllegalArgumentException("Nom de fichier de photo invalide.");
        }
        return chemin;
    }

    private void creerRepertoireSiAbsent() throws IOException {
        if (!Files.isDirectory(repertoire)) {
            Files.createDirectories(repertoire, droits("rwx------"));
        }
    }

    /** Un .tmp qui ne peut pas être effacé n'est jamais servi ; son échec est joint à l'erreur d'origine. */
    private static void effacerTemporaire(Path temporaire, IOException origine) {
        try {
            Files.deleteIfExists(temporaire);
        } catch (IOException echec) {
            origine.addSuppressed(echec);
        }
    }

    private static FileAttribute<?>[] droits(String permissions) {
        if (!POSIX) {
            return new FileAttribute<?>[0];
        }
        return new FileAttribute<?>[] {PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString(permissions))};
    }
}
