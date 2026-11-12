package com.example.ems.communications;

import java.util.Map;
import java.util.Set;

public record CommunicationNotificationEvent(Set<Long> employeeIds, String type, Map<String, Object> payload) {
}
