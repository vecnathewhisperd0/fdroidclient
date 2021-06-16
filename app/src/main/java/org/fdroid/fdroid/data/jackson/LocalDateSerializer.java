package org.fdroid.fdroid.data.jackson;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.SerializerProvider;

import java.io.IOException;
import java.time.LocalDate;
import java.time.ZoneOffset;

public class LocalDateSerializer extends JsonSerializer<LocalDate> {
    @Override
    public void serialize(LocalDate value, JsonGenerator gen, SerializerProvider serializers) throws IOException {
        gen.writeNumber(value.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli());
    }
}
