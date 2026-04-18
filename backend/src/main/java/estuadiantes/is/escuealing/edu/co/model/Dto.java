package estuadiantes.is.escuealing.edu.co.model;

import java.time.Instant;
import java.util.List;

// ─── Repository ───────────────────────────────────────────────────────────────

public class Dto {

    public record SetRepoRequest(String repoUrl, String localPath) {}

    public record RepoStatusResponse(
        String repoUrl,
        String localPath,
        String status,           // IDLE | CLONING | INDEXING | READY | ERROR
        int totalChunks,
        int indexedChunks,
        String errorMessage
    ) {}

    // ─── Documentation ───────────────────────────────────────────────────────

    public record AddDocLinkRequest(String url, String title) {}

    public record DocSource(
        String id,
        String type,             // URL | FILE
        String title,
        String source,           // URL or filename
        String status,           // PENDING | INDEXING | INDEXED | ERROR
        Instant addedAt
    ) {}

    public record DocListResponse(List<DocSource> sources) {}

    // ─── Chat ────────────────────────────────────────────────────────────────

    public record ChatRequest(String message, int topK) {
        public ChatRequest(String message) { this(message, 10); }
    }

    public record ChatResponse(
        String id,
        String question,
        String answer,
        List<SourceReference> sources,
        Instant timestamp
    ) {}

    public record SourceReference(String path, double score, String snippet) {}

    // ─── Generic ─────────────────────────────────────────────────────────────

    public record ApiResponse<T>(boolean success, String message, T data) {
        public static <T> ApiResponse<T> ok(T data) {
            return new ApiResponse<>(true, "OK", data);
        }
        public static <T> ApiResponse<T> ok(String message, T data) {
            return new ApiResponse<>(true, message, data);
        }
        public static <T> ApiResponse<T> error(String message) {
            return new ApiResponse<>(false, message, null);
        }
    }
}
