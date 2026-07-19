package com.gs.ais.repository;

import com.gs.ais.model.entity.ApiProvider;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ApiProviderRepository extends JpaRepository<ApiProvider, Long> {

    boolean existsByProviderKey(String providerKey);

    Optional<ApiProvider> findByProviderKey(String providerKey);

    List<ApiProvider> findAllByOrderByNameAsc();
}
