-- H2-compatible V129 columns for yc_rent_purchase_contract & yc_rent_purchase_contract_term
ALTER TABLE yc_rent_purchase_contract ADD COLUMN detail_subtotal DECIMAL(14,2) DEFAULT NULL;
ALTER TABLE yc_rent_purchase_contract ADD COLUMN discount_amount DECIMAL(14,2) DEFAULT 0;
ALTER TABLE yc_rent_purchase_contract ADD COLUMN warranty_years INT DEFAULT NULL;
ALTER TABLE yc_rent_purchase_contract ADD COLUMN delivery_address VARCHAR(255) DEFAULT NULL;
ALTER TABLE yc_rent_purchase_contract ADD COLUMN delivery_contact VARCHAR(64) DEFAULT NULL;
ALTER TABLE yc_rent_purchase_contract ADD COLUMN acceptance_date DATE DEFAULT NULL;
ALTER TABLE yc_rent_purchase_contract ADD COLUMN audit_pass_date DATE DEFAULT NULL;
ALTER TABLE yc_rent_purchase_contract ADD COLUMN shipped_date DATE DEFAULT NULL;
ALTER TABLE yc_rent_purchase_contract ADD COLUMN install_done_date DATE DEFAULT NULL;
ALTER TABLE yc_rent_purchase_contract ADD COLUMN load_test_date DATE DEFAULT NULL;
ALTER TABLE yc_rent_purchase_contract ADD COLUMN weigh_tons DECIMAL(10,2) DEFAULT NULL;
ALTER TABLE yc_rent_purchase_contract ADD COLUMN pallet_slots INT DEFAULT NULL;

ALTER TABLE yc_rent_purchase_contract_term ADD COLUMN trigger_point VARCHAR(16) DEFAULT '审核通过';
ALTER TABLE yc_rent_purchase_contract_term ADD COLUMN due_rule VARCHAR(24) DEFAULT 'after_days';
ALTER TABLE yc_rent_purchase_contract_term ADD COLUMN due_days INT DEFAULT 0;
ALTER TABLE yc_rent_purchase_contract_term ADD COLUMN due_day_of_month INT DEFAULT NULL;
ALTER TABLE yc_rent_purchase_contract_term ADD COLUMN due_date DATE DEFAULT NULL;
