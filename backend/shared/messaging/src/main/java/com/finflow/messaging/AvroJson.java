package com.finflow.messaging;

import com.finflow.events.EventEnvelope;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import org.apache.avro.io.DecoderFactory;
import org.apache.avro.io.EncoderFactory;
import org.apache.avro.io.JsonEncoder;
import org.apache.avro.specific.SpecificDatumReader;
import org.apache.avro.specific.SpecificDatumWriter;

/**
 * Représentation textuelle d'une enveloppe dans la table outbox. L'événement est donc écrit sans
 * appeler le Schema Registry dans la transaction métier ; la sérialisation Avro binaire se fait
 * au moment de la publication (ADR-013).
 */
public final class AvroJson {

    static {
        AvroTrust.install();
    }

    private AvroJson() {
    }

    public static String write(EventEnvelope envelope) {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            JsonEncoder encoder = EncoderFactory.get().jsonEncoder(EventEnvelope.getClassSchema(), out);
            new SpecificDatumWriter<>(EventEnvelope.class).write(envelope, encoder);
            encoder.flush();
            return out.toString(StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public static EventEnvelope read(String json) {
        try {
            return new SpecificDatumReader<>(EventEnvelope.class)
                    .read(null, DecoderFactory.get().jsonDecoder(EventEnvelope.getClassSchema(), json));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
