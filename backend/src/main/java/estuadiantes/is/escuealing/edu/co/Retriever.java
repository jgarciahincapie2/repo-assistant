package estuadiantes.is.escuealing.edu.co;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.*;

public class Retriever {
  private final EmbeddingServiceOllama embed;
  private final QdrantClient qdrant;

  public Retriever(EmbeddingServiceOllama embed, QdrantClient qdrant) {
    this.embed = embed;
    this.qdrant = qdrant;
  }

  /** Expose embed service so other services can use it directly */
  public EmbeddingServiceOllama getEmbed() {
    return embed;
  }

  public List<String> retrieve(String question, int k) throws Exception {
    float[] qvec = embed.embed(question);
    List<QdrantClient.SearchHit> hits = qdrant.search(qvec, k);

    System.out.println("Resultados de búsqueda: " + hits.size());
    List<String> contexts = new ArrayList<>();

    for (var h : hits) {
      System.out.println("➡ " + h.id + " | score=" + h.score);
      if (h.payload != null) {
        if (h.payload.has("text") && !h.payload.get("text").asText().isBlank()) {
          contexts.add(h.payload.get("text").asText());
        }
      }
    }

    System.out.println("Contextos útiles: " + contexts.size());
    return contexts;
  }
}
