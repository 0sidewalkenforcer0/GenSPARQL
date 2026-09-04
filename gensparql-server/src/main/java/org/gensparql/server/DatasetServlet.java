package org.gensparql.server;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.apache.commons.fileupload2.core.DiskFileItem;
import org.apache.commons.fileupload2.core.DiskFileItemFactory;
import org.apache.commons.fileupload2.jakarta.servlet6.JakartaServletFileUpload;
import org.apache.jena.query.Dataset;
import org.apache.jena.query.DatasetFactory;
import org.apache.jena.riot.Lang;
import org.apache.jena.riot.RDFDataMgr;
import org.apache.jena.riot.RDFLanguages;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

final class DatasetServlet extends JsonServlet {
    private static final long MAX_FILE_SIZE = 100L * 1024 * 1024;
    private final DatasetRegistry registry;

    DatasetServlet(DatasetRegistry registry) {
        this.registry = registry;
    }

    @Override
    protected void doPost(HttpServletRequest request, HttpServletResponse response) throws IOException {
        Dataset dataset = DatasetFactory.createTxnMem();
        try {
            JakartaServletFileUpload<DiskFileItem, DiskFileItemFactory> upload =
                    new JakartaServletFileUpload<>(DiskFileItemFactory.builder().get());
            upload.setFileCountMax(1);
            upload.setFileSizeMax(MAX_FILE_SIZE);
            upload.setSizeMax(MAX_FILE_SIZE + 1024 * 1024);
            DiskFileItem part = upload.parseRequest(request).stream()
                    .filter(item -> !item.isFormField() && "file".equals(item.getFieldName()))
                    .findFirst()
                    .orElse(null);
            if (part == null || part.getSize() == 0) {
                throw new IllegalArgumentException("multipart field 'file' is required");
            }

            String name = part.getName() == null ? "dataset.ttl" : part.getName();
            Lang lang = RDFLanguages.filenameToLang(name, Lang.TURTLE);
            RDFDataMgr.read(dataset.getDefaultModel(), part.getInputStream(), lang);
            long tripleCount = dataset.getDefaultModel().size();
            String datasetId = registry.put(dataset);

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("datasetId", datasetId);
            result.put("name", name);
            result.put("tripleCount", tripleCount);
            json(response, HttpServletResponse.SC_CREATED, result);
        } catch (IllegalArgumentException e) {
            dataset.close();
            error(response, HttpServletResponse.SC_BAD_REQUEST, e);
        } catch (RuntimeException e) {
            dataset.close();
            error(response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR, e);
        } catch (Exception e) {
            dataset.close();
            error(response, HttpServletResponse.SC_BAD_REQUEST, e);
        }
    }
}
