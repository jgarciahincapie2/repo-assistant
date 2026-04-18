package estuadiantes.is.escuealing.edu.co.controller;

import estuadiantes.is.escuealing.edu.co.model.Dto.*;
import estuadiantes.is.escuealing.edu.co.service.AuthService;
import estuadiantes.is.escuealing.edu.co.service.ChatService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/chat")
public class ChatController {

    private final ChatService chatService;
    private final AuthService authService;

    public ChatController(ChatService chatService, AuthService authService) {
        this.chatService = chatService;
        this.authService = authService;
    }

    /** POST /api/chat — any authenticated user */
    @PostMapping
    public ResponseEntity<ApiResponse<ChatResponse>> chat(
        @RequestBody ChatRequest request,
        @RequestHeader(value = "Authorization", required = false) String auth,
        @RequestHeader(value = "X-Conversation-Id", required = false) String conversationId
    ) {
        if (!authService.isAuthenticated(auth)) {
            return ResponseEntity.status(401).body(ApiResponse.error("No autenticado"));
        }
        if (request.message() == null || request.message().isBlank()) {
            return ResponseEntity.badRequest().body(ApiResponse.error("message es requerido"));
        }
        try {
            int topK = request.topK() > 0 ? request.topK() : 10;
            ChatResponse response = chatService.chat(conversationId, request.message(), topK);
            return ResponseEntity.ok(ApiResponse.ok(response));
        } catch (Exception e) {
            return ResponseEntity.internalServerError()
                .body(ApiResponse.error("Error procesando pregunta: " + e.getMessage()));
        }
    }

    /** GET /api/chat/history/{convId} — any authenticated user */
    @GetMapping("/history/{conversationId}")
    public ResponseEntity<ApiResponse<List<ChatResponse>>> getHistory(
        @PathVariable String conversationId,
        @RequestHeader(value = "Authorization", required = false) String auth
    ) {
        if (!authService.isAuthenticated(auth)) {
            return ResponseEntity.status(401).body(ApiResponse.error("No autenticado"));
        }
        return ResponseEntity.ok(ApiResponse.ok(chatService.getHistory(conversationId)));
    }

    /** DELETE /api/chat/history/{convId} — any authenticated user (clears own session) */
    @DeleteMapping("/history/{conversationId}")
    public ResponseEntity<ApiResponse<Void>> clearHistory(
        @PathVariable String conversationId,
        @RequestHeader(value = "Authorization", required = false) String auth
    ) {
        if (!authService.isAuthenticated(auth)) {
            return ResponseEntity.status(401).body(ApiResponse.error("No autenticado"));
        }
        chatService.clearHistory(conversationId);
        return ResponseEntity.ok(ApiResponse.ok("Conversación borrada", null));
    }

    /** GET /api/chat/conversations — ADMIN only */
    @GetMapping("/conversations")
    public ResponseEntity<ApiResponse<List<String>>> listConversations(
        @RequestHeader(value = "Authorization", required = false) String auth
    ) {
        if (!authService.isAdmin(auth)) {
            return ResponseEntity.status(403).body(ApiResponse.error("Solo el administrador puede ver todas las conversaciones"));
        }
        return ResponseEntity.ok(ApiResponse.ok(chatService.listConversations()));
    }
}
