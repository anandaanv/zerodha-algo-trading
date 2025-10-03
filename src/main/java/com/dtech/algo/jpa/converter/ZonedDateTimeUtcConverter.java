package com.dtech.algo.jpa.converter;

import com.dtech.algo.time.ZoneIdHolder;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

import java.sql.Timestamp;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;

/**
 * JPA converter that persists ZonedDateTime as UTC timestamps,
 * and restores them in the application's configured ZoneId.
 */
@Converter(autoApply = true)
public class ZonedDateTimeUtcConverter implements AttributeConverter<ZonedDateTime, Timestamp> {

    @Override
    public Timestamp convertToDatabaseColumn(ZonedDateTime attribute) {
        if (attribute == null) return null;
        return Timestamp.from(attribute.withZoneSameInstant(ZoneOffset.UTC).toInstant());
        // If your DB column is TIMESTAMP WITH TIME ZONE and your driver supports it well,
        // you can consider OffsetDateTime, but Timestamp is broadly compatible.
    }

    @Override
    public ZonedDateTime convertToEntityAttribute(Timestamp dbData) {
        if (dbData == null) return null;
        return ZonedDateTime.ofInstant(dbData.toInstant(), ZoneIdHolder.get());
    }
}
