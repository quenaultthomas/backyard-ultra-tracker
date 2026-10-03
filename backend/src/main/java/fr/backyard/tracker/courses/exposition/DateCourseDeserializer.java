package fr.backyard.tracker.courses.exposition;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.ValueDeserializer;

/**
 * Lecture stricte d'une date de Course : uniquement une chaîne aaaa-mm-jj d'un jour existant.
 * Un nombre, une date-heure ou un jour inexistant (2026-02-30) rendent le corps illisible.
 */
class DateCourseDeserializer extends ValueDeserializer<LocalDate> {

    @Override
    public LocalDate deserialize(JsonParser parser, DeserializationContext contexte) {
        if (parser.currentToken() != JsonToken.VALUE_STRING) {
            return (LocalDate) contexte.handleUnexpectedToken(LocalDate.class, parser);
        }
        try {
            return LocalDate.parse(parser.getString(), DateTimeFormatter.ISO_LOCAL_DATE);
        } catch (DateTimeParseException exception) {
            throw contexte.weirdStringException(parser.getString(), LocalDate.class, "date aaaa-mm-jj attendue");
        }
    }
}
