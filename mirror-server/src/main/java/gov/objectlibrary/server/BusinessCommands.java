package gov.objectlibrary.server;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import org.openfoundry.foundation.spi.*;
import org.springframework.stereotype.Service;

/** Business changes, response receipt, audit and outbox share one Foundry transaction. */
@Service
public class BusinessCommands {
    private final StorageProvider storage;
    private final ObjectMapper json;
    public BusinessCommands(StorageProvider storage, ObjectMapper json) {
        this.storage=storage;this.json=json.copy().enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);
    }

    public Map<String,Object> execute(Accounts.Actor actor, String action, String key, Object request,
                                      Runnable authorize, Function<Transaction,Map<String,Object>> body) {
        return executeWithEvent(actor, action, key, request, authorize, body, "mirror." + action);
    }

    public Map<String, Object> executeDefined(Accounts.Actor actor, String action, String key, Object request,
                                              Runnable authorize, Function<Transaction, Map<String, Object>> body,
                                              String eventType) {
        return executeWithEvent(actor, action, key, request, authorize, body, eventType);
    }

    /** Only use for handlers whose body has no non-transactional side effects. Authorization is rechecked on every retry. */
    public Map<String, Object> executeDefinedWithRetry(Accounts.Actor actor, String action, String key, Object request,
                                                       Runnable authorize, Function<Transaction, Map<String, Object>> body,
                                                       String eventType) {
        for (int attempt = 0; ; attempt++) {
            try {
                return executeDefined(actor, action, key, request, authorize, body, eventType);
            } catch (ConcurrentWrite conflict) {
                if (attempt >= 4 || Thread.currentThread().isInterrupted()) throw conflict;
                java.util.concurrent.locks.LockSupport.parkNanos(java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(10L * (attempt + 1)));
            }
        }
    }

    static final class ConcurrentWrite extends BusinessConflict {
        ConcurrentWrite() {
            super("数据已变化，请刷新后重试");
        }
    }

    private Map<String, Object> executeWithEvent(Accounts.Actor actor, String action, String key, Object request,
                                                Runnable authorize, Function<Transaction, Map<String, Object>> body,
                                                String eventType) {
        if (key==null || !key.matches("[a-zA-Z0-9_-]{8,100}")) throw new IllegalArgumentException("Invalid command key");
        authorize.run();
        String receiptId=hash(actor.username()+"/"+key);
        String fingerprint=hash(action+"/"+encode(request));
        String legacyAction = switch (action) {
            case "AddPersonTag" -> "PersonTagADD";
            case "RemovePersonTag" -> "PersonTagREMOVE";
            default -> null;
        };
        String legacyFingerprint = legacyAction == null ? null : hash(legacyAction + "/" + encode(request));
        var previous=receipt(actor,receiptId,fingerprint,legacyFingerprint);
        if(previous!=null) return previous;
        try(var tx=storage.beginTransaction(actor.context())) {
            // Shared per-tenant optimistic guard. A conflicting writer must reload and retry;
            // validation below runs while this transaction owns the guard until commit.
            var guard=storage.getObject(actor.context(),"BusinessWriteLock","business");
            if(guard==null) tx.createObject("BusinessWriteLock","business",Map.of("actorId",actor.username()));
            else tx.updateObject("BusinessWriteLock","business",Map.of("actorId",actor.username()),guard.version());
            authorize.run();
            Map<String,Object> result=body.apply(tx);
            String responseJson=encode(result);
            Map<String,Object> response=decode(responseJson);
            tx.createObject("BusinessCommandReceipt",receiptId,Map.of("action",action,"actorId",actor.username(),
                    "requestHash",fingerprint,"responseJson",responseJson));
            String eventId=UUID.randomUUID().toString();
            var detail=Map.<String,Object>of("action",action,"organizationId",actor.organizationId(),"result",result);
            tx.appendAudit(new AuditEntry("audit-"+eventId,Instant.now(),actor.tenantId(),actor.username(),
                    "business",null,null,action,tx.transactionId(),"success",detail));
            tx.enqueueOutbox(new OutboxEntry("event-"+eventId,actor.tenantId(),eventType,action,
                    Instant.now(),tx.transactionId(),detail));
            tx.commit();return response;
        } catch (IllegalStateException exception) {
            var completed=receipt(actor,receiptId,fingerprint,legacyFingerprint);
            if(completed!=null) return completed;
            // Map genuine optimistic/duplicate conflicts; surface other storage failures as server errors.
            String message=exception.getMessage();
            if(message!=null && (message.contains("version conflict") || message.contains("already exists")))
                throw new ConcurrentWrite();
            for(Throwable cause=exception.getCause();cause!=null;cause=cause.getCause())
                if(cause instanceof java.sql.SQLException sql && java.util.Set.of("23505","40001","40P01").contains(sql.getSQLState()))
                    throw new ConcurrentWrite();
            throw exception;
        }
    }
    private Map<String,Object> receipt(Accounts.Actor actor,String id,String fingerprint,String legacyFingerprint) {
        var record=storage.getObject(actor.context(),"BusinessCommandReceipt",id);
        if(record==null) return null;
        Object storedHash = record.properties().get("requestHash");
        if (!fingerprint.equals(storedHash) && (legacyFingerprint == null || !legacyFingerprint.equals(storedHash))) {
            throw new BusinessConflict("同一请求标识不能用于不同操作");
        }
        return decode((String)record.properties().get("responseJson"));
    }
    private Map<String,Object> decode(String value) {
        try { return json.readValue(value,new TypeReference<Map<String,Object>>(){}); }
        catch(Exception e) {throw new IllegalStateException("Invalid persisted command response",e);}
    }
    private String encode(Object value) {
        try {return json.writeValueAsString(value);} catch(Exception e) {throw new IllegalArgumentException("Invalid command",e);}
    }
    public static String hash(String value) {
        try {return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));}
        catch(Exception e) {throw new IllegalStateException(e);}
    }
}
