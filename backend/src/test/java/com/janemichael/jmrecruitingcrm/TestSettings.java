package com.janemichael.jmrecruitingcrm;

/** Settings shared by the tests that start the whole app. */
public final class TestSettings {

    /** A fixed key for tests only. Real deployments generate their own with openssl rand -base64 32. */
    public static final String ENCRYPTION_KEY = "app.encryption.key=MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=";

    private TestSettings() {
    }
}
