package com.embabel.publisher.it.app;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;

/* A plain repository with explicit searches, as an application already has: no JpaSpecificationExecutor. */
interface ProductRepository extends JpaRepository<Product, Long> {

    @Query("""
            select p from Product p
            where (:category is null or p.category = :category)
              and (:anyCategory = true or p.category in :categories)
              and (:namePrefix is null or p.name like concat(:namePrefix, '%') escape '!')
              and (:priceFrom is null or p.price >= :priceFrom)
              and (:priceTo is null or p.price <= :priceTo)
              and (:launchedFrom is null or p.launched >= :launchedFrom)
            """)
    Page<Product> search(@Param("category") Category category, @Param("anyCategory") boolean anyCategory,
                         @Param("categories") Collection<Category> categories, @Param("namePrefix") String namePrefix,
                         @Param("priceFrom") BigDecimal priceFrom, @Param("priceTo") BigDecimal priceTo,
                         @Param("launchedFrom") LocalDate launchedFrom, Pageable pageable);

    @Query("select p from Product p where p.id in :ids and (:category is null or p.category = :category)")
    List<Product> findByIds(@Param("ids") Collection<Long> ids, @Param("category") Category category);
}
