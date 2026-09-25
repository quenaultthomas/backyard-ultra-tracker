package fr.backyard.service.exception;

/**
 * Action incompatible avec l'état courant (mappée 409 en incrément 3).
 */
public class BusinessConflictException extends RuntimeException {

    public BusinessConflictException(String message) {
        super(message);
    }
}
