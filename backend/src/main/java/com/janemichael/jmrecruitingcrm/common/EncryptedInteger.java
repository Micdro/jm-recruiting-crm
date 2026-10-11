package com.janemichael.jmrecruitingcrm.common;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

/** Stores a number (such as a salary) encrypted in a text column. */
@Converter
public class EncryptedInteger implements AttributeConverter<Integer, String> {

    @Override
    public String convertToDatabaseColumn(Integer attribute) {
        return attribute == null ? null : FieldEncryptor.current().encrypt(attribute.toString());
    }

    @Override
    public Integer convertToEntityAttribute(String dbData) {
        if (dbData == null) {
            return null;
        }
        String plaintext = FieldEncryptor.current().decrypt(dbData).trim();
        return plaintext.isEmpty() ? null : Integer.valueOf(plaintext);
    }
}
