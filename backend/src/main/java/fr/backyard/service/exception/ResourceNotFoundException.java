package fr.backyard.service.exception;

/**
 * Ressource introuvable (mappée 404 en incrément 3).
 */
public class ResourceNotFoundException extends RuntimeException {

    public ResourceNotFoundException(String message) {
        super(message);
    }
}
