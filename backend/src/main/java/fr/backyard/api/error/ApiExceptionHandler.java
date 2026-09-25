package fr.backyard.api.error;

import fr.backyard.service.exception.BusinessConflictException;
import fr.backyard.service.exception.InvalidInputException;
import fr.backyard.service.exception.ResourceNotFoundException;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.ErrorResponse;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.exc.InvalidFormatException;
import tools.jackson.databind.exc.MismatchedInputException;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Traduction unique des exceptions en ProblemDetail (RG5 à RG7 inc. 3). Les 4xx sont journalisées en
 * WARN sans trace, les 5xx en ERROR avec la trace. Aucune exception n'est avalée.
 */
@RestControllerAdvice
public class ApiExceptionHandler {

    private static final Logger LOG = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(ResourceNotFoundException.class)
    public ResponseEntity<ProblemDetail> handleNotFound(ResourceNotFoundException ex, HttpServletRequest request) {
        return clientError(ApiErrorCode.RESOURCE_NOT_FOUND, ex.getMessage(), request);
    }

    @ExceptionHandler(BusinessConflictException.class)
    public ResponseEntity<ProblemDetail> handleConflict(BusinessConflictException ex, HttpServletRequest request) {
        return clientError(ApiErrorCode.BUSINESS_CONFLICT, ex.getMessage(), request);
    }

    @ExceptionHandler(InvalidInputException.class)
    public ResponseEntity<ProblemDetail> handleInvalidInput(InvalidInputException ex, HttpServletRequest request) {
        return clientError(ApiErrorCode.INVALID_INPUT, ex.getMessage(), request);
    }

    /** RG8 : une entrée {@code {field, message}} par violation de Bean Validation. */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ProblemDetail> handleValidation(MethodArgumentNotValidException ex,
                                                          HttpServletRequest request) {
        List<Map<String, String>> errors = ex.getBindingResult().getFieldErrors().stream()
            .map(ApiExceptionHandler::toFieldError)
            .toList();
        String detail = "Requête invalide : " + errors.size() + " champ(s) en erreur ("
            + errors.stream().map(error -> error.get("field")).distinct().collect(Collectors.joining(", ")) + ")";
        ProblemDetail problem = ProblemDetailFactory.create(ApiErrorCode.VALIDATION_FAILED, detail,
            request.getRequestURI());
        problem.setProperty(ProblemDetailFactory.ERRORS_PROPERTY, errors);
        return respond(problem, request, null);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ProblemDetail> handleNotReadable(HttpMessageNotReadableException ex,
                                                           HttpServletRequest request) {
        return clientError(ApiErrorCode.MALFORMED_REQUEST, describeUnreadableBody(ex), request);
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ProblemDetail> handleTypeMismatch(MethodArgumentTypeMismatchException ex,
                                                            HttpServletRequest request) {
        String detail = "Paramètre '" + ex.getName() + "' invalide : valeur « " + ex.getValue() + " »";
        return clientError(ApiErrorCode.MALFORMED_REQUEST, detail, request);
    }

    /** Conflit en base non anticipé (ex. deux inscriptions simultanées) : message générique, sans SQL. */
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ProblemDetail> handleDataIntegrity(DataIntegrityViolationException ex,
                                                             HttpServletRequest request) {
        LOG.warn("Violation d'intégrité des données sur {} {} : {}", request.getMethod(), request.getRequestURI(),
            ex.getMostSpecificCause().getClass().getSimpleName());
        return build(ProblemDetailFactory.create(ApiErrorCode.DATA_INTEGRITY,
            "Conflit d'intégrité des données, réessayer", request.getRequestURI()), null);
    }

    @ExceptionHandler({NoResourceFoundException.class, NoHandlerFoundException.class})
    public ResponseEntity<ProblemDetail> handleUnknownPath(Exception ex, HttpServletRequest request) {
        return clientError(ApiErrorCode.RESOURCE_NOT_FOUND,
            "Aucune ressource pour " + request.getMethod() + " " + request.getRequestURI(), request);
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ProblemDetail> handleMethodNotAllowed(HttpRequestMethodNotSupportedException ex,
                                                                HttpServletRequest request) {
        ProblemDetail problem = ProblemDetailFactory.create(ApiErrorCode.METHOD_NOT_ALLOWED,
            "Méthode " + request.getMethod() + " non supportée sur " + request.getRequestURI(),
            request.getRequestURI());
        return respond(problem, request, ex.getHeaders());
    }

    /**
     * RG7 : une IllegalStateException / IllegalArgumentException signale une donnée incohérente ou une
     * erreur de programmation, jamais une erreur du client.
     */
    @ExceptionHandler({IllegalStateException.class, IllegalArgumentException.class})
    public ResponseEntity<ProblemDetail> handleInconsistency(RuntimeException ex, HttpServletRequest request) {
        LOG.error("Incohérence interne sur {} {}", request.getMethod(), request.getRequestURI(), ex);
        ProblemDetail problem = ProblemDetailFactory.create(ApiErrorCode.INTERNAL_INCONSISTENCY, ex.getMessage(),
            request.getRequestURI());
        return respond(problem, request, null);
    }

    /**
     * Toute autre exception. Les autres erreurs client standard de Spring MVC (type de contenu non
     * supporté, paramètre manquant...) sont des requêtes mal formées (400) ; le reste est une erreur
     * interne dont ni le message ni la trace ne sont exposés.
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ProblemDetail> handleUnexpected(Exception ex, HttpServletRequest request) {
        if (ex instanceof ErrorResponse frameworkError && frameworkError.getStatusCode().is4xxClientError()) {
            return clientError(ApiErrorCode.MALFORMED_REQUEST, frameworkError.getBody().getDetail(), request);
        }
        LOG.error("Erreur inattendue sur {} {}", request.getMethod(), request.getRequestURI(), ex);
        ProblemDetail problem = ProblemDetailFactory.create(ApiErrorCode.INTERNAL_ERROR,
            "Erreur interne inattendue, réessayer plus tard", request.getRequestURI());
        return respond(problem, request, null);
    }

    private static ResponseEntity<ProblemDetail> clientError(ApiErrorCode code, String detail,
                                                            HttpServletRequest request) {
        return respond(ProblemDetailFactory.create(code, detail, request.getRequestURI()), request, null);
    }

    private static ResponseEntity<ProblemDetail> respond(ProblemDetail problem, HttpServletRequest request,
                                                         HttpHeaders headers) {
        if (HttpStatus.valueOf(problem.getStatus()).is4xxClientError()) {
            LOG.warn("Requête refusée {} {} : {} {}", request.getMethod(), request.getRequestURI(),
                problem.getStatus(), problem.getDetail());
        }
        return build(problem, headers);
    }

    private static ResponseEntity<ProblemDetail> build(ProblemDetail problem, HttpHeaders headers) {
        ResponseEntity.BodyBuilder builder = ResponseEntity.status(problem.getStatus())
            .contentType(MediaType.APPLICATION_PROBLEM_JSON);
        if (headers != null) {
            builder.headers(headers);
        }
        return builder.body(problem);
    }

    private static Map<String, String> toFieldError(FieldError error) {
        Map<String, String> fieldError = new LinkedHashMap<>();
        fieldError.put("field", error.getField());
        fieldError.put("message", String.valueOf(error.getDefaultMessage()));
        return fieldError;
    }

    /** Description lisible d'un corps illisible, sans nom de classe ni trace technique. */
    private static String describeUnreadableBody(HttpMessageNotReadableException ex) {
        Throwable cause = ex.getCause();
        if (cause instanceof InvalidFormatException invalidFormat) {
            return "Valeur « " + invalidFormat.getValue() + " » invalide pour le champ '"
                + fieldPath(invalidFormat) + "'" + acceptedValues(invalidFormat.getTargetType());
        }
        if (cause instanceof MismatchedInputException mismatchedInput) {
            return "Valeur de type inattendu pour le champ '" + fieldPath(mismatchedInput) + "'";
        }
        if (cause instanceof JacksonException) {
            return "Corps JSON mal formé";
        }
        return "Corps de requête absent ou illisible";
    }

    private static String fieldPath(JacksonException ex) {
        return ex.getPath().stream()
            .map(reference -> reference.getPropertyName() != null
                ? reference.getPropertyName()
                : "[" + reference.getIndex() + "]")
            .collect(Collectors.joining("."));
    }

    private static String acceptedValues(Class<?> targetType) {
        if (targetType == null || !targetType.isEnum()) {
            return "";
        }
        return " (valeurs acceptées : " + Arrays.stream(targetType.getEnumConstants())
            .map(String::valueOf).collect(Collectors.joining(", ")) + ")";
    }
}
