package estuadiantes.is.escuealing.edu.co;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

public class QdrantCollectionCreator {

  private final HttpClient httpClient = HttpClient.newHttpClient();
  private final ObjectMapper mapper = new ObjectMapper();
  private final String qdrantUrl;

  public QdrantCollectionCreator(String qdrantUrl) {
    this.qdrantUrl = qdrantUrl;
  }

  public void createCollection(String collectionName, int vectorSize) throws Exception {
    String url = qdrantUrl + "/collections/" + collectionName;

    // JSON body
    ObjectNode body = mapper.createObjectNode();
    ObjectNode vectorsConfig = mapper.createObjectNode();
    ObjectNode vectorParams = mapper.createObjectNode();

    vectorParams.put("size", vectorSize);
    vectorParams.put("distance", "Cosine");

    vectorsConfig.set("default", vectorParams); // usamos vector space "default"
    body.set("vectors", vectorsConfig);

    HttpRequest request = HttpRequest.newBuilder()
        .uri(URI.create(url))
        .header("Content-Type", "application/json")
        .PUT(HttpRequest.BodyPublishers.ofString(body.toString()))
        .build();

    HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

    if (response.statusCode() == 200) {
      System.out.println("✅ Colección creada exitosamente: " + collectionName);
    } else if (response.statusCode() == 409) {
      System.out.println("ℹ️ La colección ya existe: " + collectionName);
    } else {
      throw new RuntimeException("❌ Error creando colección: " + response.body());
    }
  }

  public static void main(String[] args) throws Exception {
    QdrantCollectionCreator creator = new QdrantCollectionCreator("http://localhost:6333");
    creator.createCollection("repo_embeddings_2", 1024);
  }
}
