package estuadiantes.is.escuealing.edu.co;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.net.http.*;
import java.net.URI;
import java.util.*;

public class EmbeddingServiceOllama {
  private final String ollamaUrl; // ex: http://localhost:11434
  private final String modelName; // "nomic-embed-text"
  private final HttpClient http;
  private final ObjectMapper mapper = new ObjectMapper();

  public EmbeddingServiceOllama(String ollamaUrl, String modelName) {
    this.ollamaUrl = ollamaUrl.endsWith("/") ? ollamaUrl.substring(0, ollamaUrl.length()-1) : ollamaUrl;
    this.modelName = modelName;
    this.http = HttpClient.newHttpClient();
  }

  // devuelve embedding como float[]
  public float[] embed(String text) {
    try {
      String url = ollamaUrl + "/api/embeddings";
      ObjectNode body = mapper.createObjectNode();
      body.put("model", modelName);
      body.put("prompt", text);

      HttpRequest req = HttpRequest.newBuilder()
          .uri(URI.create(url))
          .header("Content-Type","application/json")
          .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)))
          .build();

      HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
      if (resp.statusCode() / 100 != 2) {
        throw new RuntimeException("Ollama embeddings error: " + resp.statusCode() + " -> " + resp.body());
      }
      JsonNode root = mapper.readTree(resp.body());
      JsonNode emb = root.get("embedding");
      if (emb == null) throw new RuntimeException("No embedding in Ollama response: " + resp.body());

      float[] vec = new float[emb.size()];
      for (int i = 0; i < emb.size(); i++) vec[i] = (float) emb.get(i).asDouble();
      return vec;
    } catch (Exception e) {
      throw new RuntimeException("Error generating embedding", e);
    }
  }

  // batch (secuencial simple)
  public List<float[]> embedBatch(List<String> texts) {
    List<float[]> out = new ArrayList<>();
    for (String t : texts) out.add(embed(t));
    return out;
  }
}
