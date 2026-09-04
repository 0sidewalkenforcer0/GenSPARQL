package org.gensparql.server;

import org.apache.jena.fuseki.main.FusekiServer;
import org.apache.jena.query.Dataset;
import org.apache.jena.query.DatasetFactory;
import org.apache.jena.sys.JenaSystem;
import org.gensparql.engine.boot.GenSPARQL;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;

public final class GenSPARQLFuseki {
    private static final Logger LOG = LoggerFactory.getLogger(GenSPARQLFuseki.class);

    private GenSPARQLFuseki() {}

    public static void main(String[] args) {
        int port = args.length > 0 ? Integer.parseInt(args[0]) : 8080;
        Path ui = Path.of(System.getProperty("gensparql.ui.dir", "docs")).toAbsolutePath();
        if (!Files.isDirectory(ui)) throw new IllegalArgumentException("UI directory not found: " + ui);

        // Fuseki brings TDB subsystems onto the classpath. Initialize the complete Jena
        // subsystem graph first; entering through ARQ.init() while RIOT is initializing can
        // otherwise create a circular RDF-vocabulary initialization with TDB1.
        JenaSystem.init();
        GenSPARQL.init();
        DatasetRegistry registry = new DatasetRegistry();
        Dataset fusekiDataset = DatasetFactory.createTxnMem();

        FusekiServer server = FusekiServer.create()
                .port(port)
                .numServerThreads(Math.max(4, Runtime.getRuntime().availableProcessors()), 64)
                .staticFileBase(ui.toString())
                .add("/gensparql", fusekiDataset)
                .addServlet("/api/gensparql/health", new HealthServlet())
                .addServlet("/api/gensparql/datasets", new DatasetServlet(registry))
                .addServlet("/api/gensparql/query", new QueryServlet(registry))
                .build();

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            registry.close();
            fusekiDataset.close();
        }, "gensparql-server-shutdown"));

        server.start();
        LOG.info("GenSPARQL UI: http://localhost:{}/", port);
        LOG.info("GenSPARQL API: http://localhost:{}/api/gensparql", port);
        LOG.info("Fuseki dataset: http://localhost:{}/gensparql", port);
        server.join();
    }
}
