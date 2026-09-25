package com.hytechster.ewallet.merchant;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

public interface MerchantRepository extends JpaRepository<Merchant, Long> {

    Optional<Merchant> findByCode(String code);

    Optional<Merchant> findByUserId(Long userId);

    boolean existsByCode(String code);
}
