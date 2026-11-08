package com.payflow.security;

import com.payflow.common.NotFoundException;
import org.springframework.security.oauth2.jwt.Jwt;

import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * The authenticated API client, extracted from the JWT. Passed explicitly into services so that
 * authorization decisions are visible in method signatures (and easy to unit test).
 */
public record CurrentClient(String clientId, Set<String> roles, UUID merchantId) {

    public static CurrentClient from(Jwt jwt) {
        List<String> roles = jwt.getClaimAsStringList("roles");
        String merchant = jwt.getClaimAsString("merchant_id");
        return new CurrentClient(jwt.getSubject(),
                roles == null ? Set.of() : Set.copyOf(roles),
                merchant == null ? null : UUID.fromString(merchant));
    }

    public boolean isPrivileged() {
        return roles.contains("ADMIN") || roles.contains("SERVICE");
    }

    /**
     * Tenant isolation (OWASP API1: Broken Object Level Authorization). A merchant client may only touch
     * its own resources. We answer 404 rather than 403 so ids of other tenants cannot be probed.
     */
    public void requireAccessToMerchant(UUID resourceMerchantId, String resource, Object id) {
        if (isPrivileged()) {
            return;
        }
        if (merchantId == null || !merchantId.equals(resourceMerchantId)) {
            throw new NotFoundException(resource, id);
        }
    }
}
