package com.payflow.security;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1/auth")
@Tag(name = "Auth")
public class AuthController {

    private final TokenService tokenService;

    public AuthController(TokenService tokenService) {
        this.tokenService = tokenService;
    }

    public record TokenRequest(@JsonProperty("client_id") @NotBlank @Size(max = 100) String clientId,
                               @JsonProperty("client_secret") @NotBlank @Size(max = 200) String clientSecret) {
    }

    public record TokenResponse(@JsonProperty("access_token") String accessToken,
                                @JsonProperty("token_type") String tokenType,
                                @JsonProperty("expires_in") long expiresIn) {
    }

    @PostMapping("/token")
    @SecurityRequirements // public endpoint
    @Operation(summary = "Exchange client credentials for a short-lived JWT")
    public TokenResponse token(@Valid @RequestBody TokenRequest request) {
        var issued = tokenService.issue(request.clientId(), request.clientSecret());
        return new TokenResponse(issued.accessToken(), "Bearer", issued.expiresInSeconds());
    }
}
