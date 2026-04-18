package estuadiantes.is.escuealing.edu.co.service;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.hwpf.HWPFDocument;
import org.apache.poi.hwpf.extractor.WordExtractor;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.safety.Safelist;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.stream.Collectors;

@Service
public class DocumentParserService {

  private final HttpClient http = HttpClient.newBuilder()
      .connectTimeout(Duration.ofSeconds(15))
      .followRedirects(HttpClient.Redirect.NORMAL)
      .build();

  /**
   * Extrae texto limpio de un archivo según su extensión.
   */
  public String extractText(String filename, byte[] content) throws IOException {
    String name = filename.toLowerCase();
    if (name.endsWith(".pdf")) {
      return extractPdf(content);
    } else if (name.endsWith(".docx")) {
      return extractDocx(content);
    } else if (name.endsWith(".doc")) {
      return extractDoc(content);
    } else {
      // TXT, MD, JSON, XML, HTML, etc. — leer como texto plano
      String text = new String(content, java.nio.charset.StandardCharsets.UTF_8);
      // Si es HTML, limpiar tags
      if (name.endsWith(".html") || name.endsWith(".htm")) {
        return cleanHtml(text);
      }
      return text;
    }
  }

  /**
   * Descarga y extrae texto limpio de una URL pública.
   */
  public String fetchAndExtract(String url) throws Exception {
    HttpRequest req = HttpRequest.newBuilder()
        .uri(URI.create(url))
        .header("User-Agent", "Mozilla/5.0 (compatible; RepoAssistant/1.0)")
        .header("Accept", "text/html,application/xhtml+xml,text/plain")
        .timeout(Duration.ofSeconds(20))
        .GET()
        .build();

    HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
    if (resp.statusCode() / 100 != 2) {
      throw new RuntimeException("HTTP " + resp.statusCode() + " al acceder a: " + url);
    }

    String contentType = resp.headers().firstValue("content-type").orElse("");
    String body = resp.body();

    if (contentType.contains("text/html") || body.trim().startsWith("<")) {
      return extractFromHtml(body, url);
    }
    // Texto plano
    return body;
  }

  // ── Parsers privados ─────────────────────────────────────────────────────────

  private String extractPdf(byte[] content) throws IOException {
    try (PDDocument doc = org.apache.pdfbox.Loader.loadPDF(content)) {
      PDFTextStripper stripper = new PDFTextStripper();
      stripper.setSortByPosition(true);
      String text = stripper.getText(doc);
      return cleanWhitespace(text);
    }
  }

  private String extractDocx(byte[] content) throws IOException {
    try (XWPFDocument doc = new XWPFDocument(new ByteArrayInputStream(content))) {
      return doc.getParagraphs().stream()
          .map(XWPFParagraph::getText)
          .filter(t -> t != null && !t.isBlank())
          .collect(Collectors.joining("\n"));
    }
  }

  private String extractDoc(byte[] content) throws IOException {
    try (HWPFDocument doc = new HWPFDocument(new ByteArrayInputStream(content))) {
      WordExtractor extractor = new WordExtractor(doc);
      return cleanWhitespace(extractor.getText());
    }
  }

  private String extractFromHtml(String html, String baseUrl) {
    Document doc = Jsoup.parse(html, baseUrl);

    // Eliminar elementos no útiles
    doc.select("script, style, nav, footer, header, .nav, .menu, .sidebar, " +
        ".advertisement, .cookie-banner, iframe, noscript").remove();

    // Intentar extraer el contenido principal
    String mainContent = "";
    for (String selector : new String[]{"article", "main", ".content", ".post-content",
        ".entry-content", "#content", ".wiki-content",
        ".confluence-information-macro", "body"}) {
      var el = doc.selectFirst(selector);
      if (el != null && el.text().length() > 200) {
        mainContent = el.text();
        break;
      }
    }

    if (mainContent.isBlank()) {
      mainContent = doc.body() != null ? doc.body().text() : doc.text();
    }

    return cleanWhitespace(mainContent);
  }

  private String cleanHtml(String html) {
    return cleanWhitespace(Jsoup.clean(html, Safelist.none()));
  }

  private String cleanWhitespace(String text) {
    if (text == null) return "";
    return text.replaceAll("\\r\\n|\\r", "\n")
        .replaceAll("[ \\t]{2,}", " ")
        .replaceAll("\\n{3,}", "\n\n")
        .trim();
  }
}
