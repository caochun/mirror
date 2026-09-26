package gov.objectlibrary.core;

/** Adapter boundary for 鹿路通. The business service never depends on its wire protocol. */
public interface LulutongConnector {
    DeliveryResponse send(DeliveryRequest request);

    record DeliveryRequest(String recipientId, String personId, String title, String body) {}

    record DeliveryResponse(boolean success, String externalId, String errorCode) {}
}
