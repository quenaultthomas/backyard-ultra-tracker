package fr.backyard.api.dto;

import java.util.OptionalInt;
import java.util.OptionalLong;

/** Conversion des valeurs optionnelles des vues en champs JSON présents à null (RG3). */
final class NullableValues {

    private NullableValues() {
    }

    static Integer of(OptionalInt value) {
        return value.isPresent() ? value.getAsInt() : null;
    }

    static Long of(OptionalLong value) {
        return value.isPresent() ? value.getAsLong() : null;
    }
}
