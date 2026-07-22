package com.gs.ais.config;

import org.springframework.boot.jackson.autoconfigure.JsonMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.core.JacksonException;
import tools.jackson.core.JsonGenerator;
import tools.jackson.databind.SerializationContext;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.module.SimpleModule;
import tools.jackson.databind.ser.std.StdSerializer;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/**
 * Serializes {@link LocalDateTime} as ISO-8601 UTC instants (with {@code Z}).
 * Stored wall-clock values are interpreted in the JVM default zone, then emitted
 * as absolute UTC so browsers can display the correct local time in any timezone.
 */
@Configuration
public class JacksonConfig {

    @Bean
    public JsonMapperBuilderCustomizer utcLocalDateTimeCustomizer() {
        return builder -> {
            // Jackson 3 defaults to ISO strings — no need to disable timestamps.
            SimpleModule module = new SimpleModule();
            module.addSerializer(LocalDateTime.class, new UtcLocalDateTimeSerializer());
            builder.addModule(module);
        };
    }

    static final class UtcLocalDateTimeSerializer extends StdSerializer<LocalDateTime> {
        UtcLocalDateTimeSerializer() {
            super(LocalDateTime.class);
        }

        @Override
        public void serialize(LocalDateTime value, JsonGenerator gen, SerializationContext ctxt)
                throws JacksonException {
            if (value == null) {
                gen.writeNull();
                return;
            }
            gen.writeString(DateTimeFormatter.ISO_INSTANT.format(
                    value.atZone(ZoneId.systemDefault()).toInstant()));
        }
    }
}
