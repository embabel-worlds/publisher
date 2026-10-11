INSERT INTO region (id, name) VALUES (1, 'North'), (2, 'South');
INSERT INTO customer (id, name, city, tier, balance, since, external_ref, region_id) VALUES
 (1, 'Acme', 'Madison', 'PRO', 1200.50, DATE '2020-01-15', 9007199254740993, 1),
 (2, 'Müller GmbH', 'Berlin', 'ENTERPRISE', 0.00, DATE '2019-06-01', 42, 2),
 (3, 'muller & sons', 'Madison', 'FREE', NULL, NULL, NULL, NULL),
 (4, 'O''Brien', NULL, NULL, 99.99, DATE '2021-03-03', NULL, 1),
 (5, '50%_off', 'Madison', 'PRO', 1200.5, DATE '2020-01-15', NULL, 2),
 (6, 'Élodie', 'Paris', 'FREE', -10, DATE '2022-12-31', NULL, NULL),
 (7, 'acme labs', 'madison', 'PRO', 5000, DATE '2018-07-07', NULL, 1),
 (8, 'Zeta', 'Oslo', NULL, NULL, DATE '2023-01-01', NULL, 2),
 (9, '5000_off', 'Madison', 'ENTERPRISE', 1, DATE '2020-01-16', NULL, NULL),
 (10, 'Beta', 'Berlin', 'FREE', 250.25, DATE '2020-01-14', NULL, 1);
INSERT INTO invoice (number, customer_id, amount, due_date, status) VALUES
 ('INV-1', 1, 100.00, DATE '2026-10-31', 'OPEN'),
 ('INV-2', 1, 80.00, DATE '2026-11-30', 'OPEN'),
 ('INV-3', 1, 15.50, DATE '2026-09-30', 'PAID'),
 ('INV-4', 2, 999.99, NULL, 'DRAFT'),
 ('INV-5', 4, 10, DATE '2026-10-01', 'OPEN'),
 ('INV-6', 5, 0.01, DATE '2026-10-02', 'VOID'),
 ('INV-7', 7, 5000, DATE '2026-12-25', 'OPEN');
INSERT INTO product (id, sku, name, category, price, launched) VALUES
 (1, 'T-100', 'Hammer', 'TOOLS', 12.50, DATE '2024-01-10'),
 (2, 'T-200', 'hacksaw', 'TOOLS', 19.99, NULL),
 (3, 'Y-100', 'Yo-yo', 'TOYS', 3.00, DATE '2025-06-01'),
 (4, 'Y-200', NULL, 'TOYS', NULL, DATE '2023-03-03'),
 (5, 'F-100', '100%_Juice', 'FOOD', 2.5, DATE '2025-06-01'),
 (6, 'F-200', 'Hamburger', NULL, 7.25, DATE '2022-12-12'),
 (7, 'T-300', 'Hammer drill', 'TOOLS', 99.00, DATE '2025-01-01'),
 (8, 'X-000', 'Ham', 'FOOD', 12.5, NULL);
