package com.pokemans.app.api;

import io.swagger.v3.core.jackson.ModelResolver;
import io.swagger.v3.core.jackson.TypeNameResolver;
import io.swagger.v3.core.util.Json;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.servers.Server;
import java.util.List;
import java.util.TreeSet;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The API's OpenAPI description, which the web app's types are generated from (architecture review #32,
 * docs/api-types.md). The running app doesn't serve it ({@code springdoc.api-docs.enabled: false});
 * {@code OpenApiSpecTest} writes it to {@code web/openapi.json}.
 */
@Configuration
class OpenApiConfig {

    @Bean
    OpenAPI openApi() {
        return new OpenAPI().info(new Info().title("Rawmark API").version("1")).servers(List.of(new Server().url("/")));
    }

    /**
     * Names a nested type with the classes around it ({@code IntakeControllerCardView}), since several
     * controllers have their own {@code CardView}: by simple name alone, one would silently replace another.
     */
    @Bean
    ModelResolver nestedTypeNames() {
        return new ModelResolver(Json.mapper(), new NestedTypeNames());
    }

    /**
     * Every property is required: Jackson writes every record component, null or not. Nullability isn't
     * in the description; the web app's own types say which fields can be null.
     */
    @Bean
    @SuppressWarnings("unchecked") // swagger's Schema is a raw type in Components
    OpenApiCustomizer everyPropertyIsSent() {
        return api -> {
            if (api.getComponents() == null || api.getComponents().getSchemas() == null) {
                return;
            }
            api.getComponents().getSchemas().values().forEach(schema -> {
                if (schema.getProperties() != null && !schema.getProperties().isEmpty()) {
                    schema.setRequired(List.copyOf(new TreeSet<>(schema.getProperties().keySet())));
                }
            });
        };
    }

    static class NestedTypeNames extends TypeNameResolver {

        @Override
        protected String getNameOfClass(Class<?> cls) {
            StringBuilder name = new StringBuilder(super.getNameOfClass(cls));
            for (Class<?> outer = cls.getEnclosingClass(); outer != null; outer = outer.getEnclosingClass()) {
                name.insert(0, outer.getSimpleName());
            }
            return name.toString();
        }
    }
}
