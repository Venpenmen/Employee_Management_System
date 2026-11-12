package com.example.ems.communications;

import java.util.Map;

public record CommunicationChangedEvent(Long conversationId, String type, Map<String, Object> payload) {
}
