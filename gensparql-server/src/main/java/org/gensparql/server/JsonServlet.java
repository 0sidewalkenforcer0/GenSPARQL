package org.gensparql.server;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.util.Map;

abstract class JsonServlet extends HttpServlet {
    protected static final ObjectMapper JSON = new ObjectMapper();

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
