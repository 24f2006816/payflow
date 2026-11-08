package com.payflow.merchant;

import com.payflow.common.AssignedIdEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "merchants")
public class MerchantEntity extends AssignedIdEntity {

    @Id
    private UUID id;

    @Column(nullable = false)
    private String name;

    @Column(name = "webhook_url")
    private String webhookUrl;

    /** Shared HMAC secret for webhook signatures. Never cached, never returned after creation. */
    @Column(name = "webhook_secret", nullable = false)
    private String webhookSecret;

    @Column(nullable = false)
    private String status;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected MerchantEntity() {
    }

    public static MerchantEntity create(String name, String webhookUrl, String webhookSecret) {
        MerchantEntity m = new MerchantEntity();
        m.id = UUID.randomUUID();
        m.name = name;
        m.webhookUrl = webhookUrl;
        m.webhookSecret = webhookSecret;
        m.status = "ACTIVE";
        return m;
    }

    @PrePersist
    void onCreate() {
        createdAt = updatedAt = Instant.now();
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }

    public void changeWebhookUrl(String url) {
        this.webhookUrl = url;
    }

    @Override
    public UUID getId() { return id; }
    public String getName() { return name; }
    public String getWebhookUrl() { return webhookUrl; }
    public String getWebhookSecret() { return webhookSecret; }
    public String getStatus() { return status; }
    public Instant getCreatedAt() { return createdAt; }
}
