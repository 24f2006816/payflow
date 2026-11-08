package com.payflow.merchant;

import com.payflow.account.AccountEntity;
import com.payflow.account.AccountRepository;
import com.payflow.common.NotFoundException;
import com.payflow.config.RedisCacheConfig;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@Service
public class MerchantService {

    private static final SecureRandom RANDOM = new SecureRandom();

    private final MerchantRepository merchants;
    private final AccountRepository accounts;
    private final WebhookUrlValidator urlValidator;

    public MerchantService(MerchantRepository merchants, AccountRepository accounts, WebhookUrlValidator urlValidator) {
        this.merchants = merchants;
        this.accounts = accounts;
        this.urlValidator = urlValidator;
    }

    public record CreatedMerchant(MerchantEntity merchant, List<AccountEntity> accounts) {
    }

    /** Creates the merchant and one MERCHANT_BALANCE account per currency, atomically. */
    @Transactional
    public CreatedMerchant create(String name, String webhookUrl, Set<String> currencies) {
        urlValidator.validate(webhookUrl);
        byte[] secret = new byte[24];
        RANDOM.nextBytes(secret);
        MerchantEntity merchant = merchants.save(
                MerchantEntity.create(name, webhookUrl, "whsec_" + HexFormat.of().formatHex(secret)));
        List<AccountEntity> created = currencies.stream().sorted()
                .map(c -> accounts.save(AccountEntity.open(AccountEntity.OwnerType.MERCHANT, merchant.getId(),
                        AccountEntity.AccountType.MERCHANT_BALANCE, c)))
                .toList();
        return new CreatedMerchant(merchant, created);
    }

    /** Cached in Redis; the cache is fail-open (see RedisCacheConfig). */
    @Cacheable(cacheNames = RedisCacheConfig.MERCHANTS, key = "#merchantId")
    @Transactional(readOnly = true)
    public MerchantView get(UUID merchantId) {
        return merchants.findById(merchantId).map(MerchantView::of)
                .orElseThrow(() -> new NotFoundException("merchant", merchantId));
    }

    @CacheEvict(cacheNames = RedisCacheConfig.MERCHANTS, key = "#merchantId")
    @Transactional
    public MerchantView updateWebhookUrl(UUID merchantId, String url) {
        urlValidator.validate(url);
        MerchantEntity m = merchants.findById(merchantId).orElseThrow(() -> new NotFoundException("merchant", merchantId));
        m.changeWebhookUrl(url);
        return MerchantView.of(m);
    }

    /** Uncached read that includes the secret; used only by the webhook dispatcher. */
    @Transactional(readOnly = true)
    public MerchantEntity getWithSecret(UUID merchantId) {
        return merchants.findById(merchantId).orElseThrow(() -> new NotFoundException("merchant", merchantId));
    }

    @Transactional(readOnly = true)
    public List<AccountEntity> accountsOf(UUID merchantId) {
        return accounts.findByOwnerTypeAndOwnerId(AccountEntity.OwnerType.MERCHANT, merchantId);
    }
}
