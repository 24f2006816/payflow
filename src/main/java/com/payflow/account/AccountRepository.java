package com.payflow.account;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface AccountRepository extends JpaRepository<AccountEntity, UUID> {

    /**
     * Locks all accounts touched by a journal entry with SELECT ... FOR UPDATE, in primary-key order.
     * Every transaction acquires locks in the same (database) order, so two transfers A->B and B->A
     * can never deadlock. The session lock_timeout (application.yml) turns a pile-up into a fast, retryable 409 instead of a hang.
     * Note: the order comes from Postgres' uuid ordering, not Java's UUID.compareTo (which is signed).
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from AccountEntity a where a.id in :ids order by a.id")
    List<AccountEntity> lockAllInOrder(@Param("ids") Collection<UUID> ids);

    Optional<AccountEntity> findByOwnerTypeAndAccountTypeAndCurrency(
            AccountEntity.OwnerType ownerType, AccountEntity.AccountType accountType, String currency);

    default Optional<AccountEntity> findPlatformAccount(AccountEntity.AccountType type, String currency) {
        return findByOwnerTypeAndAccountTypeAndCurrency(AccountEntity.OwnerType.PLATFORM, type, currency);
    }

    Optional<AccountEntity> findByOwnerTypeAndOwnerIdAndAccountTypeAndCurrency(
            AccountEntity.OwnerType ownerType, UUID ownerId, AccountEntity.AccountType accountType, String currency);

    List<AccountEntity> findByOwnerTypeAndOwnerId(AccountEntity.OwnerType ownerType, UUID ownerId);
}
