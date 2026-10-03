package fr.backyard.tracker.courses.exposition;

import org.springframework.boot.jackson.autoconfigure.JsonMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.cfg.CoercionAction;
import tools.jackson.databind.cfg.CoercionInputShape;
import tools.jackson.databind.type.LogicalType;

/**
 * Lecture stricte des entiers : une décimale (1.5), une chaîne ("12") ou un booléen reçus à la place d'un
 * entier rendent le corps illisible au lieu d'être tronqués ou convertis silencieusement.
 */
@Configuration(proxyBeanMethods = false)
public class CoursesJsonConfiguration {

    @Bean
    JsonMapperBuilderCustomizer entiersStricts() {
        return builder -> builder.withCoercionConfig(LogicalType.Integer, coercion -> coercion
                .setCoercion(CoercionInputShape.Float, CoercionAction.Fail)
                .setCoercion(CoercionInputShape.String, CoercionAction.Fail)
                .setCoercion(CoercionInputShape.EmptyString, CoercionAction.Fail)
                .setCoercion(CoercionInputShape.Boolean, CoercionAction.Fail));
    }
}
