package fr.backyard.service.exception;

/**
 * Paramètre d'entrée invalide (mappée 400 en incrément 3).
 */
public class InvalidInputException extends RuntimeException {

    public InvalidInputException(String message) {
        super(message);
    }
}
