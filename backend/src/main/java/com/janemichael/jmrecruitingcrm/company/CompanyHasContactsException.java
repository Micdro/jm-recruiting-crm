package com.janemichael.jmrecruitingcrm.company;

public class CompanyHasContactsException extends RuntimeException {

    public CompanyHasContactsException() {
        super("Company has contacts. Delete or reassign them before deleting the company.");
    }
}
