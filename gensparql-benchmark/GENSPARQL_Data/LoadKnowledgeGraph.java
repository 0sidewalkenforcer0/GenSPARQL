// Apache Jena Java 示例代码

import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.riot.RDFDataMgr;
import org.apache.jena.query.*;

public class LoadKnowledgeGraph {
    public static void main(String[] args) {
        // 加载 Turtle 文件
        Model model = RDFDataMgr.loadModel("FB15k-237+H/train.ttl");
        
        System.out.println("Loaded " + model.size() + " triples");
        
        // 示例 SPARQL 查询
        String queryString = """
            PREFIX fbe: <http://freebase.com/entity/>
            PREFIX fbr: <http://freebase.com/relation/>
            
            SELECT ?entity ?relation ?target
            WHERE {
                ?entity ?relation ?target .
            }
            LIMIT 10
        """;
        
        Query query = QueryFactory.create(queryString);
        try (QueryExecution qexec = QueryExecutionFactory.create(query, model)) {
            ResultSet results = qexec.execSelect();
            ResultSetFormatter.out(System.out, results, query);
        }
        
        model.close();
    }
}
