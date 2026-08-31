package com.gs.ais.dto.response;

import com.gs.ais.security.ResourceUrlSigner;
import tools.jackson.core.JacksonException;
import tools.jackson.core.JsonGenerator;
import tools.jackson.databind.SerializationContext;
import tools.jackson.databind.ser.std.StdSerializer;

/**
 * Jackson 3 serializer that dynamically appends a short-lived signature to image
 * and attachment URLs when a response is serialized. The persisted URL field stays
 * raw; only the wire representation carries the {@code ?sig=...} credential.
 */
public class SignedUrlSerializer extends StdSerializer<String> {

    public SignedUrlSerializer() {
        super(String.class);
    }

    @Override
    public void serialize(String value, JsonGenerator gen, SerializationContext ctxt)
            throws JacksonException {
        if (value == null) {
            gen.writeNull();
            return;
        }
        gen.writeString(ResourceUrlSigner.signCurrent(value));
    }
}
