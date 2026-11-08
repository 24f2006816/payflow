package com.payflow.security;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface ApiClientRepository extends JpaRepository<ApiClientEntity, UUID> {

    Optional<ApiClientEntity> findByClientId(String clientId);
}
