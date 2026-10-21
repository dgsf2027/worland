DROP TABLE IF EXISTS yc_rent_accounting_period;
CREATE TABLE yc_rent_accounting_period (
  id BIGINT AUTO_INCREMENT PRIMARY KEY, period VARCHAR(7) NOT NULL, book VARCHAR(8) NOT NULL,
  is_locked TINYINT NOT NULL DEFAULT 0, locked_by BIGINT, locked_at TIMESTAMP, remark VARCHAR(255),
  create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP, update_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  is_deleted TINYINT NOT NULL DEFAULT 0
);
DROP TABLE IF EXISTS yc_rent_audit_log;
CREATE TABLE yc_rent_audit_log (
  id BIGINT AUTO_INCREMENT PRIMARY KEY, action VARCHAR(64), target_type VARCHAR(64),
  target_id BIGINT, result VARCHAR(32), operator_id BIGINT, operator_name VARCHAR(64),
  operator_role VARCHAR(32), client_ip VARCHAR(64), request_uri VARCHAR(255),
  request_id VARCHAR(128), detail VARCHAR(2000), create_time TIMESTAMP
);
DROP TABLE IF EXISTS yc_rent_voucher_line;
DROP TABLE IF EXISTS yc_rent_voucher;
DROP TABLE IF EXISTS yc_rent_ledger_book;
DROP TABLE IF EXISTS yc_rent_asset_depreciation_line;
DROP TABLE IF EXISTS yc_rent_supplier_payment_alloc;
DROP TABLE IF EXISTS yc_rent_supplier_payment;
DROP TABLE IF EXISTS yc_rent_purchase_invoice;
DROP TABLE IF EXISTS yc_rent_purchase_contract_term;
DROP TABLE IF EXISTS yc_rent_purchase_contract;

CREATE TABLE yc_rent_voucher (
  id               bigint        NOT NULL AUTO_INCREMENT,
  voucher_no       varchar(64)   NOT NULL,
  source_doc_type  varchar(32)   NOT NULL,
  source_doc_id    bigint        DEFAULT NULL,
  book             varchar(8)    NOT NULL,
  period           varchar(7)    NOT NULL,
  biz_date         date          NOT NULL,
  total_amount     decimal(18,2) NOT NULL DEFAULT 0,
  entry_type       varchar(16)   NOT NULL DEFAULT 'other',
  summary          varchar(255)  DEFAULT NULL,
  is_reversal      tinyint(1)    NOT NULL DEFAULT 0,
  reverses_id      bigint        DEFAULT NULL,
  locked_period    tinyint(1)    NOT NULL DEFAULT 0,
  operator_id      bigint        DEFAULT NULL,
  remark           varchar(255)  DEFAULT NULL,
  create_time      datetime      NOT NULL DEFAULT CURRENT_TIMESTAMP,
  update_time      datetime      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  is_deleted       tinyint(1)    NOT NULL DEFAULT 0,
  PRIMARY KEY (id),
  UNIQUE KEY uk_voucher_no (voucher_no),
  UNIQUE KEY uk_voucher_reverses (reverses_id),
  KEY idx_voucher_source (source_doc_type, source_doc_id),
  KEY idx_voucher_book_period (book, period)
);

CREATE TABLE yc_rent_voucher_line (
  id           bigint        NOT NULL AUTO_INCREMENT,
  voucher_id   bigint        NOT NULL,
  account_code varchar(16)   NOT NULL,
  account_name varchar(64)   NOT NULL,
  direction    varchar(2)    NOT NULL,
  amount       decimal(18,2) NOT NULL,
  remark       varchar(255)  DEFAULT NULL,
  create_time  datetime      NOT NULL DEFAULT CURRENT_TIMESTAMP,
  is_deleted   tinyint(1)    NOT NULL DEFAULT 0,
  PRIMARY KEY (id),
  KEY idx_line_voucher (voucher_id)
);

CREATE TABLE yc_rent_ledger_book (
  id               bigint        NOT NULL AUTO_INCREMENT,
  book             varchar(8)    NOT NULL,
  period           varchar(7)    NOT NULL,
  entry_type       varchar(16)   NOT NULL,
  amount           decimal(18,2) NOT NULL,
  voucher_id       bigint        NOT NULL,
  source_doc_type  varchar(32)   DEFAULT NULL,
  source_doc_id    bigint        DEFAULT NULL,
  biz_date         date          NOT NULL,
  remark           varchar(255)  DEFAULT NULL,
  create_time      datetime      NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  KEY idx_ledger_book_period (book, period),
  KEY idx_ledger_book_type (book, entry_type),
  KEY idx_ledger_voucher (voucher_id)
);
