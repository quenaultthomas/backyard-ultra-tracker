package fr.backyard.service.exception;

/**
 * Paramètre d'entrée invalide (mappée 400 en incrément 3).
 */
public class InvalidInputException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public InvalidInputException(String message) {
        super(message);
    }
}
