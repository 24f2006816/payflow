package com.payflow.merchant;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface MerchantRepository extends JpaRepository<MerchantEntity, UUID> {
}
