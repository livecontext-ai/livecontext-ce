package com.apimarketplace.auth.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import java.io.Serializable;
import java.util.Objects;

@Entity
@Table(name = "personal_offer_matrix")
@IdClass(PersonalOfferMatrix.Key.class)
@Getter @Setter
public class PersonalOfferMatrix {
    @Id @Column(name = "policy_id")
    private Long policyId;
    @Id @Column(name = "plan_code", length = 32)
    private String planCode;
    @Id @Column(name = "monthly_credits")
    private int monthlyCredits;
    @Column(name = "bonus_credits", nullable = false)
    private int bonusCredits;

    public static class Key implements Serializable {
        public Long policyId;
        public String planCode;
        public int monthlyCredits;
        public Key() {}
        public boolean equals(Object other) {
            return other instanceof Key key && Objects.equals(policyId, key.policyId)
                    && Objects.equals(planCode, key.planCode) && monthlyCredits == key.monthlyCredits;
        }
        public int hashCode() { return Objects.hash(policyId, planCode, monthlyCredits); }
    }
}
