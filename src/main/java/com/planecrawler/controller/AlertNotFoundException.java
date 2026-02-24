package com.planecrawler.controller;

public class AlertNotFoundException extends RuntimeException {
    public AlertNotFoundException(Long id) {
        super("Alert not found: " + id);
    }
}
