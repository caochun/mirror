package gov.objectlibrary.core;

import java.util.HashMap;
import java.util.Map;

/** Deterministic connector used by business acceptance tests and local development. */
public final class MockLulutongConnector implements LulutongConnector {
    private final Map<String, DeliveryResponse> responses = new HashMap<>();

    public MockLulutongConnector respond(String recipientId, boolean success, String errorCode) {
        responses.put(recipientId, new DeliveryResponse(success,
                success ? "mock-" + recipientId : null, errorCode));
        return this;
    }

    @Override
    public DeliveryResponse send(DeliveryRequest request) {
        return responses.getOrDefault(request.recipientId(),
                new DeliveryResponse(true, "mock-" + request.recipientId(), null));
    }
}
