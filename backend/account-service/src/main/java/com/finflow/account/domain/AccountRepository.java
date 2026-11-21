package com.finflow.account.domain;

import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

/** Aucune requête ne filtre explicitement sur le tenant : la RLS ne renvoie que le sien. */
public interface AccountRepository extends JpaRepository<Account, UUID> {

    Page<Account> findByStatus(Account.Status status, Pageable pageable);
}
