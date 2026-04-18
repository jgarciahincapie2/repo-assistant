package estuadiantes.is.escuealing.edu.co;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.regex.*;

public class FileChunker {
  private final int maxChunkSize;
  private final int chunkOverlap;
  private final Set<String> includeExt;

  private static final Map<String, Pattern> SEMANTIC_SPLITTERS = new LinkedHashMap<>();
  static {
    SEMANTIC_SPLITTERS.put(".java",  Pattern.compile("(?=\\n\\s{0,4}(?:public|private|protected|static|abstract|final|class|interface|enum|@interface)\\s)"));
    SEMANTIC_SPLITTERS.put(".kt",    Pattern.compile("(?=\\n\\s{0,4}(?:fun |class |object |interface |data class|sealed class))"));
    SEMANTIC_SPLITTERS.put(".js",    Pattern.compile("(?=\\n(?:export\\s+)?(?:async\\s+)?function\\s|\\nclass\\s|\\nconst\\s+\\w+\\s*=\\s*(?:async\\s+)?(?:\\([^)]*\\)|\\w+)\\s*=>)"));
    SEMANTIC_SPLITTERS.put(".ts",    Pattern.compile("(?=\\n(?:export\\s+)?(?:async\\s+)?function\\s|\\nclass\\s|\\ninterface\\s|\\ntype\\s+\\w+\\s*=)"));
    SEMANTIC_SPLITTERS.put(".tsx",   SEMANTIC_SPLITTERS.get(".ts"));
    SEMANTIC_SPLITTERS.put(".jsx",   SEMANTIC_SPLITTERS.get(".js"));
    SEMANTIC_SPLITTERS.put(".py",    Pattern.compile("(?=\\n(?:def |class |async def ))"));
    SEMANTIC_SPLITTERS.put(".go",    Pattern.compile("(?=\\nfunc )"));
    SEMANTIC_SPLITTERS.put(".rs",    Pattern.compile("(?=\\n(?:pub\\s+)?(?:fn |struct |impl |enum |trait ))"));
    SEMANTIC_SPLITTERS.put(".c",     Pattern.compile("(?=\\n\\w[^\\n]*\\([^)]*\\)\\s*\\{)"));
    SEMANTIC_SPLITTERS.put(".cpp",   SEMANTIC_SPLITTERS.get(".c"));
    SEMANTIC_SPLITTERS.put(".h",     SEMANTIC_SPLITTERS.get(".c"));
    SEMANTIC_SPLITTERS.put(".rb",    Pattern.compile("(?=\\n\\s*def |\\n\\s*class |\\n\\s*module )"));
    SEMANTIC_SPLITTERS.put(".php",   Pattern.compile("(?=\\n\\s*(?:public|private|protected|static)?\\s*function )"));
  }

  private static final Map<String, String> EXT_TO_LANG = new HashMap<>();
  static {
    EXT_TO_LANG.put(".java","java"); EXT_TO_LANG.put(".kt","kotlin");
    EXT_TO_LANG.put(".js","javascript"); EXT_TO_LANG.put(".ts","typescript");
    EXT_TO_LANG.put(".tsx","typescript"); EXT_TO_LANG.put(".jsx","javascript");
    EXT_TO_LANG.put(".py","python"); EXT_TO_LANG.put(".go","go");
    EXT_TO_LANG.put(".rs","rust"); EXT_TO_LANG.put(".rb","ruby");
    EXT_TO_LANG.put(".php","php"); EXT_TO_LANG.put(".c","c");
    EXT_TO_LANG.put(".cpp","cpp"); EXT_TO_LANG.put(".h","c");
    EXT_TO_LANG.put(".md","markdown"); EXT_TO_LANG.put(".json","json");
    EXT_TO_LANG.put(".xml","xml"); EXT_TO_LANG.put(".yaml","yaml");
    EXT_TO_LANG.put(".yml","yaml"); EXT_TO_LANG.put(".html","html");
    EXT_TO_LANG.put(".css","css"); EXT_TO_LANG.put(".properties","properties");
    EXT_TO_LANG.put(".txt","text");
  }

  public FileChunker(int maxChunkSize, int chunkOverlap, Set<String> includeExt) {
    this.maxChunkSize = maxChunkSize;
    this.chunkOverlap = chunkOverlap;
    this.includeExt = includeExt;
  }

  public List<Chunk> chunkRepo(Path repoDir) throws IOException {
    List<Chunk> chunks = new ArrayList<>();
    Files.walk(repoDir)
        .filter(Files::isRegularFile)
        .filter(p -> !p.toString().contains("/.git/") && !p.toString().contains("\\.git\\"))
        .filter(p -> !p.toString().contains("/node_modules/") && !p.toString().contains("/target/"))
        .filter(p -> !p.toString().contains("/.idea/") && !p.toString().contains("/build/"))
        .forEach(p -> {
          String name = p.getFileName().toString().toLowerCase();
          String ext = getExtension(name);
          boolean ok = includeExt.stream().anyMatch(name::endsWith);
          if (!ok) return;
          try {
            String content = Files.readString(p);
            if (content.isBlank()) return;
            String relPath = repoDir.relativize(p).toString();
            String lang = EXT_TO_LANG.getOrDefault(ext, "text");
            chunks.addAll(chunkFile(content, relPath, lang, ext));
          } catch (IOException e) {
            e.printStackTrace();
          }
        });
    return chunks;
  }

  private List<Chunk> chunkFile(String content, String relPath, String lang, String ext) {
    Pattern splitter = SEMANTIC_SPLITTERS.get(ext);
    if (splitter != null) {
      String[] blocks = splitter.split(content);
      if (blocks.length > 1) return mergeAndChunk(blocks, relPath, lang);
    }
    String[] paragraphs = content.split("\\n{2,}");
    if (paragraphs.length > 1) return mergeAndChunk(paragraphs, relPath, lang);
    return chunkBySize(content, relPath, lang);
  }

  private List<Chunk> mergeAndChunk(String[] blocks, String relPath, String lang) {
    List<Chunk> result = new ArrayList<>();
    StringBuilder current = new StringBuilder();
    int start = 0;
    for (String block : blocks) {
      if (block.isBlank()) continue;
      if (block.length() > maxChunkSize) {
        if (current.length() > 0) {
          result.add(makeChunk(current.toString().trim(), relPath, lang, start));
          current = new StringBuilder();
        }
        result.addAll(chunkBySize(block, relPath, lang));
        start += block.length();
        continue;
      }
      if (current.length() + block.length() > maxChunkSize && current.length() > 0) {
        result.add(makeChunk(current.toString().trim(), relPath, lang, start));
        String overlap = getOverlap(current.toString());
        start += current.length() - overlap.length();
        current = new StringBuilder(overlap);
      }
      current.append(block).append("\n");
    }
    if (current.length() > 0 && !current.toString().isBlank()) {
      result.add(makeChunk(current.toString().trim(), relPath, lang, start));
    }
    return result;
  }

  private List<Chunk> chunkBySize(String text, String relPath, String lang) {
    List<Chunk> res = new ArrayList<>();
    int start = 0, len = text.length();
    while (start < len) {
      int end = Math.min(start + maxChunkSize, len);
      if (end < len) {
        int newline = text.lastIndexOf('\n', end);
        if (newline > start + (maxChunkSize / 2)) end = newline;
      }
      String slice = text.substring(start, end).trim();
      if (!slice.isBlank()) res.add(makeChunk(slice, relPath, lang, start));
      if (end == len) break;
      start = Math.max(0, end - chunkOverlap);
    }
    return res;
  }

  private Chunk makeChunk(String text, String relPath, String lang, int start) {
    String enriched = String.format("# File: %s\n# Language: %s\n\n%s", relPath, lang, text);
    return new Chunk(UUID.randomUUID().toString(), relPath, lang, start, start + text.length(), text, enriched);
  }

  private String getOverlap(String text) {
    if (text.length() <= chunkOverlap) return text;
    String tail = text.substring(text.length() - chunkOverlap);
    int newline = tail.indexOf('\n');
    return newline > 0 ? tail.substring(newline + 1) : tail;
  }

  private static String getExtension(String filename) {
    int dot = filename.lastIndexOf('.');
    return dot >= 0 ? filename.substring(dot) : "";
  }

  public static class Chunk {
    public final String id;
    public final String path;
    public final String language;
    public final int start;
    public final int end;
    public final String text;
    public final String enrichedText;

    public Chunk(String id, String path, String language, int start, int end, String text, String enrichedText) {
      this.id = id; this.path = path; this.language = language;
      this.start = start; this.end = end;
      this.text = text; this.enrichedText = enrichedText;
    }
  }
}
