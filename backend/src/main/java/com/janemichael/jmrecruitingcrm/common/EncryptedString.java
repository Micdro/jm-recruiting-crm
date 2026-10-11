package com.janemichael.jmrecruitingcrm.common;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

/** Stores a text field encrypted. Put {@code @Convert(converter = EncryptedString.class)} on the field. */
@Converter
public class EncryptedString implements AttributeConverter<String, String> {

    @Override
    public String convertToDatabaseColumn(String attribute) {
        return attribute == null ? null : FieldEncryptor.current().encrypt(attribute);
    }

    @Override
    public String convertToEntityAttribute(String dbData) {
        return dbData == null ? null : FieldEncryptor.current().decrypt(dbData);
    }
}
