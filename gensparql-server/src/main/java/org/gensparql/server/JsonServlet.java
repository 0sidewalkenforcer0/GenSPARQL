package org.gensparql.server;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.util.Arrays;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

abstract class JsonServlet extends HttpServlet {
    protected static final ObjectMapper JSON = new ObjectMapper();

    // A page that this machine serves may call the service from any port.
    private static final Pattern LOOPBACK_ORIGIN =
            Pattern.compile("http://(localhost|127\\.0\\.0\\.1|\\[::1\\])(:\\d+)?");

    // A page served from elsewhere may call the service only if its origin is listed: the
    // project page, and the origins that -Dgensparql.server.allowedOrigins names, separated by
    // commas.
    static final Set<String> ALLOWED_ORIGINS = Stream.concat(
                    Stream.of("https://0sidewalkenforcer0.github.io"),
                    Arrays.stream(System.getProperty("gensparql.server.allowedOrigins", "").split(",")))
            .map(String::trim)
            .map(origin -> origin.endsWith("/") ? origin.substring(0, origin.length() - 1) : origin)
            .filter(origin -> !origin.isEmpty())
            .collect(Collectors.toUnmodifiableSet());

    @Override
    protected void service(HttpServletRequest request, HttpServletResponse response)
            throws ServletException, IOException {
        // A browser names the origin of the page in every cross-origin request and in every
        // POST; other clients name none.
        String origin = request.getHeader("Origin");
        if (origin != null) {
            response.addHeader("Vary", "Origin");
            if (!allowed(origin, request)) {
                // A cross-origin POST with a plain-text body needs no preflight, so the browser
                // would deliver it even though the page cannot read the answer.
                json(response, HttpServletResponse.SC_FORBIDDEN, Map.of("error",
                        "Pages from " + origin + " may not use this service; see -Dgensparql.server.allowedOrigins"));
                return;
            }
            response.setHeader("Access-Control-Allow-Origin", origin);
            if ("OPTIONS".equals(request.getMethod()) && request.getHeader("Access-Control-Request-Method") != null) {
                response.setHeader("Access-Control-Allow-Methods", "GET, POST");
                response.setHeader("Access-Control-Allow-Headers", "Content-Type, Accept");
                // Chrome asks this when a page from the Internet calls a service on this machine.
                if ("true".equals(request.getHeader("Access-Control-Request-Private-Network"))) {
                    response.setHeader("Access-Control-Allow-Private-Network", "true");
                }
                response.setHeader("Access-Control-Max-Age", "600");
                response.setStatus(HttpServletResponse.SC_NO_CONTENT);
                return;
            }
        }
        super.service(request, response);
    }

    private static boolean allowed(String origin, HttpServletRequest request) {
        return origin.equalsIgnoreCase(request.getScheme() + "://" + request.getHeader("Host"))
                || LOOPBACK_ORIGIN.matcher(origin).matches()
                || ALLOWED_ORIGINS.contains(origin);
    }

    protected static void json(HttpServletResponse response, int status, Object body) throws IOException {
        response.setStatus(status);
        response.setCharacterEncoding("UTF-8");
        response.setContentType("application/json");
        JSON.writeValue(response.getOutputStream(), body);
    }

    protected static void error(HttpServletResponse response, int status, Exception error) throws IOException {
        String message = error.getMessage();
        json(response, status, Map.of("error", message == null ? error.getClass().getSimpleName() : message));
    }
}
