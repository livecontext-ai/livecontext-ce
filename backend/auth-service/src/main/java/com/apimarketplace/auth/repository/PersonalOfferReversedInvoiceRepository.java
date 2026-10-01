package com.apimarketplace.auth.repository;

import com.apimarketplace.auth.domain.PersonalOfferReversedInvoice;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PersonalOfferReversedInvoiceRepository extends JpaRepository<PersonalOfferReversedInvoice, String> {
    @Modifying
    @Query(value = "INSERT INTO auth.personal_offer_reversed_invoice(invoice_id, reason) VALUES (:invoiceId, :reason) ON CONFLICT (invoice_id) DO NOTHING", nativeQuery = true)
    int recordIfAbsent(@Param("invoiceId") String invoiceId, @Param("reason") String reason);
}
