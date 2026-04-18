package estuadiantes.is.escuealing.edu.co.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class AuthService {

    @Value("${app.auth.admin-token}")
    private String adminToken;

    @Value("${app.auth.user-token}")
    private String userToken;

    public enum Role { ADMIN, USER, UNAUTHORIZED }

    /**
     * Validates the token from the Authorization header.
     * Expected format: "Bearer <token>"
     */
    public Role resolveRole(String authHeader) {
        if (authHeader == null || !authHeader.startsWith("Bearer ")) return Role.UNAUTHORIZED;
        String token = authHeader.substring(7).trim();
        if (token.equals(adminToken)) return Role.ADMIN;
        if (token.equals(userToken))  return Role.USER;
        return Role.UNAUTHORIZED;
    }

    public boolean isAdmin(String authHeader) {
        return resolveRole(authHeader) == Role.ADMIN;
    }

    public boolean isAuthenticated(String authHeader) {
        Role r = resolveRole(authHeader);
        return r == Role.ADMIN || r == Role.USER;
    }
}
