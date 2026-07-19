package com.gs.ais.repository;

import com.gs.ais.model.entity.ModelProvider;
import com.gs.ais.model.enums.ProviderType;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ModelProviderRepository extends JpaRepository<ModelProvider, Long> {

    List<ModelProvider> findByType(ProviderType type);

    List<ModelProvider> findByApiProviderIsNull();

    List<ModelProvider> findByApiProviderId(Long apiProviderId);

    Optional<ModelProvider> findByTypeAndIsActiveTrue(ProviderType type);

    List<ModelProvider> findAllByTypeAndIsActiveTrue(ProviderType type);

    Optional<ModelProvider> findByProviderId(String providerId);

    boolean existsByProviderId(String providerId);

    long countByType(ProviderType type);
}
