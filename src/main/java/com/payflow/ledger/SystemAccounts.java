package com.payflow.ledger;

import com.payflow.account.AccountEntity;
import com.payflow.account.AccountRepository;
import com.payflow.common.ApiException;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Resolves platform system accounts (PSP clearing, holds, fees) per currency. They are created by
 * migration V2 and never change, so an in-process map is a safe cache (no Redis round trip needed).
 */
@Component
public class SystemAccounts {

    private final AccountRepository accounts;
    private final Map<String, UUID> cache = new ConcurrentHashMap<>();

    public SystemAccounts(AccountRepository accounts) {
        this.accounts = accounts;
    }

    public UUID pspClearing(String currency) {
        return resolve(AccountEntity.AccountType.PSP_CLEARING, currency);
    }

    public UUID holds(String currency) {
        return resolve(AccountEntity.AccountType.PLATFORM_HOLDS, currency);
    }

    public UUID fees(String currency) {
        return resolve(AccountEntity.AccountType.PLATFORM_FEES, currency);
    }

    private UUID resolve(AccountEntity.AccountType type, String currency) {
        return cache.computeIfAbsent(type + ":" + currency, k -> accounts.findPlatformAccount(type, currency)
                .map(AccountEntity::getId)
                .orElseThrow(() -> ApiException.unprocessable("unsupported_currency",
                        "currency " + currency + " is not supported by the platform")));
    }
}
