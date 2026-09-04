package org.gensparql.server;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.apache.jena.query.ARQ;
import org.gensparql.engine.boot.GenSPARQL;

import java.io.IOException;
import java.util.Map;

final class HealthServlet extends JsonServlet {
    @Override
    protected void doGet(HttpServletRequest request, HttpServletResponse response) throws IOException {
        json(response, HttpServletResponse.SC_OK, Map.of(
                "status", "ok",
                "engine", "GenSPARQL",
                "initialized", GenSPARQL.isInitialized(),
                "jenaVersion", packageVersion()
        ));
    }

    private static String packageVersion() {
        String version = ARQ.class.getPackage().getImplementationVersion();
        return version == null ? "5.1.0" : version;
    }
}
