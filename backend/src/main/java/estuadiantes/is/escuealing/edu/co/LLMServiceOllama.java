package estuadiantes.is.escuealing.edu.co;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.net.http.*;
import java.net.URI;
import java.time.Duration;
import java.util.List;

public class LLMServiceOllama {
  private final String ollamaUrl;
  private final String modelName;
  private final HttpClient http;
  private final ObjectMapper mapper = new ObjectMapper();

  private static final int MAX_CHARS_PER_CHUNK = 1500;
  private static final int MAX_CHUNKS          = 8;

  public LLMServiceOllama(String ollamaUrl, String modelName) {
    this.ollamaUrl = ollamaUrl.endsWith("/")
        ? ollamaUrl.substring(0, ollamaUrl.length() - 1)
        : ollamaUrl;
    this.modelName = modelName;
    this.http = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .build();
  }

  public String ask(String question, List<String> contexts) {
    try {
      // Separar contexto en dos grupos: código del repo vs documentación externa
      StringBuilder repoCtx  = new StringBuilder();
      StringBuilder docCtx   = new StringBuilder();
      int repoCount = 0, docCount = 0;

      for (int i = 0; i < Math.min(MAX_CHUNKS, contexts.size()); i++) {
        String chunk = contexts.get(i);
        if (chunk.length() > MAX_CHARS_PER_CHUNK)
          chunk = chunk.substring(0, MAX_CHARS_PER_CHUNK) + "\n...[truncado]";

        // Los chunks del repo tienen el prefijo "# File:" generado por FileChunker
        // Los docs tienen "# Document:" generado por IndexingService
        boolean isRepo = chunk.contains("# File:") || chunk.contains("# Language:");
        if (isRepo) {
          repoCtx.append("```\n").append(chunk).append("\n```\n\n");
          repoCount++;
        } else {
          docCtx.append("```\n").append(chunk).append("\n```\n\n");
          docCount++;
        }
      }

      // Construir prompt separando claramente las dos fuentes
      StringBuilder userMsg = new StringBuilder();

      if (repoCount > 0) {
        userMsg.append("## CÓDIGO DEL REPOSITORIO (fuente principal)\n\n");
        userMsg.append(repoCtx);
      }
      if (docCount > 0) {
        userMsg.append("## DOCUMENTACIÓN ADICIONAL (fuente secundaria)\n\n");
        userMsg.append(docCtx);
      }

      userMsg.append("---\n");
      userMsg.append("Pregunta: ").append(question);

      String systemMsg =
          "Eres un asistente técnico que analiza proyectos de software.\n\n" +
              "Se te dan dos tipos de contexto:\n" +
              "1. **CÓDIGO DEL REPOSITORIO**: archivos de código fuente reales del proyecto.\n" +
              "2. **DOCUMENTACIÓN ADICIONAL**: páginas web, wikis u otros documentos de referencia.\n\n" +
              "REGLAS ESTRICTAS:\n" +
              "- Cuando la pregunta sea sobre el proyecto, el código o su funcionamiento: " +
              "responde SOLO basándote en el CÓDIGO DEL REPOSITORIO. Ignora la documentación adicional.\n" +
              "- Cuando la pregunta sea sobre conceptos, tecnologías o información general: " +
              "puedes usar la documentación adicional como referencia.\n" +
              "- Si el código del repositorio no es suficiente para responder, di exactamente " +
              "qué encontraste en el código y qué faltaría para responder completamente.\n" +
              "- NUNCA confundas el contenido de la documentación con el código del proyecto.\n" +
              "- NUNCA digas que no puedes acceder a archivos. El código ya está en el mensaje.\n" +
              "- Menciona siempre el archivo (path) cuando hagas referencia a código.\n" +
              "- Responde en español, de forma clara y técnica.";

      System.out.println("model=" + modelName +
          " | repo_chunks=" + repoCount + " | doc_chunks=" + docCount);

      ObjectNode body = mapper.createObjectNode();
      body.put("model", modelName);
      body.put("stream", false);

      var messages = mapper.createArrayNode();

      var sysNode = mapper.createObjectNode();
      sysNode.put("role", "system");
      sysNode.put("content", systemMsg);
      messages.add(sysNode);

      var userNode = mapper.createObjectNode();
      userNode.put("role", "user");
      userNode.put("content", userMsg.toString());
      messages.add(userNode);

      body.set("messages", messages);

      ObjectNode options = mapper.createObjectNode();
      options.put("temperature", 0.2);
      options.put("num_predict", 1024);
      options.put("num_ctx", 8192);
      body.set("options", options);

      HttpRequest req = HttpRequest.newBuilder()
          .uri(URI.create(ollamaUrl + "/api/chat"))
          .header("Content-Type", "application/json")
          .timeout(Duration.ofMinutes(5))
          .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)))
          .build();

      System.out.println("Waiting for model...");
      HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
      System.out.println("Status: " + resp.statusCode());

      if (resp.statusCode() / 100 != 2)
        throw new RuntimeException("LLM fail: " + resp.body());

      JsonNode root = mapper.readTree(resp.body());
      if (root.has("message") && root.get("message").has("content")) {
        String answer = root.get("message").get("content").asText().trim();
        System.out.println("Answer: " + answer.length() + " chars");
        return answer.isEmpty() ? "El modelo no generó respuesta. Intenta reformular." : answer;
      }
      if (root.has("response")) return root.get("response").asText().trim();
      return "Respuesta inesperada del modelo.";

    } catch (HttpTimeoutException e) {
      throw new RuntimeException("Timeout esperando al modelo. Intenta una pregunta más corta.", e);
    } catch (Exception e) {
      throw new RuntimeException("Error calling LLM: " + e.getMessage(), e);
    }
  }
}