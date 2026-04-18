package estuadiantes.is.escuealing.edu.co.service;

import estuadiantes.is.escuealing.edu.co.EmbeddingServiceOllama;
import estuadiantes.is.escuealing.edu.co.FileChunker;
import estuadiantes.is.escuealing.edu.co.GitCloner;
import estuadiantes.is.escuealing.edu.co.QdrantClient;
import estuadiantes.is.escuealing.edu.co.model.Dto.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

@Service
public class IndexingService {

    private final EmbeddingServiceOllama embedSvc;
    private final QdrantClient qdrant;
    private final DocumentParserService parser;

    @Value("${app.repo.local-path:./repos}")
    private String baseLocalPath;

    private final AtomicReference<String> repoStatus    = new AtomicReference<>("IDLE");
    private final AtomicReference<String> repoUrl       = new AtomicReference<>("");
    private final AtomicReference<String> repoLocalPath = new AtomicReference<>("");
    private final AtomicInteger totalChunks             = new AtomicInteger(0);
    private final AtomicInteger indexedChunks           = new AtomicInteger(0);
    private final AtomicReference<String> repoError     = new AtomicReference<>("");

    private final Map<String, DocSource> docSources = new ConcurrentHashMap<>();

    public IndexingService(EmbeddingServiceOllama embedSvc, QdrantClient qdrant,
                           DocumentParserService parser) {
        this.embedSvc = embedSvc;
        this.qdrant   = qdrant;
        this.parser   = parser;
    }

    // ── Repository ───────────────────────────────────────────────────────────────

    public RepoStatusResponse getRepoStatus() {
        return new RepoStatusResponse(
            repoUrl.get(), repoLocalPath.get(),
            repoStatus.get(), totalChunks.get(), indexedChunks.get(), repoError.get()
        );
    }

    public void startRepoIndexing(String url, String localPath) {
        String path = (localPath != null && !localPath.isBlank())
            ? localPath
            : baseLocalPath + "/" + sanitizeName(url);

        repoUrl.set(url);
        repoLocalPath.set(path);
        repoStatus.set("CLONING");
        repoError.set("");
        totalChunks.set(0);
        indexedChunks.set(0);

        CompletableFuture.runAsync(() -> {
            try {
                var dir = GitCloner.cloneOrUpdate(url, path).toPath();
                repoStatus.set("INDEXING");

                Set<String> exts = Set.of(".java", ".py", ".js", ".ts", ".tsx", ".jsx",
                    ".md", ".txt", ".json", ".xml", ".html", ".css", ".properties",
                    ".yaml", ".yml", ".go", ".rs", ".kt", ".rb", ".php", ".c", ".cpp", ".h");

                FileChunker chunker = new FileChunker(1500, 150, exts);
                var chunks = chunker.chunkRepo(dir);
                totalChunks.set(chunks.size());
                System.out.println("Total chunks: " + chunks.size());

                int threads = Math.min(10, Runtime.getRuntime().availableProcessors());
                ExecutorService executor = Executors.newFixedThreadPool(threads);

                CompletableFuture<?>[] tasks = chunks.stream()
                    .map(c -> CompletableFuture.supplyAsync(() -> {
                            if (c.text == null || c.text.isBlank()) return null;
                            float[] vec = embedSvc.embed(c.enrichedText);
                            return new AbstractMap.SimpleEntry<>(c, vec);
                        }, executor)
                        .thenCompose(pair -> {
                            if (pair == null) return CompletableFuture.completedFuture(null);
                            FileChunker.Chunk c2 = pair.getKey();
                            float[] vec = pair.getValue();
                            Map<String, Object> payload = new HashMap<>();
                            payload.put("path", c2.path);
                            payload.put("language", c2.language);
                            payload.put("start", c2.start);
                            payload.put("end", c2.end);
                            payload.put("text", c2.text);
                            payload.put("enriched_text", c2.enrichedText);
                            payload.put("source_type", "REPO");
                            return qdrant.upsertPointAsync(c2.id, vec, payload)
                                .thenRun(() -> indexedChunks.incrementAndGet());
                        })
                        .exceptionally(ex -> { System.err.println("Chunk error: " + ex.getMessage()); return null; }))
                    .toArray(CompletableFuture[]::new);

                CompletableFuture.allOf(tasks).join();
                executor.shutdown();
                repoStatus.set("READY");
                System.out.println("✅ Indexing complete: " + indexedChunks.get() + " chunks");

            } catch (Exception e) {
                repoStatus.set("ERROR");
                repoError.set(e.getMessage());
                e.printStackTrace();
            }
        });
    }

    // ── Documentation Sources ─────────────────────────────────────────────────────

    public List<DocSource> listDocSources() {
        return new ArrayList<>(docSources.values());
    }

    public DocSource addDocUrl(String url, String title) {
        String id = UUID.randomUUID().toString();
        String resolvedTitle = (title != null && !title.isBlank()) ? title : url;
        DocSource doc = new DocSource(id, "URL", resolvedTitle, url, "PENDING", Instant.now());
        docSources.put(id, doc);
        CompletableFuture.runAsync(() -> {
            updateDocStatus(id, "INDEXING");
            try {
                System.out.println("🌐 Fetching URL: " + url);
                String content = parser.fetchAndExtract(url);
                System.out.println("📄 Extracted " + content.length() + " chars from URL");
                indexTextContent(id, resolvedTitle, content, "URL");
                updateDocStatus(id, "INDEXED");
                System.out.println("✅ URL indexed: " + url);
            } catch (Exception e) {
                updateDocStatus(id, "ERROR");
                System.err.println("❌ Error indexing URL " + url + ": " + e.getMessage());
            }
        });
        return doc;
    }

    public DocSource addDocFile(String originalFilename, byte[] content) throws IOException {
        String id = UUID.randomUUID().toString();
        String title = (originalFilename != null && !originalFilename.isBlank())
            ? originalFilename : "uploaded-doc-" + id;

        // Guardar en disco
        Path tempDir = Paths.get(baseLocalPath, "docs");
        Files.createDirectories(tempDir);
        Path filePath = tempDir.resolve(id + "_" + title);
        Files.write(filePath, content);

        DocSource doc = new DocSource(id, "FILE", title, filePath.toString(), "PENDING", Instant.now());
        docSources.put(id, doc);

        CompletableFuture.runAsync(() -> {
            updateDocStatus(id, "INDEXING");
            try {
                System.out.println("📂 Parsing file: " + title);
                String text = parser.extractText(title, content);
                System.out.println("📄 Extracted " + text.length() + " chars from file");
                if (text.isBlank()) {
                    throw new RuntimeException("No se pudo extraer texto del archivo: " + title);
                }
                indexTextContent(id, title, text, "FILE");
                updateDocStatus(id, "INDEXED");
                System.out.println("✅ File indexed: " + title);
            } catch (Exception e) {
                updateDocStatus(id, "ERROR");
                System.err.println("❌ Error indexing file " + title + ": " + e.getMessage());
            }
        });
        return doc;
    }

    public boolean removeDocSource(String id) {
        return docSources.remove(id) != null;
    }

    // ── Private ───────────────────────────────────────────────────────────────────

    private void indexTextContent(String sourceId, String title, String content, String sourceType) {
        int chunkSize = 1500, overlap = 150;
        int start = 0, len = content.length();
        int count = 0;
        while (start < len) {
            int end = Math.min(start + chunkSize, len);
            if (end < len) {
                int newline = content.lastIndexOf('\n', end);
                if (newline > start + chunkSize / 2) end = newline;
            }
            String slice = content.substring(start, end).trim();
            if (!slice.isBlank()) {
                try {
                    String enriched = "# Document: " + title + "\n# Type: " + sourceType + "\n\n" + slice;
                    float[] vec = embedSvc.embed(enriched);
                    Map<String, Object> payload = new HashMap<>();
                    payload.put("path", title);
                    payload.put("language", "text");
                    payload.put("start", start);
                    payload.put("end", end);
                    payload.put("text", slice);
                    payload.put("enriched_text", enriched);
                    payload.put("source_type", sourceType);
                    payload.put("source_id", sourceId);
                    qdrant.upsertPointAsync(UUID.randomUUID().toString(), vec, payload).join();
                    count++;
                } catch (Exception e) {
                    System.err.println("Error embedding chunk: " + e.getMessage());
                }
            }
            if (end == len) break;
            start = Math.max(0, end - overlap);
        }
        System.out.println("📦 Indexed " + count + " chunks for: " + title);
    }

    private void updateDocStatus(String id, String status) {
        docSources.computeIfPresent(id, (k, old) ->
            new DocSource(old.id(), old.type(), old.title(), old.source(), status, old.addedAt()));
    }

    private static String sanitizeName(String url) {
        return url.replaceAll("[^a-zA-Z0-9._-]", "_").replaceAll("_+", "_");
    }
}