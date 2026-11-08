package com.payflow.security;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.util.Arrays;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Entity
@Table(name = "api_clients")
public class ApiClientEntity {

    @Id
    private UUID id;

    @Column(name = "client_id", nullable = false, unique = true)
    private String clientId;

    @Column(name = "client_secret_hash", nullable = false)
    private String clientSecretHash;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false)
    private String roles;

    @Column(name = "merchant_id")
    private UUID merchantId;

    @Column(nullable = false)
    private String status;

    protected ApiClientEntity() {
    }

    public Set<String> roleSet() {
        return Arrays.stream(roles.split(",")).map(String::trim).filter(r -> !r.isEmpty())
                .collect(Collectors.toUnmodifiableSet());
    }

    public boolean isActive() {
        return "ACTIVE".equals(status);
    }

    public UUID getId() { return id; }
    public String getClientId() { return clientId; }
    public String getClientSecretHash() { return clientSecretHash; }
    public String getName() { return name; }
    public UUID getMerchantId() { return merchantId; }
}
