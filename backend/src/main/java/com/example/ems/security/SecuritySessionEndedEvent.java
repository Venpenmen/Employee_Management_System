package com.example.ems.security;

public record SecuritySessionEndedEvent(Long employeeId, String tokenHash) {
}
