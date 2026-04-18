package estuadiantes.is.escuealing.edu.co.controller;

import estuadiantes.is.escuealing.edu.co.model.Dto.*;
import estuadiantes.is.escuealing.edu.co.service.AuthService;
import estuadiantes.is.escuealing.edu.co.service.IndexingService;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

@RestController
@RequestMapping("/api/docs")
public class DocumentationController {

    private final IndexingService indexingService;
    private final AuthService authService;

    public DocumentationController(IndexingService indexingService, AuthService authService) {
        this.indexingService = indexingService;
        this.authService = authService;
    }

    /** GET /api/docs — authenticated users can see the list */
    @GetMapping
    public ResponseEntity<ApiResponse<DocListResponse>> listSources(
        @RequestHeader(value = "Authorization", required = false) String auth
    ) {
        if (!authService.isAuthenticated(auth)) {
            return ResponseEntity.status(401).body(ApiResponse.error("No autenticado"));
        }
        List<DocSource> sources = indexingService.listDocSources();
        return ResponseEntity.ok(ApiResponse.ok(new DocListResponse(sources)));
    }

    /** POST /api/docs/url — ADMIN only */
    @PostMapping("/url")
    public ResponseEntity<ApiResponse<DocSource>> addUrl(
        @RequestBody AddDocLinkRequest request,
        @RequestHeader(value = "Authorization", required = false) String auth
    ) {
        if (!authService.isAdmin(auth)) {
            return ResponseEntity.status(403)
                .body(ApiResponse.error("Solo el administrador puede agregar documentación"));
        }
        if (request.url() == null || request.url().isBlank()) {
            return ResponseEntity.badRequest().body(ApiResponse.error("url es requerido"));
        }
        DocSource doc = indexingService.addDocUrl(request.url(), request.title());
        return ResponseEntity.accepted()
            .body(ApiResponse.ok("URL agregada e indexación iniciada", doc));
    }

    /** POST /api/docs/file — ADMIN only */
    @PostMapping(value = "/file", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ApiResponse<DocSource>> uploadFile(
        @RequestParam("file") MultipartFile file,
        @RequestHeader(value = "Authorization", required = false) String auth
    ) {
        if (!authService.isAdmin(auth)) {
            return ResponseEntity.status(403)
                .body(ApiResponse.error("Solo el administrador puede subir archivos"));
        }
        if (file == null || file.isEmpty()) {
            return ResponseEntity.badRequest().body(ApiResponse.error("Archivo requerido"));
        }
        try {
            DocSource doc = indexingService.addDocFile(file.getOriginalFilename(), file.getBytes());
            return ResponseEntity.accepted()
                .body(ApiResponse.ok("Archivo subido e indexación iniciada", doc));
        } catch (Exception e) {
            return ResponseEntity.internalServerError()
                .body(ApiResponse.error("Error procesando archivo: " + e.getMessage()));
        }
    }

    /** DELETE /api/docs/{id} — ADMIN only */
    @DeleteMapping("/{id}")
    public ResponseEntity<ApiResponse<Void>> removeSource(
        @PathVariable String id,
        @RequestHeader(value = "Authorization", required = false) String auth
    ) {
        if (!authService.isAdmin(auth)) {
            return ResponseEntity.status(403)
                .body(ApiResponse.error("Solo el administrador puede eliminar fuentes"));
        }
        boolean removed = indexingService.removeDocSource(id);
        if (!removed) {
            return ResponseEntity.status(404)
                .body(ApiResponse.error("Fuente no encontrada: " + id));
        }
        return ResponseEntity.ok(ApiResponse.ok("Fuente eliminada", null));
    }
}
