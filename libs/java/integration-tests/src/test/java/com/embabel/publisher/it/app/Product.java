package com.embabel.publisher.it.app;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;

import java.math.BigDecimal;
import java.time.LocalDate;

@Entity
public class Product {

    @Id
    private Long id;

    @Column(nullable = false)
    private String sku;

    private String name;

    @Enumerated(EnumType.STRING)
    private Category category;

    @Column(precision = 12, scale = 2)
    private BigDecimal price;

    private LocalDate launched;

    public Long getId() {
        return id;
    }

    public String getSku() {
        return sku;
    }

    public String getName() {
        return name;
    }

    public Category getCategory() {
        return category;
    }

    public BigDecimal getPrice() {
        return price;
    }

    public LocalDate getLaunched() {
        return launched;
    }
}
