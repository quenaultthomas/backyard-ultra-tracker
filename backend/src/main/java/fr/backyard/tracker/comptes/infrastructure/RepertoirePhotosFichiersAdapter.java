package fr.backyard.tracker.comptes.infrastructure;

import fr.backyard.tracker.comptes.domaine.FichierPhoto;
import fr.backyard.tracker.comptes.domaine.RepertoirePhotos;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.DirectoryIteratorException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Entrées directes du répertoire des photos (BACKYARD_PHOTOS_REPERTOIRE) pour le nettoyage des orphelins (correctif
 * audit jalon 10 RG4, RG6) : attributs lus sans suivre les liens, aucune descente dans les sous-répertoires, répertoire
 * jamais créé. Les exceptions levées ne citent ni chemin ni nom de fichier dans leur message.
 */
@Component
public class RepertoirePhotosFichiersAdapter implements RepertoirePhotos {

    private final Path repertoire;

    public RepertoirePhotosFichiersAdapter(@Value("${backyard.photos.repertoire}") String repertoire) {
        this.repertoire = Path.of(repertoire).toAbsolutePath().normalize();
    }

    @Override
    public Optional<List<FichierPhoto>> lister() {
        if (Files.notExists(repertoire, LinkOption.NOFOLLOW_LINKS)) {
            return Optional.empty();
        }
        List<FichierPhoto> fichiers = new ArrayList<>();
        try (DirectoryStream<Path> entrees = Files.newDirectoryStream(repertoire)) {
            for (Path entree : entrees) {
                decrire(entree).ifPresent(fichiers::add);
            }
        } catch (IOException echec) {
            throw new UncheckedIOException("Lecture du répertoire des photos impossible.", echec);
        } catch (DirectoryIteratorException echec) {
            throw new UncheckedIOException("Lecture du répertoire des photos impossible.", echec.getCause());
        }
        return Optional.of(fichiers);
    }

    @Override
    public void supprimer(String nom) {
        Path chemin = repertoire.resolve(nom).normalize();
        if (!repertoire.equals(chemin.getParent()) || !chemin.getFileName().toString().equals(nom)) {
            throw new IllegalArgumentException("Nom de fichier de photo invalide.");
        }
        try {
            Files.delete(chemin);
        } catch (IOException echec) {
            throw new UncheckedIOException("Suppression d'un fichier de photo impossible.", echec);
        }
    }

    /** Vide si l'entrée a disparu entre le listage et la lecture de ses attributs. */
    private static Optional<FichierPhoto> decrire(Path entree) throws IOException {
        try {
            BasicFileAttributes attributs = Files.readAttributes(entree, BasicFileAttributes.class,
                    LinkOption.NOFOLLOW_LINKS);
            return Optional.of(new FichierPhoto(entree.getFileName().toString(),
                    attributs.lastModifiedTime().toInstant(), attributs.isRegularFile()));
        } catch (NoSuchFileException disparue) {
            return Optional.empty();
        }
    }
}
