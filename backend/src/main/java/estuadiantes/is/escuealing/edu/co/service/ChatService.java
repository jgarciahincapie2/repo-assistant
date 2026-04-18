package estuadiantes.is.escuealing.edu.co.service;

import estuadiantes.is.escuealing.edu.co.LLMServiceOllama;
import estuadiantes.is.escuealing.edu.co.QdrantClient;
import estuadiantes.is.escuealing.edu.co.Retriever;
import estuadiantes.is.escuealing.edu.co.model.Dto.*;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.*;

@Service
public class ChatService {

    private static final double MIN_SCORE = 0.40;

    private final Retriever retriever;
    private final LLMServiceOllama llm;
    private final QdrantClient qdrant;

    private final Map<String, List<ChatResponse>> conversationHistory = new LinkedHashMap<>();

    public ChatService(Retriever retriever, LLMServiceOllama llm, QdrantClient qdrant) {
        this.retriever = retriever;
        this.llm = llm;
        this.qdrant = qdrant;
    }

    public ChatResponse chat(String conversationId, String question, int topK) throws Exception {
        if (question == null || question.isBlank())
            throw new IllegalArgumentException("Question cannot be empty");

        String safeConvId = (conversationId != null && !conversationId.isBlank())
            ? conversationId : "default";

        float[] queryVec = retriever.getEmbed().embed(question);

        // 1. Estrategia de búsqueda:
        //    - Primero busca en TODO (repo + docs) sin filtro
        //    - Si la mayoría de resultados son de docs pero la pregunta parece ser de código,
        //      hace una búsqueda adicional filtrando solo REPO y mezcla los resultados
        List<QdrantClient.SearchHit> allHits = qdrant.search(queryVec, topK, null);
        List<QdrantClient.SearchHit> repoHits = qdrant.search(queryVec, topK, "REPO");

        // Contar cuántos de los top hits son del repo
        long repoCount = allHits.stream()
            .filter(h -> h.payload != null && "REPO".equals(h.payload.has("source_type")
                ? h.payload.get("source_type").asText() : ""))
            .count();

        // Si la pregunta parece ser sobre código/repositorio pero los hits son mayormente de docs,
        // usar los hits del repo directamente
        boolean isCodeQuestion = isCodeRelatedQuestion(question);
        List<QdrantClient.SearchHit> hitsToUse;

        if (isCodeQuestion && repoCount < allHits.size() / 2 && !repoHits.isEmpty()) {
            System.out.println("🔀 Code question detected, prioritizing REPO hits");
            // Mezclar: repo hits primero, luego completar con all hits si hacen falta
            hitsToUse = new ArrayList<>(repoHits);
            for (var h : allHits) {
                if (hitsToUse.stream().noneMatch(r -> r.id.equals(h.id))) {
                    hitsToUse.add(h);
                }
                if (hitsToUse.size() >= topK) break;
            }
        } else {
            hitsToUse = allHits;
        }

        // 2. Filtrar por score mínimo y construir contexto
        List<String> contexts = new ArrayList<>();
        List<SourceReference> sources = new ArrayList<>();

        for (var hit : hitsToUse) {
            if (hit.score < MIN_SCORE) continue;
            if (hit.payload == null) continue;

            String path = hit.payload.has("path") ? hit.payload.get("path").asText() : "unknown";
            String srcType = hit.payload.has("source_type") ? hit.payload.get("source_type").asText() : "";

            String text = "";
            if (hit.payload.has("enriched_text") && !hit.payload.get("enriched_text").asText().isBlank()) {
                text = hit.payload.get("enriched_text").asText();
            } else if (hit.payload.has("text")) {
                text = hit.payload.get("text").asText();
            }
            if (text.isBlank()) continue;

            contexts.add(text);
            String snippet = hit.payload.has("text") ? hit.payload.get("text").asText() : text;
            if (snippet.length() > 250) snippet = snippet.substring(0, 250) + "...";
            sources.add(new SourceReference(path + " [" + srcType + "]", hit.score, snippet));
        }

        System.out.println("✅ Final context chunks: " + contexts.size() +
            " | code question: " + isCodeQuestion);

        String answer;
        if (contexts.isEmpty()) {
            answer = "No encontré información relevante para responder esta pregunta. " +
                "Verifica que el repositorio esté indexado (estado READY) e intenta reformular la pregunta.";
        } else {
            answer = llm.ask(question, contexts);
        }

        ChatResponse response = new ChatResponse(
            UUID.randomUUID().toString(), question, answer, sources, Instant.now());

        conversationHistory.computeIfAbsent(safeConvId, k -> new ArrayList<>()).add(response);
        return response;
    }

    /**
     * Detecta si la pregunta es sobre código o el repositorio.
     */
    private boolean isCodeRelatedQuestion(String question) {
        String q = question.toLowerCase();
        return q.contains("clase") || q.contains("class") ||
            q.contains("método") || q.contains("method") ||
            q.contains("función") || q.contains("function") ||
            q.contains("archivo") || q.contains("file") ||
            q.contains("repositorio") || q.contains("repository") ||
            q.contains("código") || q.contains("code") ||
            q.contains(".java") || q.contains(".py") || q.contains(".js") ||
            q.contains("implementa") || q.contains("implements") ||
            q.contains("hereda") || q.contains("extends") ||
            q.contains("paquete") || q.contains("package") ||
            q.contains("import") || q.contains("variable") ||
            q.contains("resumen del repo") || q.contains("que hace el repo") ||
            q.contains("lista") || q.contains("list") ||
            q.contains("qué hace") || q.contains("cómo funciona");
    }

    public List<ChatResponse> getHistory(String conversationId) {
        String safeConvId = (conversationId != null && !conversationId.isBlank())
            ? conversationId : "default";
        return conversationHistory.getOrDefault(safeConvId, List.of());
    }

    public void clearHistory(String conversationId) {
        String safeConvId = (conversationId != null && !conversationId.isBlank())
            ? conversationId : "default";
        conversationHistory.remove(safeConvId);
    }

    public List<String> listConversations() {
        return new ArrayList<>(conversationHistory.keySet());
    }
}
