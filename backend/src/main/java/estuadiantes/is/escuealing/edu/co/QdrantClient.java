package estuadiantes.is.escuealing.edu.co;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import java.net.http.*;
import java.net.URI;
import java.util.*;
import java.util.concurrent.CompletableFuture;

public class QdrantClient {
  private final String baseUrl;
  private final String collection;
  private final HttpClient http;
  private final ObjectMapper mapper = new ObjectMapper();
  public static final int VECTOR_SIZE = 768;

  public QdrantClient(String baseUrl, String collection) {
    this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length()-1) : baseUrl;
    this.collection = collection;
    this.http = HttpClient.newHttpClient();
  }

  public void createCollectionIfNotExists() throws Exception {
    String url = baseUrl + "/collections/" + collection;
    ObjectNode body = mapper.createObjectNode();
    ObjectNode vectors = mapper.createObjectNode();
    ObjectNode defaultVec = mapper.createObjectNode();
    defaultVec.put("size", VECTOR_SIZE);
    defaultVec.put("distance", "Cosine");
    vectors.set("default", defaultVec);
    body.set("vectors", vectors);

    HttpRequest req = HttpRequest.newBuilder()
        .uri(URI.create(url))
        .header("Content-Type","application/json")
        .PUT(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)))
        .build();

    HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
    if (resp.statusCode() / 100 != 2 && resp.statusCode() != 409) {
      throw new RuntimeException("Create collection error: " + resp.statusCode() + " -> " + resp.body());
    }
  }

  public static String sanitizeText(String text) {
    if (text == null) return "";
    text = text.replaceAll("[^\\x20-\\x7E\\n\\r]", " ");
    text = text.replace("\\", "\\\\")
        .replace("\"", "\\\"")
        .replace("\t", " ")
        .replace("\r", "")
        .replace("\n", "\\n");
    int maxLength = 2000;
    if (text.length() > maxLength) {
      text = text.substring(0, maxLength) + "... [truncated]";
    }
    return text;
  }

  public CompletableFuture<Void> upsertPointAsync(String id, float[] vector, Map<String, Object> payload) {
    try {
      String url = baseUrl + "/collections/" + collection + "/points?wait=false";
      ObjectNode body = mapper.createObjectNode();
      ArrayNode points = mapper.createArrayNode();

      ObjectNode point = mapper.createObjectNode();
      point.put("id", id);

      ObjectNode vectorNode = mapper.createObjectNode();
      ArrayNode vecArray = mapper.createArrayNode();
      for (float v : vector) vecArray.add((double) v);
      vectorNode.set("default", vecArray);
      point.set("vector", vectorNode);

      if (payload.containsKey("text")) {
        String rawText = String.valueOf(payload.get("text"));
        payload.put("text", sanitizeText(rawText));
      }
      if (payload.containsKey("enriched_text")) {
        String rawText = String.valueOf(payload.get("enriched_text"));
        payload.put("enriched_text", sanitizeText(rawText));
      }

      JsonNode payloadNode = mapper.valueToTree(payload);
      point.set("payload", payloadNode);
      points.add(point);
      body.set("points", points);
      body.put("ordering", "weak");

      HttpRequest req = HttpRequest.newBuilder()
          .uri(URI.create(url))
          .header("Content-Type", "application/json")
          .PUT(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)))
          .build();

      return http.sendAsync(req, HttpResponse.BodyHandlers.ofString())
          .thenAccept(resp -> {
            if (resp.statusCode() / 100 != 2) {
              System.err.println("❌ Error inserting point " + id + ": " + resp.statusCode());
            }
          })
          .exceptionally(ex -> {
            System.err.println("⚠️ Error sending point " + id + ": " + ex.getMessage());
            return null;
          });

    } catch (Exception e) {
      CompletableFuture<Void> failed = new CompletableFuture<>();
      failed.completeExceptionally(e);
      return failed;
    }
  }

  /**
   * Búsqueda sin filtro — devuelve todos los tipos de fuente.
   */
  public List<SearchHit> search(float[] vector, int topK) throws Exception {
    return search(vector, topK, null);
  }

  /**
   * Búsqueda con filtro opcional por source_type.
   * sourceType puede ser: "REPO", "URL", "FILE", o null para buscar en todo.
   */
  public List<SearchHit> search(float[] vector, int topK, String sourceType) throws Exception {
    String url = baseUrl + "/collections/" + collection + "/points/search";
    ObjectNode body = mapper.createObjectNode();

    ObjectNode namedVector = mapper.createObjectNode();
    namedVector.put("name", "default");
    ArrayNode vecArray = mapper.createArrayNode();
    for (float v : vector) vecArray.add((double) v);
    namedVector.set("vector", vecArray);
    body.set("vector", namedVector);

    body.put("limit", topK);
    body.put("with_payload", true);

    // Agregar filtro si se especifica source_type
    if (sourceType != null && !sourceType.isBlank()) {
      ObjectNode filter = mapper.createObjectNode();
      ArrayNode must = mapper.createArrayNode();
      ObjectNode condition = mapper.createObjectNode();
      ObjectNode fieldCondition = mapper.createObjectNode();
      ObjectNode matchValue = mapper.createObjectNode();
      matchValue.put("value", sourceType);
      fieldCondition.set("source_type", matchValue);
      condition.set("key", mapper.getNodeFactory().textNode("source_type"));

      // Qdrant filter format: {"must": [{"key": "source_type", "match": {"value": "REPO"}}]}
      ObjectNode mustItem = mapper.createObjectNode();
      mustItem.put("key", "source_type");
      ObjectNode match = mapper.createObjectNode();
      match.put("value", sourceType);
      mustItem.set("match", match);
      must.add(mustItem);
      filter.set("must", must);
      body.set("filter", filter);
    }

    HttpRequest req = HttpRequest.newBuilder()
        .uri(URI.create(url))
        .header("Content-Type", "application/json")
        .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)))
        .build();

    HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
    if (resp.statusCode() / 100 != 2)
      throw new RuntimeException("Search fail: " + resp.body());

    JsonNode root = mapper.readTree(resp.body());
    List<SearchHit> hits = new ArrayList<>();
    for (JsonNode r : root.get("result")) {
      String id = r.get("id").asText();
      double score = r.get("score").asDouble();
      JsonNode payload = r.has("payload") ? r.get("payload") : null;
      hits.add(new SearchHit(id, score, payload));
    }
    System.out.println("🔍 Search results: " + hits.size() + (sourceType != null ? " (filter: " + sourceType + ")" : " (no filter)"));
    return hits;
  }

  public boolean isCollectionPopulated() {
    try {
      String url = baseUrl + "/collections/" + collection;
      HttpRequest req = HttpRequest.newBuilder().uri(URI.create(url)).GET().build();
      HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
      if (resp.statusCode() / 100 != 2) return false;
      JsonNode root = mapper.readTree(resp.body());
      int count = root.path("result").path("points_count").asInt(0);
      return count > 0;
    } catch (Exception e) {
      return false;
    }
  }

  public static class SearchHit {
    public final String id;
    public final double score;
    public final JsonNode payload;
    public SearchHit(String id, double score, JsonNode payload) {
      this.id = id; this.score = score; this.payload = payload;
    }
  }
}
