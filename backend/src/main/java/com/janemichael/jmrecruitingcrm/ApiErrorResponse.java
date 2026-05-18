package com.janemichael.jmrecruitingcrm;

import java.util.List;

public class ApiErrorResponse {

    private int status;
    private String error;
    private List<String> messages;

    public ApiErrorResponse(int status, String error, List<String> messages) {
        this.status = status;
        this.error = error;
        this.messages = messages;
    }

    public int getStatus() {
        return status;
    }

    public String getError() {
        return error;
    }

    public List<String> getMessages() {
        return messages;
    }
}
