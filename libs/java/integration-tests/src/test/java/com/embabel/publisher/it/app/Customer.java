package com.embabel.publisher.it.app;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.ManyToOne;

import java.math.BigDecimal;
import java.time.LocalDate;

@Entity
public class Customer {

    @Id
    private Long id;

    private String name;

    private String city;

    @Enumerated(EnumType.STRING)
    private Tier tier;

    @Column(precision = 12, scale = 2)
    private BigDecimal balance;

    private LocalDate since;

    private Long externalRef;

    @ManyToOne
    private Region region;

    public Long getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getCity() {
        return city;
    }

    public Tier getTier() {
        return tier;
    }

    public BigDecimal getBalance() {
        return balance;
    }

    public void setBalance(BigDecimal balance) {
        this.balance = balance;
    }

    public LocalDate getSince() {
        return since;
    }

    public Long getExternalRef() {
        return externalRef;
    }

    public Region getRegion() {
        return region;
    }
}
