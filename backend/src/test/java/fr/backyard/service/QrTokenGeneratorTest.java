package fr.backyard.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/** Spec increment 3 - RG16, CA26. */
class QrTokenGeneratorTest {

    private static final Pattern UUID_V4 =
        Pattern.compile("^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$");

    @Test
    @DisplayName("CA26 - 1000 tokens : 36 caracteres, UUID v4 canonique minuscule, tous distincts")
    void ca26_tokensAreDistinctCanonicalUuidV4() {
        QrTokenGenerator generator = new QrTokenGenerator();
        Set<String> tokens = new HashSet<>();

        for (int i = 0; i < 1000; i++) {
            String token = generator.generate();
            assertThat(token).hasSize(36).matches(UUID_V4);
            tokens.add(token);
        }

        assertThat(tokens).hasSize(1000);
    }
}
