package com.janemichael.jmrecruitingcrm.common;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

import java.math.BigDecimal;

/** Stores a money amount (such as a placement fee) encrypted in a text column. */
@Converter
public class EncryptedDecimal implements AttributeConverter<BigDecimal, String> {

    @Override
    public String convertToDatabaseColumn(BigDecimal attribute) {
        return attribute == null ? null : FieldEncryptor.current().encrypt(attribute.toPlainString());
    }

    @Override
    public BigDecimal convertToEntityAttribute(String dbData) {
        if (dbData == null) {
            return null;
        }
        String plaintext = FieldEncryptor.current().decrypt(dbData).trim();
        return plaintext.isEmpty() ? null : new BigDecimal(plaintext);
    }
}
