-- 地图位置独立于仓库内部位置描述；统一保存 GCJ-02 经纬度。
ALTER TABLE yc_rent_inv_item
  ADD COLUMN (
    map_longitude decimal(10,7) DEFAULT NULL COMMENT '资产定位经度 GCJ-02',
    map_latitude decimal(10,7) DEFAULT NULL COMMENT '资产定位纬度 GCJ-02',
    map_address varchar(255) DEFAULT NULL COMMENT '定位地址',
    map_provider varchar(16) DEFAULT NULL COMMENT 'amap/baidu/tencent/browser/manual',
    map_source varchar(16) DEFAULT NULL COMMENT 'map/gps/manual',
    map_accuracy decimal(10,2) DEFAULT NULL COMMENT '现场定位精度(米)',
    map_updated_at datetime DEFAULT NULL COMMENT '定位更新时间',
    map_updated_by varchar(64) DEFAULT NULL COMMENT '定位操作人'
  );
