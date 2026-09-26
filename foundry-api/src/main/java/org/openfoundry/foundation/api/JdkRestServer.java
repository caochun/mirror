package org.openfoundry.foundation.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.openfoundry.foundation.security.SecurityPrincipal;
import org.openfoundry.foundation.spi.QueryOptions;
import org.openfoundry.foundation.spi.RequestContext;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.function.Supplier;

/** Minimal JDK HTTP adapter for the framework-neutral REST router. */
public final class JdkRestServer implements AutoCloseable {
    private final HttpServer server;
    private final RestApiRouter router;
    private final Supplier<ApiRequestContext> requestContext;
    private final ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());

    public JdkRestServer(int port, RestApiRouter router, Supplier<ApiRequestContext> requestContext) throws IOException {
        this.server = HttpServer.create(new InetSocketAddress(port), 0);
        this.router = router;
        this.requestContext = requestContext;
        server.createContext("/api/v1", this::handle);
    }

    public void start() { server.start(); }
    public int port() { return server.getAddress().getPort(); }

    @Override
    public void close() { server.stop(0); }

    private void handle(HttpExchange exchange) throws IOException {
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            write(exchange, ApiResponse.badRequest("only GET is supported by this adapter"));
            return;
        }
        ApiRequestContext context = requestContext.get();
        ApiResponse response = router.get(context.request(), context.principal(), exchange.getRequestURI().getPath(), QueryOptions.defaults());
        write(exchange, response);
    }

    private void write(HttpExchange exchange, ApiResponse response) throws IOException {
        byte[] body = mapper.writeValueAsBytes(response.body());
        exchange.getResponseHeaders().set("content-type", "application/json");
        exchange.sendResponseHeaders(response.status(), body.length);
        exchange.getResponseBody().write(body);
        exchange.close();
    }
}
