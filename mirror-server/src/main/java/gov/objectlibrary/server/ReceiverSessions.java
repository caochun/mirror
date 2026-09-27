package gov.objectlibrary.server;

import jakarta.servlet.http.HttpSession;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Explicit Mock identity simulation. A production identity adapter must verify the external recipient. */
@Service
class ReceiverSessions {
    private static final String ATTRIBUTE = ReceiverSessions.class.getName() + ".grants";
    private final SecureRandom random = new SecureRandom();
    private final JdbcTemplate jdbc;
    private final Accounts accounts;
    private final DeliveryService delivery;
    private final Clock clock;

    ReceiverSessions(JdbcTemplate jdbc, Accounts accounts, DeliveryService delivery, Clock clock) {
        this.jdbc = jdbc;
        this.accounts = accounts;
        this.delivery = delivery;
        this.clock = clock;
    }

    String issue(Accounts.Actor issuer, String recipientId, HttpSession session) {
        requireMock();
        String ticket = opaque();
        jdbc.update("""
                INSERT INTO mirror_receiver_tickets
                  (ticket_hash, tenant_id, recipient_id, session_binding, issued_by, expires_at)
                VALUES (?, ?, ?, ?, ?, ?)
                """, BusinessCommands.hash(ticket), issuer.tenantId(), recipientId, binding(session), issuer.username(),
                OffsetDateTime.ofInstant(clock.instant().plusSeconds(120), ZoneOffset.UTC));
        return "/r/reminder#ticket=" + ticket;
    }

    String exchange(String ticket, HttpSession session) {
        requireMock();
        if (ticket == null || !ticket.matches("[A-Za-z0-9_-]{43}")) throw denied();
        String sessionBinding = binding(session);
        var rows = jdbc.query("SELECT * FROM mirror_receiver_tickets WHERE ticket_hash=?", (rs, i) ->
                new Ticket(rs.getString("tenant_id"), rs.getString("recipient_id"), rs.getString("session_binding"),
                        rs.getString("issued_by"), rs.getObject("expires_at", OffsetDateTime.class).toInstant()),
                BusinessCommands.hash(ticket));
        if (rows.size() != 1) throw denied();
        var stored = rows.getFirst();
        if (!stored.binding().equals(sessionBinding) || !stored.expiresAt().isAfter(clock.instant())) throw denied();
        var issuer = accounts.actor(stored.issuer());
        if (!issuer.tenantId().equals(stored.tenant())) throw denied();
        synchronized (session) {
            var grants = grants(session);
            grants.values().removeIf(g -> !g.expiresAt().isAfter(clock.instant()) || !g.binding().equals(sessionBinding));
            if (grants.size() >= 32) throw new BusinessConflict("打开的提醒过多，请关闭会话后重新进入");
            int consumed = jdbc.update("""
                    UPDATE mirror_receiver_tickets SET consumed_at=?
                    WHERE ticket_hash=? AND session_binding=? AND consumed_at IS NULL AND expires_at>?
                    """, OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC), BusinessCommands.hash(ticket),
                    sessionBinding, OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC));
            if (consumed != 1) throw denied();
            String id = opaque();
            grants.put(id, new Grant(id, stored.tenant(), stored.recipient(), sessionBinding, issuer,
                    clock.instant().plusSeconds(900), new ConcurrentHashMap<>()));
            return id;
        }
    }

    Grant require(String id, HttpSession session) {
        requireMock();
        if (id == null || !id.matches("[A-Za-z0-9_-]{43}")) throw denied();
        Grant grant = grants(session).get(id);
        if (grant == null || !grant.binding().equals(binding(session)) || !grant.expiresAt().isAfter(clock.instant())
                || !accounts.actor(grant.issuer().username()).equals(grant.issuer())) throw denied();
        accounts.requirePermission(grant.issuer(), "REMINDER_WRITE");
        return grant;
    }

    String renderProof(Grant grant, String version, String digest) {
        synchronized (grant.proofs()) {
            grant.proofs().values().removeIf(proof -> !proof.expiresAt().isAfter(clock.instant()));
            if (grant.proofs().size() >= 32) throw new BusinessConflict("访问过于频繁，请稍后刷新");
            String token = opaque();
            grant.proofs().put(BusinessCommands.hash(token), new Proof(version, digest, clock.instant().plusSeconds(300)));
            return token;
        }
    }

    void validateProof(Grant grant, String token, String version, String digest) {
        if (token == null || !token.matches("[A-Za-z0-9_-]{43}")) throw denied();
        Proof proof = grant.proofs().get(BusinessCommands.hash(token));
        if (proof == null || !proof.expiresAt().isAfter(clock.instant()) || !proof.version().equals(version)
                || !proof.digest().equals(digest)) throw new BusinessConflict("正文已更新或阅读确认已过期，请重新加载");
    }

    @SuppressWarnings("unchecked")
    private Map<String, Grant> grants(HttpSession session) {
        binding(session);
        synchronized (session) {
            var stored = session.getAttribute(ATTRIBUTE);
            if (stored == null) {
                stored = new ConcurrentHashMap<String, Grant>();
                session.setAttribute(ATTRIBUTE, stored);
            }
            return (Map<String, Grant>) stored;
        }
    }

    private void requireMock() {
        if (!delivery.mockEnabled()) throw denied();
    }

    private String binding(HttpSession session) {
        if (session == null) throw denied();
        return BusinessCommands.hash(session.getId());
    }

    private String opaque() {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static AccessDeniedException denied() {
        return new AccessDeniedException("本人会话无效，请从本人消息重新进入");
    }

    record Grant(String id, String tenant, String recipientId, String binding, Accounts.Actor issuer,
                 Instant expiresAt, Map<String, Proof> proofs) {
        Accounts.Actor actor() {
            return new Accounts.Actor("__receiver__" + BusinessCommands.hash(recipientId), "Mock本人阅读", tenant, "", "RECIPIENT");
        }
    }
    record Proof(String version, String digest, Instant expiresAt) {}
    private record Ticket(String tenant, String recipient, String binding, String issuer, Instant expiresAt) {}
}
