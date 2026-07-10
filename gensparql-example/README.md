# GenSPARQL Examples

This directory contains example data and queries for GenSPARQL.

## Directory Structure

```
gensparql-example/
├── data/
│   └── scientists_awards.ttl              # RDF data in Turtle format
├── queries/
│   ├── query1_list_scientists_awards.sparql    # Basic SPARQL query
│   ├── query2_generate_descriptions.sparql     # GENOP: Generate descriptions
│   ├── query3_translate_awards.sparql         # GENOP: Translate awards
│   └── query4_explain_categories.sparql      # GENOP: Explain categories
├── ScientistAwardExample.java   # Java example code
└── README.md                     # This file
```

## Data Files

### scientists_awards.ttl

Contains information about scientists and their awards in RDF/Turtle format:

- **Albert Einstein** - Nobel Prize in Physics (1921)
- **Alan Turing** - Order of the British Empire (1945)
- **Marie Curie** - Nobel Prize in Chemistry (1911)

The data uses the following vocabulary:
- `foaf:Person` - Person type
- `foaf:name` - Person's name
- `ex:receivedAward` - Award received by person
- `ex:Award` - Award type
- `rdfs:label` - Award name
- `ex:year` - Award year
- `ex:category` - Award category

## Usage

### Loading Data

Load the TTL file into a Jena Model:

```java
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.util.FileManager;

Model model = ModelFactory.createDefaultModel();
FileManager.get().readModel(model, "data/scientists_awards.ttl");
```

### Running Examples

Compile and run the example:

```bash
cd gensparql-example
javac -cp "path/to/jena/*:path/to/gensparql/*" ScientistAwardExample.java
java -cp ".:path/to/jena/*:path/to/gensparql/*" org.gensparql.example.ScientistAwardExample
```

### Example Queries

Each query is in a separate file in the `queries/` directory:

1. **query1_list_scientists_awards.sparql** - Basic SPARQL query to list all scientists and their awards (no GENOP)
2. **query2_generate_descriptions.sparql** - Use GENOP to generate descriptions about scientists and their awards
3. **query3_translate_awards.sparql** - Use GENOP to translate award names to different languages
4. **query4_explain_categories.sparql** - Use GENOP to generate explanations about award categories

