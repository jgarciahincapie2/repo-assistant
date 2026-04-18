package estuadiantes.is.escuealing.edu.co.controller;

import estuadiantes.is.escuealing.edu.co.model.Dto.*;
import estuadiantes.is.escuealing.edu.co.service.AuthService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * Auth Endpoints
 * POST /api/auth/login  — validate token, get role back
 * GET  /api/auth/me     — who am I?
 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthService authService;

    @Value("${app.auth.admin-token}")
    private String adminToken;

    @Value("${app.auth.user-token}")
    private String userToken;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    public record LoginRequest(String token) {}
    public record LoginResponse(String token, String role, String message) {}

    /**
     * POST /api/auth/login
     * Body: { "token": "admin-secret-2024" }
     * Returns role and confirms token is valid.
     */
    @PostMapping("/login")
    public ResponseEntity<ApiResponse<LoginResponse>> login(@RequestBody LoginRequest req) {
        if (req.token() == null || req.token().isBlank()) {
            return ResponseEntity.badRequest()
                .body(ApiResponse.error("Token requerido"));
        }

        AuthService.Role role = authService.resolveRole("Bearer " + req.token());

        if (role == AuthService.Role.UNAUTHORIZED) {
            return ResponseEntity.status(401)
                .body(ApiResponse.error("Token inválido"));
        }

        String roleName = role == AuthService.Role.ADMIN ? "ADMIN" : "USER";
        String msg = role == AuthService.Role.ADMIN
            ? "Bienvenido, administrador"
            : "Bienvenido, usuario";

        return ResponseEntity.ok(ApiResponse.ok(new LoginResponse(req.token(), roleName, msg)));
    }

    /**
     * GET /api/auth/me
     * Header: Authorization: Bearer <token>
     */
    @GetMapping("/me")
    public ResponseEntity<ApiResponse<LoginResponse>> me(
        @RequestHeader(value = "Authorization", required = false) String auth
    ) {
        AuthService.Role role = authService.resolveRole(auth);
        if (role == AuthService.Role.UNAUTHORIZED) {
            return ResponseEntity.status(401).body(ApiResponse.error("No autenticado"));
        }
        String roleName = role == AuthService.Role.ADMIN ? "ADMIN" : "USER";
        return ResponseEntity.ok(ApiResponse.ok(new LoginResponse(null, roleName, "Autenticado")));
    }
}
