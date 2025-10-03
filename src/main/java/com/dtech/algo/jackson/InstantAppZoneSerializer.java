package com.dtech.algo.jackson;

import com.dtech.algo.time.ZoneIdHolder;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.SerializerProvider;

import java.io.IOException;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Serializes Instant as an ISO-8601 string in the application ZoneId instead of UTC.
 */
public class InstantAppZoneSerializer extends JsonSerializer<Instant> {

    @Override
    public void serialize(Instant value, JsonGenerator gen, SerializerProvider serializers) throws IOException {
        if (value == null) {
            gen.writeNull();
            return;
        }
        ZonedDateTime zdt = ZonedDateTime.ofInstant(value, ZoneIdHolder.get());
        gen.writeString(DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(zdt));
    }
}
