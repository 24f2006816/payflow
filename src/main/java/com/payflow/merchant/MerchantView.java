package com.payflow.merchant;

import java.util.UUID;

/** Cacheable, secret-free projection of a merchant. */
public record MerchantView(UUID id, String name, String webhookUrl, String status) {

    public boolean isActive() {
        return "ACTIVE".equals(status);
    }

    static MerchantView of(MerchantEntity m) {
        return new MerchantView(m.getId(), m.getName(), m.getWebhookUrl(), m.getStatus());
    }
}
