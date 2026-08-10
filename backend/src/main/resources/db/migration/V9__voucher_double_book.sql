-- =====================================================================
-- 沃朗科技租赁板块 · M3 Wave A 凭证双账 + 折旧真相源 Schema (V9)
-- MySQL 8 · utf8mb4 · InnoDB · 库 worland_dev · 表前缀 yc_rent_
-- 依据: DESIGN_DOC §4.3(voucher/voucher_line/ledger_book) + §十一 P2(voucher_line 借贷方向)
--       + ADR-004(asset_depreciation_line 折旧真相源+book_value 唯一写手) + ADR-003(多 ledger_book 双账)
--       + 系统方案 流程12 凭证双账 + 12_Phase1.5 P0-B/P2
-- 口径: 金额=元 decimal(18,2) · 手写 migration(§4.16,Flyway boot 自动 apply)
-- 单一真相源(§4.24):
--   * voucher = 凭证头(一业务事件 × 一账套 = 一凭证);借贷平衡 Σdr=Σcr(服务端校验)
--   * ledger_book = 双账已过账收入/成本流水(唯一写手=VoucherService.post/reverse·append-only 防漂移)
--   * asset_depreciation_line = 折旧真相源;asset.book_value 由本表 book_value_after 即时算(唯一写手)
--   * 红冲(P0-F 复用):单事务原子 + reverses_id 唯一约束(幂等键) + 锁账守卫(locked_period 拒写)
--   * 500万营收红线取 tax 账套(ops≠tax·折旧只落 ops·两账套天然分岔)
-- =====================================================================

SET NAMES utf8mb4;

-- ---------------------------------------------------------------------
-- 1. 凭证头 —— 业务单据自动生成;一业务事件按账套拆两张(tax/ops)
--    is_reversal=1 + reverses_id 唯一 = 红冲幂等键(一原凭证仅允许被红冲一次)
-- ---------------------------------------------------------------------
CREATE TABLE yc_rent_voucher (
  id               bigint        NOT NULL AUTO_INCREMENT COMMENT '主键',
  voucher_no       varchar(64)   NOT NULL COMMENT '凭证号(唯一)',
  source_doc_type  varchar(32)   NOT NULL COMMENT '来源单据类型:rent_bill收租/purchase_in采购/transfer转让/depreciation折旧/manual',
  source_doc_id    bigint        DEFAULT NULL COMMENT '来源单据 id',
  book             varchar(8)    NOT NULL COMMENT '账套口径:tax税务(分期收款销售) / ops经营(三层回报)',
  period           varchar(7)    NOT NULL COMMENT '记账期 YYYY-MM(锁账守卫基准)',
  biz_date         date          NOT NULL COMMENT '业务日期',
  total_amount     decimal(18,2) NOT NULL DEFAULT 0 COMMENT '借方合计(=贷方合计·借贷平衡校验值)',
  entry_type       varchar(16)   NOT NULL DEFAULT 'other' COMMENT '过账性质:revenue收入/cost成本/payable应付/other(ledger_book 汇总维度)',
  summary          varchar(255)  DEFAULT NULL COMMENT '摘要',
  is_reversal      tinyint(1)    NOT NULL DEFAULT 0 COMMENT '是否红冲凭证:0原始 1红冲',
  reverses_id      bigint        DEFAULT NULL COMMENT '红冲指向的原凭证 id(P0-F 幂等键·唯一约束防重复红冲)',
  locked_period    tinyint(1)    NOT NULL DEFAULT 0 COMMENT '记账期是否已锁(过账时快照·仅展示)',
  operator_id      bigint        DEFAULT NULL COMMENT '操作人(user 主键)',
  remark           varchar(255)  DEFAULT NULL COMMENT '备注',
  create_time      datetime      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  update_time      datetime      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  is_deleted       tinyint(1)    NOT NULL DEFAULT 0 COMMENT '逻辑删除:0否 1是',
  PRIMARY KEY (id),
  UNIQUE KEY uk_voucher_no (voucher_no),
  UNIQUE KEY uk_voucher_reverses (reverses_id) COMMENT 'P0-F 红冲幂等键:一原凭证仅允许一条红冲(NULL 不参与唯一)',
  KEY idx_voucher_source (source_doc_type, source_doc_id),
  KEY idx_voucher_book_period (book, period)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='凭证头:业财一体双账+红冲幂等键(M3-01)';

-- ---------------------------------------------------------------------
-- 2. 凭证分录行 —— 借贷方向 dr/cr + 科目 + 金额;Σdr=Σcr(服务端校验)
-- ---------------------------------------------------------------------
CREATE TABLE yc_rent_voucher_line (
  id           bigint        NOT NULL AUTO_INCREMENT COMMENT '主键',
  voucher_id   bigint        NOT NULL COMMENT '所属凭证(yc_rent_voucher.id)',
  account_code varchar(16)   NOT NULL COMMENT '会计科目编码:1002银行/1122应收/1601固资/1602累计折旧/2202应付/6001主营收入/6051租赁收入/6602折旧费用',
  account_name varchar(64)   NOT NULL COMMENT '会计科目名称',
  direction    varchar(2)    NOT NULL COMMENT '借贷方向:dr借 / cr贷',
  amount       decimal(18,2) NOT NULL COMMENT '金额(元·正数;方向由 direction 表达)',
  remark       varchar(255)  DEFAULT NULL COMMENT '行摘要',
  create_time  datetime      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  is_deleted   tinyint(1)    NOT NULL DEFAULT 0 COMMENT '逻辑删除:0否 1是',
  PRIMARY KEY (id),
  KEY idx_line_voucher (voucher_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='凭证分录:借贷方向+科目+金额(M3-01)';

-- ---------------------------------------------------------------------
-- 3. 双账账套流水(ledger_book) —— 已过账收入/成本按账套汇总
--    唯一写手=VoucherService.post/reverse(append-only·红冲写负额行·防 stale 漂移)
--    500万营收红线 = SUM(amount) WHERE book='tax' AND entry_type='revenue' AND YEAR
-- ---------------------------------------------------------------------
CREATE TABLE yc_rent_ledger_book (
  id               bigint        NOT NULL AUTO_INCREMENT COMMENT '主键',
  book             varchar(8)    NOT NULL COMMENT '账套:tax税务 / ops经营',
  period           varchar(7)    NOT NULL COMMENT '记账期 YYYY-MM',
  entry_type       varchar(16)   NOT NULL COMMENT '性质:revenue收入/cost成本/payable应付/other',
  amount           decimal(18,2) NOT NULL COMMENT '金额(元·有符号:原始为正·红冲为负)',
  voucher_id       bigint        NOT NULL COMMENT '来源凭证(yc_rent_voucher.id)',
  source_doc_type  varchar(32)   DEFAULT NULL COMMENT '来源单据类型',
  source_doc_id    bigint        DEFAULT NULL COMMENT '来源单据 id',
  biz_date         date          NOT NULL COMMENT '业务日期(500万按自然年归集)',
  remark           varchar(255)  DEFAULT NULL COMMENT '备注',
  create_time      datetime      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  PRIMARY KEY (id),
  KEY idx_ledger_book_period (book, period),
  KEY idx_ledger_book_type (book, entry_type),
  KEY idx_ledger_voucher (voucher_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='双账账套流水:收入/成本按账套汇总·500万红线取税务账(M3-01/08)';

-- ---------------------------------------------------------------------
-- 4. 折旧计划真相源(ADR-004 P0-B) —— 经营口径 ops 逐月折旧行
--    asset.book_value 由本表 book_value_after 即时算/回填(唯一写手)
--    unique(asset_id,book,period_no) = 月度计提幂等键(同期不重复计提)
-- ---------------------------------------------------------------------
CREATE TABLE yc_rent_asset_depreciation_line (
  id               bigint        NOT NULL AUTO_INCREMENT COMMENT '主键',
  asset_id         bigint        NOT NULL COMMENT '设备(yc_rent_asset.id)',
  book             varchar(8)    NOT NULL DEFAULT 'ops' COMMENT '账套:ops经营口径(折旧只落经营账)',
  period_no        int           NOT NULL COMMENT '折旧期次(第 N 个月·从 1 起)',
  period           varchar(7)    NOT NULL COMMENT '记账期 YYYY-MM',
  depr_amount      decimal(18,2) NOT NULL COMMENT '本期折旧额(元)',
  book_value_after decimal(18,2) NOT NULL COMMENT '本期折旧后账面净值(元·递减·asset.book_value 真相源)',
  voucher_id       bigint        DEFAULT NULL COMMENT '折旧凭证(yc_rent_voucher.id·ops 账)',
  biz_date         date          NOT NULL COMMENT '计提业务日期',
  remark           varchar(255)  DEFAULT NULL COMMENT '备注',
  create_time      datetime      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  is_deleted       tinyint(1)    NOT NULL DEFAULT 0 COMMENT '逻辑删除:0否 1是',
  PRIMARY KEY (id),
  UNIQUE KEY uk_depr_asset_period (asset_id, book, period_no) COMMENT '月度计提幂等键:同设备同账套同期次仅一行',
  KEY idx_depr_asset (asset_id),
  KEY idx_depr_period (period)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='折旧计划真相源:经营口径逐月折旧+book_value 唯一写手(M3-02·ADR-004 P0-B)';

-- ---------------------------------------------------------------------
-- 5. rule_config 追加:500万营收红线口径常量(禁硬编码 §4.24)
-- ---------------------------------------------------------------------
INSERT INTO yc_rent_rule_config (rule_key, scope_key, rule_value, value_type, version, effective_from, effective_to, remark) VALUES
 ('tax_revenue_threshold',   '', 5000000.00, 'money', 1, '2023-01-01', NULL, '增值税营收红线:自然年 tax 账套收入达 500万预警(小规模→一般纳税人/税负跳档)'),
 ('tax_threshold_warn_ratio','', 0.80000000, 'rate',  1, '2023-01-01', NULL, '营收红线预警占比:达阈值×0.8 亮黄灯,达阈值亮红灯');
