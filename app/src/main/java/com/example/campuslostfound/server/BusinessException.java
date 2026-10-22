package com.example.campuslostfound.server;

public final class BusinessException extends Exception {
    public final int status;
    public BusinessException(int status, String message) { super(message); this.status = status; }
}
