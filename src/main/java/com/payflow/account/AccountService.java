package com.payflow.account;

import com.payflow.common.ApiException;
import com.payflow.common.NotFoundException;
import com.payflow.ledger.PostingEntity;
import com.payflow.ledger.PostingRepository;
import com.payflow.security.CurrentClient;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
public class AccountService {

    private final AccountRepository accounts;
    private final PostingRepository postings;

    public AccountService(AccountRepository accounts, PostingRepository postings) {
        this.accounts = accounts;
        this.postings = postings;
    }

    @Transactional
    public AccountEntity openCustomerWallet(UUID customerId, String currency) {
        accounts.findByOwnerTypeAndOwnerIdAndAccountTypeAndCurrency(AccountEntity.OwnerType.CUSTOMER, customerId,
                AccountEntity.AccountType.CUSTOMER_WALLET, currency).ifPresent(existing -> {
            throw ApiException.conflict("account_exists",
                    "customer " + customerId + " already has a " + currency + " wallet: " + existing.getId());
        });
        try {
            return accounts.saveAndFlush(AccountEntity.open(AccountEntity.OwnerType.CUSTOMER, customerId,
                    AccountEntity.AccountType.CUSTOMER_WALLET, currency));
        } catch (DataIntegrityViolationException race) {
            throw ApiException.conflict("account_exists", "wallet was created concurrently");
        }
    }

    @Transactional(readOnly = true)
    public AccountEntity get(UUID accountId, CurrentClient client) {
        AccountEntity account = accounts.findById(accountId).orElseThrow(() -> new NotFoundException("account", accountId));
        authorizeRead(account, client);
        return account;
    }

    /** Keyset pagination: stable under concurrent inserts, O(limit) regardless of page depth. */
    @Transactional(readOnly = true)
    public List<PostingEntity> statement(UUID accountId, Long beforePostingId, int limit, CurrentClient client) {
        get(accountId, client);
        Limit l = Limit.of(Math.min(Math.max(limit, 1), 200));
        return beforePostingId == null
                ? postings.findByAccountIdOrderByIdDesc(accountId, l)
                : postings.findByAccountIdAndIdLessThanOrderByIdDesc(accountId, beforePostingId, l);
    }

    private static void authorizeRead(AccountEntity account, CurrentClient client) {
        if (client.isPrivileged()) {
            return;
        }
        // Merchants may read only their own merchant balance accounts.
        if (account.getOwnerType() != AccountEntity.OwnerType.MERCHANT) {
            throw new NotFoundException("account", account.getId());
        }
        client.requireAccessToMerchant(account.getOwnerId(), "account", account.getId());
    }
}
