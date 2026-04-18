package estuadiantes.is.escuealing.edu.co.controller;

import estuadiantes.is.escuealing.edu.co.model.Dto.*;
import estuadiantes.is.escuealing.edu.co.service.AuthService;
import estuadiantes.is.escuealing.edu.co.service.IndexingService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/repo")
public class RepositoryController {

    private final IndexingService indexingService;
    private final AuthService authService;

    public RepositoryController(IndexingService indexingService, AuthService authService) {
        this.indexingService = indexingService;
        this.authService = authService;
    }

    /** POST /api/repo/set — ADMIN only */
    @PostMapping("/set")
    public ResponseEntity<ApiResponse<RepoStatusResponse>> setRepository(
        @RequestBody SetRepoRequest request,
        @RequestHeader(value = "Authorization", required = false) String auth
    ) {
        if (!authService.isAdmin(auth)) {
            return ResponseEntity.status(403)
                .body(ApiResponse.error("Solo el administrador puede configurar el repositorio"));
        }
        if (request.repoUrl() == null || request.repoUrl().isBlank()) {
            return ResponseEntity.badRequest().body(ApiResponse.error("repoUrl es requerido"));
        }
        String status = indexingService.getRepoStatus().status();
        if ("CLONING".equals(status) || "INDEXING".equals(status)) {
            return ResponseEntity.status(409)
                .body(ApiResponse.error("Indexación en progreso: " + status));
        }
        indexingService.startRepoIndexing(request.repoUrl(), request.localPath());
        return ResponseEntity.accepted()
            .body(ApiResponse.ok("Indexación iniciada", indexingService.getRepoStatus()));
    }

    /** GET /api/repo/status — any authenticated user */
    @GetMapping("/status")
    public ResponseEntity<ApiResponse<RepoStatusResponse>> getStatus(
        @RequestHeader(value = "Authorization", required = false) String auth
    ) {
        if (!authService.isAuthenticated(auth)) {
            return ResponseEntity.status(401).body(ApiResponse.error("No autenticado"));
        }
        return ResponseEntity.ok(ApiResponse.ok(indexingService.getRepoStatus()));
    }
}
