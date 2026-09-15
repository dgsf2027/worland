-- =====================================================================
-- 资产管理(V108,替代原「盘点·差异/盘盈亏」入口;盘点表 V12 保留不动)
-- 仓库实物管理,独立于「设备·租赁台账」(逐台融资设备)。一条资产 = 一批同规格实物(如某规格播种墙 N 套),按数量管理:
--   total_qty = stock_qty(库存·未预订) + reserved_qty(已预订) + rented_qty(出租中) + repair_qty(维修中) + scrapped_qty(已报废)
-- 流程:
--   新建出租(预订)      库存 → 已预订
--   出库(可分批)        已预订 → 出租中
--   归还(可分批)        出租中 → 库存 / 维修中 / 已报废;同时检查货架/格口/电子标签等损坏缺件,按赔偿价目自动计算赔偿
--   入库 / 送修 / 修好 / 报废  库存与维修中、已报废之间调整
-- 二维码:每批一个 qr_token,标签可重复打印;扫码打开手机网页,未登录只见企业信息与名称规格,登录后可出库/归还。
-- 照片走对象存储 yc_rent_file_object:biz_type=inv_item(资产照片)/inv_movement(出入库现场照片)。
-- =====================================================================

SET NAMES utf8mb4;

CREATE TABLE yc_rent_inv_item (
  id              bigint        NOT NULL AUTO_INCREMENT COMMENT '主键',
  code            varchar(32)   NOT NULL COMMENT '资产编号(空则自动生成 ZC+日期+序号)',
  name            varchar(64)   NOT NULL COMMENT '名称:播种墙/货架…',
  spec            varchar(128)  DEFAULT NULL COMMENT '规格型号',
  category        varchar(16)   DEFAULT NULL COMMENT '类别:播种墙/货架/阁楼/其他',
  unit            varchar(8)    NOT NULL DEFAULT '套' COMMENT '计量单位:套/组/件',
  total_qty       int           NOT NULL DEFAULT 0 COMMENT '总数量(=各状态数量之和)',
  stock_qty       int           NOT NULL DEFAULT 0 COMMENT '库存(在库未预订,即闲置)',
  reserved_qty    int           NOT NULL DEFAULT 0 COMMENT '已预订(在库已被出租单锁定)',
  rented_qty      int           NOT NULL DEFAULT 0 COMMENT '出租中',
  repair_qty      int           NOT NULL DEFAULT 0 COMMENT '维修中',
  scrapped_qty    int           NOT NULL DEFAULT 0 COMMENT '已报废',
  location        varchar(128)  DEFAULT NULL COMMENT '存放位置',
  qr_token        varchar(32)   NOT NULL COMMENT '二维码令牌(随机不可枚举)',
  remark          varchar(255)  DEFAULT NULL COMMENT '备注',
  create_by_name  varchar(64)   DEFAULT NULL COMMENT '建档人',
  create_time     datetime      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  update_time     datetime      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  is_deleted      tinyint(1)    NOT NULL DEFAULT 0 COMMENT '逻辑删除:0否 1是',
  PRIMARY KEY (id),
  UNIQUE KEY uk_inv_item_code (code),
  UNIQUE KEY uk_inv_item_qr (qr_token)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='资产管理:一批同规格实物,按状态数量管理';

CREATE TABLE yc_rent_inv_rental (
  id                   bigint        NOT NULL AUTO_INCREMENT COMMENT '主键',
  rental_no            varchar(32)   NOT NULL COMMENT '出租单号(CZ+日期+序号)',
  item_id              bigint        NOT NULL COMMENT '资产(yc_rent_inv_item.id)',
  customer_id          bigint        DEFAULT NULL COMMENT '客户(yc_rent_customer.id)',
  customer_name        varchar(128)  NOT NULL COMMENT '客户名称(快照)',
  contract_id          bigint        DEFAULT NULL COMMENT '关联合同(可空,用于合同到期提醒)',
  install_address      varchar(255)  DEFAULT NULL COMMENT '安装地址',
  contact              varchar(64)   DEFAULT NULL COMMENT '现场联系人',
  phone                varchar(32)   DEFAULT NULL COMMENT '联系电话',
  qty                  int           NOT NULL COMMENT '出租数量',
  out_qty              int           NOT NULL DEFAULT 0 COMMENT '已出库数量',
  returned_qty         int           NOT NULL DEFAULT 0 COMMENT '已归还数量',
  start_date           date          NOT NULL COMMENT '开始时间',
  expected_return_date date          NOT NULL COMMENT '预计归还时间',
  actual_return_date   date          DEFAULT NULL COMMENT '实际归还完成日',
  status               varchar(8)    NOT NULL DEFAULT '已预订' COMMENT '已预订/出租中/已归还/已取消',
  remark               varchar(255)  DEFAULT NULL COMMENT '备注',
  create_by_name       varchar(64)   DEFAULT NULL COMMENT '创建人',
  create_time          datetime      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  update_time          datetime      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  is_deleted           tinyint(1)    NOT NULL DEFAULT 0 COMMENT '逻辑删除:0否 1是',
  PRIMARY KEY (id),
  UNIQUE KEY uk_inv_rental_no (rental_no),
  KEY idx_inv_rental_item (item_id),
  KEY idx_inv_rental_customer (customer_id),
  KEY idx_inv_rental_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='资产出租单:客户/安装地址/数量/起止时间';

CREATE TABLE yc_rent_inv_movement (
  id                 bigint        NOT NULL AUTO_INCREMENT COMMENT '主键',
  item_id            bigint        NOT NULL COMMENT '资产',
  rental_id          bigint        DEFAULT NULL COMMENT '出租单(出库/归还时)',
  type               varchar(8)    NOT NULL COMMENT '出库/归还/入库/送修/修好/报废',
  qty                int           NOT NULL COMMENT '数量',
  good_qty           int           NOT NULL DEFAULT 0 COMMENT '归还:完好入库数量',
  repair_qty         int           NOT NULL DEFAULT 0 COMMENT '归还:转维修数量',
  scrap_qty          int           NOT NULL DEFAULT 0 COMMENT '归还:报废数量',
  accessories        varchar(500)  DEFAULT NULL COMMENT '配件清单',
  condition_level    varchar(8)    DEFAULT NULL COMMENT '设备状况:完好/轻微损坏/损坏',
  condition_desc     varchar(500)  DEFAULT NULL COMMENT '设备状况说明',
  compensation_total decimal(18,2) NOT NULL DEFAULT 0 COMMENT '本次赔偿合计',
  operator_id        bigint        DEFAULT NULL COMMENT '经办人',
  operator_name      varchar(64)   DEFAULT NULL COMMENT '经办人姓名',
  op_time            datetime      NOT NULL COMMENT '登记时间',
  remark             varchar(255)  DEFAULT NULL COMMENT '备注',
  create_time        datetime      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  update_time        datetime      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  is_deleted         tinyint(1)    NOT NULL DEFAULT 0 COMMENT '逻辑删除:0否 1是',
  PRIMARY KEY (id),
  KEY idx_inv_move_item (item_id),
  KEY idx_inv_move_rental (rental_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='资产出入库记录:数量/配件/状况/现场照片';

CREATE TABLE yc_rent_inv_damage (
  id              bigint        NOT NULL AUTO_INCREMENT COMMENT '主键',
  movement_id     bigint        NOT NULL COMMENT '归还记录(yc_rent_inv_movement.id)',
  rental_id       bigint        DEFAULT NULL COMMENT '出租单',
  item_id         bigint        NOT NULL COMMENT '资产',
  part_name       varchar(32)   NOT NULL COMMENT '检查项:货架/格口/电子标签…',
  damaged_qty     int           NOT NULL DEFAULT 0 COMMENT '损坏数量',
  missing_qty     int           NOT NULL DEFAULT 0 COMMENT '缺失数量',
  damage_price    decimal(18,2) NOT NULL DEFAULT 0 COMMENT '损坏单价(登记时快照)',
  missing_price   decimal(18,2) NOT NULL DEFAULT 0 COMMENT '缺失单价(登记时快照)',
  amount          decimal(18,2) NOT NULL DEFAULT 0 COMMENT '赔偿金额=损坏数×损坏单价+缺失数×缺失单价',
  settle_status   varchar(8)    NOT NULL DEFAULT '待收取' COMMENT '待收取/已收取/已减免',
  remark          varchar(255)  DEFAULT NULL COMMENT '说明',
  create_time     datetime      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  update_time     datetime      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  is_deleted      tinyint(1)    NOT NULL DEFAULT 0 COMMENT '逻辑删除:0否 1是',
  PRIMARY KEY (id),
  KEY idx_inv_damage_move (movement_id),
  KEY idx_inv_damage_rental (rental_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='归还损坏与缺件:自动计算赔偿';

CREATE TABLE yc_rent_inv_comp_price (
  id              bigint        NOT NULL AUTO_INCREMENT COMMENT '主键',
  part_name       varchar(32)   NOT NULL COMMENT '检查项名称',
  unit            varchar(8)    NOT NULL DEFAULT '个' COMMENT '单位',
  damage_price    decimal(18,2) NOT NULL DEFAULT 0 COMMENT '损坏赔偿单价',
  missing_price   decimal(18,2) NOT NULL DEFAULT 0 COMMENT '缺失赔偿单价',
  sort_no         int           NOT NULL DEFAULT 0 COMMENT '排序',
  create_time     datetime      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  update_time     datetime      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  is_deleted      tinyint(1)    NOT NULL DEFAULT 0 COMMENT '逻辑删除:0否 1是',
  PRIMARY KEY (id),
  KEY idx_inv_price_name (part_name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='损坏缺件赔偿价目(单价须按公司标准维护)';

-- 常用检查项;单价默认 0,须在「资产管理 › 设置」按公司标准填写后才会算出赔偿金额
INSERT INTO yc_rent_inv_comp_price (part_name, unit, damage_price, missing_price, sort_no) VALUES
  ('货架', '组', 0, 0, 1),
  ('货架层板', '块', 0, 0, 2),
  ('格口', '个', 0, 0, 3),
  ('电子标签', '个', 0, 0, 4),
  ('灯条', '条', 0, 0, 5),
  ('控制器', '个', 0, 0, 6);

CREATE TABLE yc_rent_inv_company (
  id              bigint        NOT NULL COMMENT '主键(固定 1)',
  company_name    varchar(128)  NOT NULL COMMENT '企业名称(二维码标签/扫码页展示)',
  phone           varchar(32)   DEFAULT NULL COMMENT '服务电话',
  address         varchar(255)  DEFAULT NULL COMMENT '企业地址',
  website         varchar(128)  DEFAULT NULL COMMENT '网址',
  notice          varchar(255)  DEFAULT NULL COMMENT '标签提示语',
  update_time     datetime      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='资产标签企业信息(单行)';

INSERT INTO yc_rent_inv_company (id, company_name, notice) VALUES
  (1, '曜石科技', '本设备为租赁资产,请勿私自拆卸、转移');
