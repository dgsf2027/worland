# 资产管理重组 + 总资产报表 + 资金进出强制回单 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 落地 10-07 会议纪要子项目 A：资产管理只留资产本身、新增「服务」模块；设备台账加总资产（含/不含折旧）；6 个资金动作必须先传回单才能登记。

**Architecture:** 新增 `payproof` 模块（关联表 `yc_rent_pay_proof` + `PayProofService.bind`），在采购付款、收租核销/批量核销/退款、逾期还款、赔偿收取的**同一事务**里先绑回单再执行原逻辑；回单文件走现有对象存储（`bizType=pay_proof`）。总资产由新 `AssetValueService` 从设备台账 + 经营口径折旧行即时汇总。前端把维保/赔偿/设置三个标签页从 `Inventory.vue` 原样搬进新 `Service.vue`。

**Tech Stack:** Spring Boot 2.7 + MyBatis-Plus + Flyway（MySQL）+ JUnit5/Mockito；Vue 3 + Element Plus + Vite（pnpm）。

**Spec:** `docs/superpowers/specs/2026-10-13-asset-regroup-total-asset-pay-proof-design.md`

## Global Constraints

- 迁移号：本批只用 **V122**（远端所有分支最大为 V121，已核对无冲突）。
- 后端接口路径不改：维保 `/rent/maintenance`、赔偿 `/rent/inventory/damages`、价目 `/rent/inventory/comp-prices`、标签企业信息 `/rent/inventory/company`。
- 界面文案统一叫**「回单」**，不叫「凭证」（收租页「凭证」列指记账凭证 `voucher_id`）。
- 回单文件：`bizType=pay_proof`，扩展名仅 `jpg/jpeg/png/pdf`，单个 ≤ 10MB，**不可删除**（不进 `DELETABLE_BIZ_TYPES`）。
- 回单上传角色：老板、财务、供应链；回单查看：`DataScope.canSeeCost` 角色（GP/LP 不可见）。
- 核销（单笔、批量）、逾期还款恢复加 `@RequireRole({"财务","老板"})`。
- 总资产只统计设备台账状态 `采购/投放/在租/待转让/收回待处置`；累计折旧只取 `book='ops'`；缺集采价的设备不进合计，单独计数。
- 总资产接口与卡片仅老板、财务可见。
- 新增 Java final 依赖一律**加在类里最后一个 `private final` 字段之后**（Lombok `@RequiredArgsConstructor` 按声明顺序生成构造器，测试按位置传参）。
- 仓库是**公开**的：不得写入真实客户、价格、密钥。
- 提交身份已在仓库本地配置为 `李伟 <xuanhungcf588e@gmail.com>`；提交信息结尾加 `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`；完成后直接推 `main`（推前先 `git fetch` 确认远端无新提交）。
- 本机没有 MySQL/Docker，后端无法整体启动：验证靠单元测试 + `mvn -q test` + `pnpm --dir frontend build`；接口实测放到部署后按 Task 11 的核对清单走。

## 文件结构

| 文件 | 职责 |
|---|---|
| `backend/src/main/resources/db/migration/V122__pay_proof.sql` | 新表 `yc_rent_pay_proof` |
| `backend/.../modules/payproof/domain/PayProof.java` | 实体 |
| `backend/.../modules/payproof/mapper/PayProofMapper.java` | Mapper |
| `backend/.../modules/payproof/dto/PayProofDtos.java` | `ProofView`、`SupplementRequest` |
| `backend/.../modules/payproof/service/PayProofService.java` | 绑定校验、查询、计数、补传 |
| `backend/.../modules/payproof/interfaces/PayProofController.java` | `GET/POST /rent/pay-proofs` |
| `backend/.../modules/file/service/FileStorageService.java` | `pay_proof` 上传规则与查看鉴权 |
| `backend/.../modules/purchase/...` | 登记付款带回单；应付行回单数 |
| `backend/.../modules/billing/...` | 核销/批量/退款/还款带回单；权限收紧；稽核「已核销无回单」 |
| `backend/.../modules/inventory/...` | 赔偿收取带回单、减免必填原因；提醒去掉「赔偿待收」 |
| `backend/.../modules/asset/dto/AssetValueDtos.java` | 总资产 DTO |
| `backend/.../modules/asset/service/AssetValueService.java` | 汇总计算 + Excel |
| `backend/.../modules/asset/interfaces/AssetController.java` | `total-value`、`total-value/export` |
| `frontend/src/api/payProof.ts` | 回单接口 |
| `frontend/src/components/payproof/ProofUploader.vue` | 选择并上传回单（v-model 文件 id） |
| `frontend/src/components/payproof/ProofViewer.vue` | 查看/补传回单弹窗 |
| `frontend/src/views/Service.vue` | 新「服务」模块：维保工单 / 损坏与赔偿 / 设置 |
| `frontend/src/views/Inventory.vue` | 删掉三个标签页及其脚本 |
| `frontend/src/views/Purchase.vue`、`Rent.vue`、`Asset.vue` | 接回单、总资产卡片 |
| `frontend/src/router/index.ts`、`App.vue`、`components/inventory/MovementForm.vue` | 路由、侧栏、文案 |

---

### Task 1: 回单核心（迁移 + PayProofService + 上传规则）

**Files:**
- Create: `backend/src/main/resources/db/migration/V122__pay_proof.sql`
- Create: `backend/src/main/java/top/aole/rent/modules/payproof/domain/PayProof.java`
- Create: `backend/src/main/java/top/aole/rent/modules/payproof/mapper/PayProofMapper.java`
- Create: `backend/src/main/java/top/aole/rent/modules/payproof/dto/PayProofDtos.java`
- Create: `backend/src/main/java/top/aole/rent/modules/payproof/service/PayProofService.java`
- Create: `backend/src/main/java/top/aole/rent/modules/payproof/interfaces/PayProofController.java`
- Modify: `backend/src/main/java/top/aole/rent/modules/file/service/FileStorageService.java`
- Test: `backend/src/test/java/top/aole/rent/modules/payproof/PayProofServiceTest.java`

**Interfaces:**
- Produces:
  - `PayProofService.FILE_BIZ_TYPE = "pay_proof"`，`PAYABLE = "payable"`，`RENT_BILL = "rent_bill"`，`INV_DAMAGE = "inv_damage"`，`UPLOAD_ROLES`
  - `void bind(String bizType, List<Long> bizIds, String action, List<Long> fileIds)` —— 缺回单/文件非法抛 `BizException(400)`
  - `List<PayProofDtos.ProofView> list(String bizType, Long bizId)`
  - `Map<Long, Integer> countMap(String bizType, Collection<Long> bizIds)` —— 没有回单的 id 不在 map 里
  - `void supplement(PayProofDtos.SupplementRequest req)`

- [ ] **Step 1: 写迁移**

`V122__pay_proof.sql`：

```sql
-- =====================================================================
-- 资金进出回单(V122 · 10-07 会议纪要「付款凭证强制留存」)
-- 采购付款 / 收租核销(含批量)/ 收租退款 / 逾期还款 / 赔偿收取,登记前必须先上传
-- 银行回单或支付截图。文件本身在 yc_rent_file_object(biz_type=pay_proof),
-- 本表记录「哪个文件挂在哪张单据上」:一个文件可挂多张单(批量核销一张回单对多张收租单)。
-- 回单不可删除,撤销付款/红冲不删关联,保留痕迹。
-- =====================================================================

SET NAMES utf8mb4;

CREATE TABLE yc_rent_pay_proof (
  id              bigint       NOT NULL AUTO_INCREMENT COMMENT '主键',
  file_id         bigint       NOT NULL COMMENT '回单文件(yc_rent_file_object.id,biz_type=pay_proof)',
  biz_type        varchar(16)  NOT NULL COMMENT '单据类型:payable/rent_bill/inv_damage',
  biz_id          bigint       NOT NULL COMMENT '单据 id',
  action          varchar(16)  NOT NULL COMMENT '付款/核销/批量核销/退款/还款/赔偿收取/补传',
  create_by_name  varchar(64)  DEFAULT NULL COMMENT '挂回单的人',
  create_time     datetime     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '挂回单时间',
  PRIMARY KEY (id),
  UNIQUE KEY uk_pay_proof (file_id, biz_type, biz_id),
  KEY idx_pay_proof_biz (biz_type, biz_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='资金进出回单关联';
```

- [ ] **Step 2: 实体、Mapper、DTO**

`PayProof.java`：

```java
package top.aole.rent.modules.payproof.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** 资金进出回单关联(V122):一个回单文件可挂多张单据。无逻辑删除——回单只增不删。 */
@Data
@TableName("yc_rent_pay_proof")
public class PayProof {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long fileId;

    /** payable / rent_bill / inv_damage */
    private String bizType;

    private Long bizId;

    /** 付款/核销/批量核销/退款/还款/赔偿收取/补传 */
    private String action;

    private String createByName;

    private LocalDateTime createTime;
}
```

`PayProofMapper.java`：

```java
package top.aole.rent.modules.payproof.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import top.aole.rent.modules.payproof.domain.PayProof;

@Mapper
public interface PayProofMapper extends BaseMapper<PayProof> {
}
```

`PayProofDtos.java`：

```java
package top.aole.rent.modules.payproof.dto;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

public class PayProofDtos {

    @Data
    public static class ProofView {
        private Long proofId;
        private Long fileId;
        private String fileName;
        private String contentType;
        private Long size;
        private String action;
        private String createByName;
        private LocalDateTime createTime;
    }

    /** 历史单据补传回单 */
    @Data
    public static class SupplementRequest {
        private String bizType;
        private Long bizId;
        private List<Long> fileIds;
    }
}
```

- [ ] **Step 3: 写失败的测试**

先确认 `BizException` 的状态码 getter 名：`grep -n "getCode\|private.*code" backend/src/main/java/top/aole/rent/common/exception/BizException.java`；下面测试用 `e.getCode()`，名字不同就替换。

`PayProofServiceTest.java`：

```java
package top.aole.rent.modules.payproof;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import top.aole.rent.common.auth.CurrentUser;
import top.aole.rent.common.auth.UserContext;
import top.aole.rent.common.exception.BizException;
import top.aole.rent.modules.billing.domain.RentBill;
import top.aole.rent.modules.billing.mapper.RentBillMapper;
import top.aole.rent.modules.file.domain.FileObject;
import top.aole.rent.modules.file.mapper.FileObjectMapper;
import top.aole.rent.modules.inventory.domain.InvDamage;
import top.aole.rent.modules.inventory.mapper.InvDamageMapper;
import top.aole.rent.modules.payproof.domain.PayProof;
import top.aole.rent.modules.payproof.dto.PayProofDtos;
import top.aole.rent.modules.payproof.mapper.PayProofMapper;
import top.aole.rent.modules.payproof.service.PayProofService;
import top.aole.rent.modules.purchase.domain.Payable;
import top.aole.rent.modules.purchase.mapper.PayableMapper;

import java.util.Arrays;
import java.util.Collections;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 资金回单:先传后登记的校验、批量挂多单、重复不重挂、补传状态与角色。 */
class PayProofServiceTest {

    private PayProofMapper proofMapper;
    private FileObjectMapper fileMapper;
    private PayableMapper payableMapper;
    private RentBillMapper rentBillMapper;
    private InvDamageMapper damageMapper;
    private PayProofService service;

    @BeforeAll
    static void initLambdaCache() {
        MapperBuilderAssistant a = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(a, PayProof.class);
        TableInfoHelper.initTableInfo(a, FileObject.class);
    }

    @BeforeEach
    void setUp() {
        proofMapper = mock(PayProofMapper.class);
        fileMapper = mock(FileObjectMapper.class);
        payableMapper = mock(PayableMapper.class);
        rentBillMapper = mock(RentBillMapper.class);
        damageMapper = mock(InvDamageMapper.class);
        service = new PayProofService(proofMapper, fileMapper, payableMapper, rentBillMapper, damageMapper);
        UserContext.set(new CurrentUser(1005L, "财务", "财务", null));
        when(proofMapper.selectCount(any())).thenReturn(0L);
    }

    @AfterEach
    void tearDown() {
        UserContext.clear();
    }

    private FileObject file(long id, String bizType, long uploaderId) {
        FileObject fo = new FileObject();
        fo.setId(id);
        fo.setBizType(bizType);
        fo.setUploaderId(uploaderId);
        fo.setFileName("回单" + id + ".png");
        fo.setIsDeleted(0);
        when(fileMapper.selectById(id)).thenReturn(fo);
        return fo;
    }

    @Test
    void 没传回单直接拒() {
        BizException e = assertThrows(BizException.class,
                () -> service.bind(PayProofService.PAYABLE, Collections.singletonList(1L), "付款", Collections.emptyList()));
        assertTrue(e.getMessage().contains("请先上传回单"), e.getMessage());
        BizException e2 = assertThrows(BizException.class,
                () -> service.bind(PayProofService.PAYABLE, Collections.singletonList(1L), "付款", null));
        assertTrue(e2.getMessage().contains("请先上传回单"), e2.getMessage());
        verify(proofMapper, never()).insert(any(PayProof.class));
    }

    @Test
    void 文件不存在拒() {
        BizException e = assertThrows(BizException.class,
                () -> service.bind(PayProofService.PAYABLE, Collections.singletonList(1L), "付款", Collections.singletonList(99L)));
        assertTrue(e.getMessage().contains("回单文件不存在"), e.getMessage());
    }

    @Test
    void 不是回单类型的文件拒() {
        file(70L, "inv_item", 1005L);
        BizException e = assertThrows(BizException.class,
                () -> service.bind(PayProofService.PAYABLE, Collections.singletonList(1L), "付款", Collections.singletonList(70L)));
        assertTrue(e.getMessage().contains("不是回单"), e.getMessage());
    }

    @Test
    void 别人上传的回单不能用() {
        file(71L, PayProofService.FILE_BIZ_TYPE, 2000L);
        BizException e = assertThrows(BizException.class,
                () -> service.bind(PayProofService.PAYABLE, Collections.singletonList(1L), "付款", Collections.singletonList(71L)));
        assertTrue(e.getMessage().contains("自己上传"), e.getMessage());
    }

    @Test
    void 一张回单挂多张单() {
        file(72L, PayProofService.FILE_BIZ_TYPE, 1005L);
        service.bind(PayProofService.RENT_BILL, Arrays.asList(11L, 12L), "批量核销", Collections.singletonList(72L));
        ArgumentCaptor<PayProof> cap = ArgumentCaptor.forClass(PayProof.class);
        verify(proofMapper, times(2)).insert(cap.capture());
        assertEquals(11L, cap.getAllValues().get(0).getBizId());
        assertEquals(12L, cap.getAllValues().get(1).getBizId());
        assertEquals("批量核销", cap.getAllValues().get(0).getAction());
        assertEquals("财务", cap.getAllValues().get(0).getCreateByName());
    }

    @Test
    void 已挂过的不重复插() {
        file(73L, PayProofService.FILE_BIZ_TYPE, 1005L);
        when(proofMapper.selectCount(any())).thenReturn(1L);
        service.bind(PayProofService.PAYABLE, Collections.singletonList(1L), "付款", Arrays.asList(73L, 73L));
        verify(proofMapper, never()).insert(any(PayProof.class));
    }

    @Test
    void 计数按单据分组() {
        PayProof a = new PayProof(); a.setBizId(1L);
        PayProof b = new PayProof(); b.setBizId(1L);
        PayProof c = new PayProof(); c.setBizId(2L);
        when(proofMapper.selectList(any())).thenReturn(Arrays.asList(a, b, c));
        Map<Long, Integer> m = service.countMap(PayProofService.RENT_BILL, Arrays.asList(1L, 2L, 3L));
        assertEquals(2, m.get(1L));
        assertEquals(1, m.get(2L));
        assertFalse(m.containsKey(3L));
        assertTrue(service.countMap(PayProofService.RENT_BILL, Collections.emptyList()).isEmpty());
    }

    @Test
    void 补传只对已付应付开放() {
        Payable p = new Payable();
        p.setId(1L);
        p.setStatus("待付");
        when(payableMapper.selectById(1L)).thenReturn(p);
        PayProofDtos.SupplementRequest req = new PayProofDtos.SupplementRequest();
        req.setBizType(PayProofService.PAYABLE);
        req.setBizId(1L);
        req.setFileIds(Collections.singletonList(74L));
        BizException e = assertThrows(BizException.class, () -> service.supplement(req));
        assertTrue(e.getMessage().contains("已付"), e.getMessage());

        p.setStatus("已付");
        file(74L, PayProofService.FILE_BIZ_TYPE, 1005L);
        service.supplement(req);
        ArgumentCaptor<PayProof> cap = ArgumentCaptor.forClass(PayProof.class);
        verify(proofMapper).insert(cap.capture());
        assertEquals("补传", cap.getValue().getAction());
    }

    @Test
    void 补传收租单限财务老板() {
        RentBill b = new RentBill();
        b.setId(5L);
        b.setStatus("已核销");
        when(rentBillMapper.selectById(5L)).thenReturn(b);
        PayProofDtos.SupplementRequest req = new PayProofDtos.SupplementRequest();
        req.setBizType(PayProofService.RENT_BILL);
        req.setBizId(5L);
        req.setFileIds(Collections.singletonList(75L));
        UserContext.set(new CurrentUser(1006L, "供应链", "供应链", null));
        BizException e = assertThrows(BizException.class, () -> service.supplement(req));
        assertEquals(403, e.getCode());
    }

    @Test
    void 补传赔偿要已收取() {
        InvDamage d = new InvDamage();
        d.setId(9L);
        d.setSettleStatus("待收取");
        when(damageMapper.selectById(9L)).thenReturn(d);
        PayProofDtos.SupplementRequest req = new PayProofDtos.SupplementRequest();
        req.setBizType(PayProofService.INV_DAMAGE);
        req.setBizId(9L);
        req.setFileIds(Collections.singletonList(76L));
        BizException e = assertThrows(BizException.class, () -> service.supplement(req));
        assertTrue(e.getMessage().contains("已收取"), e.getMessage());
    }

    @Test
    void 不认识的单据类型拒() {
        PayProofDtos.SupplementRequest req = new PayProofDtos.SupplementRequest();
        req.setBizType("contract");
        req.setBizId(1L);
        req.setFileIds(Collections.singletonList(1L));
        BizException e = assertThrows(BizException.class, () -> service.supplement(req));
        assertTrue(e.getMessage().contains("不支持"), e.getMessage());
    }
}
```

- [ ] **Step 4: 运行，确认失败**

Run: `cd backend && mvn -q test -Dtest=PayProofServiceTest`
Expected: 编译失败，提示 `PayProofService` 不存在。

- [ ] **Step 5: 实现 PayProofService**

```java
package top.aole.rent.modules.payproof.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import top.aole.rent.common.auth.CurrentUser;
import top.aole.rent.common.auth.DataScope;
import top.aole.rent.common.auth.UserContext;
import top.aole.rent.common.exception.BizException;
import top.aole.rent.modules.billing.domain.RentBill;
import top.aole.rent.modules.billing.mapper.RentBillMapper;
import top.aole.rent.modules.file.domain.FileObject;
import top.aole.rent.modules.file.mapper.FileObjectMapper;
import top.aole.rent.modules.inventory.domain.InvDamage;
import top.aole.rent.modules.inventory.mapper.InvDamageMapper;
import top.aole.rent.modules.payproof.domain.PayProof;
import top.aole.rent.modules.payproof.dto.PayProofDtos;
import top.aole.rent.modules.payproof.mapper.PayProofMapper;
import top.aole.rent.modules.purchase.domain.Payable;
import top.aole.rent.modules.purchase.mapper.PayableMapper;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 资金进出回单(10-07 纪要「付款凭证强制留存」):<b>先传回单再登记</b>。
 * 各资金动作在自己的事务里先调 {@link #bind},缺回单/文件不合规直接 400,原登记逻辑不执行。
 * 回单只增不删;撤销付款、红冲不动已挂的回单。
 */
@Service
@RequiredArgsConstructor
public class PayProofService {

    public static final String FILE_BIZ_TYPE = "pay_proof";
    public static final String PAYABLE = "payable";
    public static final String RENT_BILL = "rent_bill";
    public static final String INV_DAMAGE = "inv_damage";
    /** 能上传回单的角色:各资金动作经办角色的并集 */
    public static final Set<String> UPLOAD_ROLES = new HashSet<>(Arrays.asList("老板", "财务", "供应链"));

    private final PayProofMapper payProofMapper;
    private final FileObjectMapper fileObjectMapper;
    private final PayableMapper payableMapper;
    private final RentBillMapper rentBillMapper;
    private final InvDamageMapper damageMapper;

    /** 把回单挂到单据上。须在资金动作的同一事务里、写业务数据之前调用。 */
    @Transactional(rollbackFor = Exception.class)
    public void bind(String bizType, List<Long> bizIds, String action, List<Long> fileIds) {
        List<Long> files = fileIds == null ? Collections.emptyList()
                : fileIds.stream().filter(Objects::nonNull).distinct().collect(Collectors.toList());
        if (files.isEmpty()) {
            throw new BizException(400, "请先上传回单(银行回单或支付截图)再登记");
        }
        CurrentUser u = UserContext.require();
        for (Long fid : files) {
            FileObject fo = fileObjectMapper.selectById(fid);
            if (fo == null || Integer.valueOf(1).equals(fo.getIsDeleted())) {
                throw new BizException(400, "回单文件不存在: id=" + fid);
            }
            if (!FILE_BIZ_TYPE.equals(fo.getBizType())) {
                throw new BizException(400, "文件「" + fo.getFileName() + "」不是回单,请在登记弹窗里重新上传");
            }
            if (fo.getUploaderId() == null || !fo.getUploaderId().equals(u.getUserId())) {
                throw new BizException(400, "只能使用自己上传的回单:「" + fo.getFileName() + "」");
            }
        }
        for (Long bizId : bizIds) {
            for (Long fid : files) {
                Long n = payProofMapper.selectCount(new LambdaQueryWrapper<PayProof>()
                        .eq(PayProof::getFileId, fid)
                        .eq(PayProof::getBizType, bizType)
                        .eq(PayProof::getBizId, bizId));
                if (n != null && n > 0) {
                    continue;
                }
                PayProof p = new PayProof();
                p.setFileId(fid);
                p.setBizType(bizType);
                p.setBizId(bizId);
                p.setAction(action);
                p.setCreateByName(u.getUserName());
                payProofMapper.insert(p);
            }
        }
    }

    public List<PayProofDtos.ProofView> list(String bizType, Long bizId) {
        CurrentUser u = UserContext.require();
        if (!DataScope.canSeeCost(u.getRole())) {
            throw new BizException(403, "当前角色无权查看回单");
        }
        List<PayProof> rows = payProofMapper.selectList(new LambdaQueryWrapper<PayProof>()
                .eq(PayProof::getBizType, bizType)
                .eq(PayProof::getBizId, bizId)
                .orderByAsc(PayProof::getId));
        if (rows.isEmpty()) {
            return new ArrayList<>();
        }
        Map<Long, FileObject> files = fileObjectMapper.selectBatchIds(
                        rows.stream().map(PayProof::getFileId).distinct().collect(Collectors.toList()))
                .stream().collect(Collectors.toMap(FileObject::getId, Function.identity()));
        List<PayProofDtos.ProofView> out = new ArrayList<>();
        for (PayProof p : rows) {
            FileObject fo = files.get(p.getFileId());
            PayProofDtos.ProofView v = new PayProofDtos.ProofView();
            v.setProofId(p.getId());
            v.setFileId(p.getFileId());
            v.setFileName(fo == null ? "(文件已不存在)" : fo.getFileName());
            v.setContentType(fo == null ? null : fo.getContentType());
            v.setSize(fo == null ? null : fo.getFileSize());
            v.setAction(p.getAction());
            v.setCreateByName(p.getCreateByName());
            v.setCreateTime(p.getCreateTime());
            out.add(v);
        }
        return out;
    }

    /** 各单据挂了几张回单;没有回单的 id 不在结果里。 */
    public Map<Long, Integer> countMap(String bizType, Collection<Long> bizIds) {
        Map<Long, Integer> m = new HashMap<>();
        if (bizIds == null || bizIds.isEmpty()) {
            return m;
        }
        for (PayProof p : payProofMapper.selectList(new LambdaQueryWrapper<PayProof>()
                .eq(PayProof::getBizType, bizType)
                .in(PayProof::getBizId, bizIds))) {
            m.merge(p.getBizId(), 1, Integer::sum);
        }
        return m;
    }

    /** 历史单据补传:只对已付/已核销/已收取的单据开放,角色同原登记动作。 */
    @Transactional(rollbackFor = Exception.class)
    public void supplement(PayProofDtos.SupplementRequest req) {
        CurrentUser u = UserContext.require();
        String bt = req.getBizType();
        Long id = req.getBizId();
        if (PAYABLE.equals(bt)) {
            requireRole(u, "财务", "老板");
            Payable p = payableMapper.selectById(id);
            if (p == null) {
                throw new BizException(404, "应付不存在: id=" + id);
            }
            if (!"已付".equals(p.getStatus())) {
                throw new BizException(400, "只有已付的应付可以补传回单");
            }
        } else if (RENT_BILL.equals(bt)) {
            requireRole(u, "财务", "老板");
            RentBill b = rentBillMapper.selectById(id);
            if (b == null) {
                throw new BizException(404, "收租单不存在: id=" + id);
            }
            if (!"已核销".equals(b.getStatus())) {
                throw new BizException(400, "只有已核销的收租单可以补传回单");
            }
        } else if (INV_DAMAGE.equals(bt)) {
            requireRole(u, "老板", "财务", "供应链");
            InvDamage d = damageMapper.selectById(id);
            if (d == null) {
                throw new BizException(404, "损坏缺件记录不存在: id=" + id);
            }
            if (!"已收取".equals(d.getSettleStatus())) {
                throw new BizException(400, "只有已收取的赔偿可以补传回单");
            }
        } else {
            throw new BizException(400, "不支持的单据类型: " + bt);
        }
        bind(bt, Collections.singletonList(id), "补传", req.getFileIds());
    }

    private static void requireRole(CurrentUser u, String... roles) {
        if (!Arrays.asList(roles).contains(u.getRole())) {
            throw new BizException(403, "当前角色无权补传该类回单(需 " + String.join("/", roles) + ")");
        }
    }
}
```

- [ ] **Step 6: Controller**

```java
package top.aole.rent.modules.payproof.interfaces;

import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import top.aole.rent.common.result.R;
import top.aole.rent.modules.payproof.dto.PayProofDtos;
import top.aole.rent.modules.payproof.service.PayProofService;

import java.util.List;

@Api(tags = "资金回单")
@RestController
@RequestMapping("/rent/pay-proofs")
@RequiredArgsConstructor
public class PayProofController {

    private final PayProofService payProofService;

    @ApiOperation("某张单据的回单(bizType=payable/rent_bill/inv_damage)")
    @GetMapping
    public R<List<PayProofDtos.ProofView>> list(@RequestParam String bizType, @RequestParam Long bizId) {
        return R.ok(payProofService.list(bizType, bizId));
    }

    @ApiOperation("历史单据补传回单(已付/已核销/已收取)")
    @PostMapping
    public R<Void> supplement(@RequestBody PayProofDtos.SupplementRequest req) {
        payProofService.supplement(req);
        return R.ok();
    }
}
```

- [ ] **Step 7: FileStorageService 加 `pay_proof` 规则**

1. 顶部 import：`import top.aole.rent.modules.payproof.service.PayProofService;`
2. `static { ... }` 块末尾（照片循环之后）加：

```java
        // 资金回单:银行回单截图或 PDF(10-07 纪要,登记付款/核销前必传)
        MAX_BYTES.put(PayProofService.FILE_BIZ_TYPE, 10 * MB);
        ALLOWED_EXT.put(PayProofService.FILE_BIZ_TYPE, new HashSet<>(Arrays.asList("jpg", "jpeg", "png", "pdf")));
```

3. `upload()` 里 `if (CONTRACT.equals(bizType)) { ... }` 块之后加：

```java
        if (PayProofService.FILE_BIZ_TYPE.equals(bizType)
                && !PayProofService.UPLOAD_ROLES.contains(u.getRole())) {
            throw new BizException(403, "当前角色无权上传回单(需 老板/财务/供应链)");
        }
```

4. `authorize()` 末尾（成本类判断之后）加：

```java
        // 资金回单含银行账户信息:仅经营角色可见
        if (PayProofService.FILE_BIZ_TYPE.equals(fo.getBizType()) && !DataScope.canSeeCost(u.getRole())) {
            throw new BizException(403, "越权:回单仅经营角色可见");
        }
```

5. `upload()` 里的提示文案 `bizType 必填(contract/site_photo/import/asset_bom/supplier_inspection)` 末尾加 `/pay_proof`。

- [ ] **Step 8: 运行测试**

Run: `cd backend && mvn -q test -Dtest=PayProofServiceTest`
Expected: 全部 PASS。

- [ ] **Step 9: 提交**

```bash
git add backend/src/main/resources/db/migration/V122__pay_proof.sql backend/src/main/java/top/aole/rent/modules/payproof backend/src/main/java/top/aole/rent/modules/file/service/FileStorageService.java backend/src/test/java/top/aole/rent/modules/payproof
git commit -m "feat(payproof): 资金回单关联表与先传后登记校验(V122)" -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 2: 采购登记付款必须带回单

**Files:**
- Modify: `backend/src/main/java/top/aole/rent/modules/purchase/dto/PurchaseEditDtos.java`（`PayRequest`）
- Modify: `backend/src/main/java/top/aole/rent/modules/purchase/dto/PurchaseDetailResponse.java`（`PayableLine`）
- Modify: `backend/src/main/java/top/aole/rent/modules/purchase/service/PurchaseService.java`
- Test: `backend/src/test/java/top/aole/rent/modules/purchase/PurchaseEditTest.java`

**Interfaces:**
- Consumes: `PayProofService.bind(...)`、`countMap(...)`、`PAYABLE`
- Produces: `PayRequest.proofFileIds: List<Long>`；`PayableLine.proofCount: Integer`

- [ ] **Step 1: 改测试（先红）**

`PurchaseEditTest`：
1. 加字段 `private PayProofService payProofService;`；import `top.aole.rent.modules.payproof.service.PayProofService`、`static org.mockito.ArgumentMatchers.eq`、`static org.mockito.Mockito.doThrow`。
2. `setUp()` 里构造改为：

```java
        payProofService = mock(PayProofService.class);
        service = new PurchaseService(purchaseInMapper, purchaseItemMapper, payableMapper, contractMapper,
                mock(CustomerMapper.class), mock(SupplierMapper.class), assetMapper, assetService,
                paymentService, mock(VoucherService.class), mock(RuleConfigService.class),
                mock(AuditLogService.class), mock(RentCoverageService.class), mock(ContractPaymentService.class),
                payProofService);
```

3. 在「登记付款」区追加：

```java
    @Test
    void 登记付款先绑回单() {
        purchase("已下单", "2026-03-01", "2026-03-31", null);
        payable(1L, "首付", "65710.80", "待付", "2026-03-01", 0);
        PurchaseEditDtos.PayRequest req = new PurchaseEditDtos.PayRequest();
        req.setPaidAmount(new BigDecimal("65710.80"));
        req.setProofFileIds(Collections.singletonList(77L));
        service.payPayable(1L, req);
        verify(payProofService).bind(eq(PayProofService.PAYABLE), eq(Collections.singletonList(1L)),
                eq("付款"), eq(Collections.singletonList(77L)));
    }

    @Test
    void 回单校验失败不登记() {
        purchase("已下单", "2026-03-01", "2026-03-31", null);
        payable(1L, "首付", "65710.80", "待付", "2026-03-01", 0);
        doThrow(new BizException(400, "请先上传回单(银行回单或支付截图)再登记"))
                .when(payProofService).bind(any(), any(), any(), any());
        PurchaseEditDtos.PayRequest req = new PurchaseEditDtos.PayRequest();
        req.setPaidAmount(new BigDecimal("65710.80"));
        BizException e = assertThrows(BizException.class, () -> service.payPayable(1L, req));
        assertTrue(e.getMessage().contains("请先上传回单"), e.getMessage());
        verify(payableMapper, never()).updateById(any(Payable.class));
        verify(payableMapper, never()).insert(any(Payable.class));
    }
```

- [ ] **Step 2: 运行，确认失败**

Run: `cd backend && mvn -q test -Dtest=PurchaseEditTest`
Expected: 编译失败（构造器参数个数不对、`setProofFileIds` 不存在）。

- [ ] **Step 3: 实现**

1. `PurchaseEditDtos.PayRequest` 在 `remark` 后加（文件顶部补 `import java.util.List;`，已有则跳过）：

```java
        @ApiModelProperty("付款回单文件 id(先用 bizType=pay_proof 上传);必填")
        private List<Long> proofFileIds;
```

2. `PurchaseDetailResponse.PayableLine` 在 `remark` 后加：

```java
        /** 已挂回单数;0 且已付 = 历史无回单 */
        private Integer proofCount;
```

3. `PurchaseService`：在 `contractPaymentService` 字段之后加 `private final top.aole.rent.modules.payproof.service.PayProofService payProofService;`。
4. `payPayable` 里，在 `BigDecimal rest = due.subtract(paid);` 之后、`if (rest.signum() > 0)` 之前插入：

```java
        // 10-07 纪要:付款必须先挂回单;缺回单在这里 400,下面的写库都不会发生
        payProofService.bind(top.aole.rent.modules.payproof.service.PayProofService.PAYABLE,
                java.util.Collections.singletonList(payableId), "付款", req.getProofFileIds());
```

5. 详情组装：把 `for (Payable pay : payablesOf(id)) {` 替换为

```java
        List<Payable> payRows = payablesOf(id);
        java.util.Map<Long, Integer> proofCounts = payProofService.countMap(
                top.aole.rent.modules.payproof.service.PayProofService.PAYABLE,
                payRows.stream().map(Payable::getId).collect(java.util.stream.Collectors.toList()));
        for (Payable pay : payRows) {
```

并在循环里 `pl.setRemark(pay.getRemark());` 之后加 `pl.setProofCount(proofCounts.getOrDefault(pay.getId(), 0));`。

- [ ] **Step 4: 运行测试**

Run: `cd backend && mvn -q test -Dtest=PurchaseEditTest`
Expected: 全部 PASS（原有付款用例 bind 被 mock，不受影响）。

- [ ] **Step 5: 提交**

```bash
git add backend/src/main/java/top/aole/rent/modules/purchase backend/src/test/java/top/aole/rent/modules/purchase/PurchaseEditTest.java
git commit -m "feat(purchase): 登记付款必须先挂回单,应付行显示回单数" -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 3: 收租核销/批量/退款/逾期还款必须带回单 + 权限收紧 + 稽核

**Files:**
- Modify: `backend/src/main/java/top/aole/rent/modules/billing/dto/BillDtos.java`
- Modify: `backend/src/main/java/top/aole/rent/modules/billing/service/RentBillService.java`
- Modify: `backend/src/main/java/top/aole/rent/modules/billing/service/OverdueService.java`
- Modify: `backend/src/main/java/top/aole/rent/modules/billing/interfaces/RentBillController.java`
- Modify: `backend/src/main/java/top/aole/rent/modules/billing/interfaces/OverdueController.java`
- Test: `backend/src/test/java/top/aole/rent/modules/billing/RentBillProofTest.java`

**Interfaces:**
- Consumes: `PayProofService.bind/countMap/RENT_BILL`
- Produces:
  - `MatchRequest.proofFileIds`、`BatchMatchRequest.proofFileIds`、`RefundRequest.proofFileIds`（均 `List<Long>`）
  - `BillItem.proofCount: Integer`
  - `CashCheck.matchedNoProof: int`、`matchedNoProofDetail: List<String>`
  - `RentBillService.match(Long, MatchRequest, String action)`、`batchMatch(List<Long> ids, List<Long> proofFileIds)`（旧的单参 `batchMatch(List<Long>)` 删除）

调用方只有三处（已 grep 确认）：`RentBillController.match/batchMatch`、`OverdueService.repay`。

- [ ] **Step 1: 写失败的测试**

`RentBillProofTest.java`：

```java
package top.aole.rent.modules.billing;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import top.aole.rent.common.audit.AuditLogService;
import top.aole.rent.common.auth.CurrentUser;
import top.aole.rent.common.auth.UserContext;
import top.aole.rent.common.exception.BizException;
import top.aole.rent.modules.billing.domain.AccountingPeriod;
import top.aole.rent.modules.billing.domain.OverdueCase;
import top.aole.rent.modules.billing.domain.RentBill;
import top.aole.rent.modules.billing.dto.BillDtos;
import top.aole.rent.modules.billing.mapper.AccountingPeriodMapper;
import top.aole.rent.modules.billing.mapper.OverdueCaseMapper;
import top.aole.rent.modules.billing.mapper.RentBillMapper;
import top.aole.rent.modules.billing.service.RentBillService;
import top.aole.rent.modules.contract.domain.RentSchedule;
import top.aole.rent.modules.contract.mapper.ContractMapper;
import top.aole.rent.modules.contract.mapper.RentScheduleMapper;
import top.aole.rent.modules.customer.mapper.CustomerMapper;
import top.aole.rent.modules.finance.service.VoucherService;
import top.aole.rent.modules.payproof.service.PayProofService;
import top.aole.rent.modules.rule.service.RuleConfigService;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 收租核销/批量核销/退款都要先挂回单;稽核统计已核销无回单。 */
class RentBillProofTest {

    private RentBillMapper billMapper;
    private PayProofService payProofService;
    private RentBillService service;

    @BeforeAll
    static void initLambdaCache() {
        MapperBuilderAssistant a = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        for (Class<?> c : Arrays.asList(RentBill.class, OverdueCase.class, AccountingPeriod.class, RentSchedule.class)) {
            TableInfoHelper.initTableInfo(a, c);
        }
    }

    @BeforeEach
    void setUp() {
        billMapper = mock(RentBillMapper.class);
        payProofService = mock(PayProofService.class);
        service = new RentBillService(billMapper, mock(RentScheduleMapper.class), mock(ContractMapper.class),
                mock(CustomerMapper.class), mock(AccountingPeriodMapper.class), mock(OverdueCaseMapper.class),
                mock(RuleConfigService.class), mock(AuditLogService.class), mock(VoucherService.class), payProofService);
        UserContext.set(new CurrentUser(1005L, "财务", "财务", null));
    }

    @AfterEach
    void tearDown() {
        UserContext.clear();
    }

    private RentBill bill(long id, String status) {
        RentBill b = new RentBill();
        b.setId(id);
        b.setBillNo("SZ-" + id);
        b.setContractId(60L);
        b.setPeriodNo(1);
        b.setAmount(new BigDecimal("6720"));
        b.setReceivedAmount(BigDecimal.ZERO);
        b.setStatus(status);
        b.setBillKind("正常");
        when(billMapper.selectById(id)).thenReturn(b);
        return b;
    }

    @Test
    void 核销先绑回单() {
        bill(1L, "待收");
        BillDtos.MatchRequest req = new BillDtos.MatchRequest();
        req.setProofFileIds(Collections.singletonList(80L));
        service.match(1L, req);
        verify(payProofService).bind(eq(PayProofService.RENT_BILL), eq(Collections.singletonList(1L)),
                eq("核销"), eq(Collections.singletonList(80L)));
    }

    @Test
    void 回单不过不核销() {
        bill(1L, "待收");
        doThrow(new BizException(400, "请先上传回单(银行回单或支付截图)再登记"))
                .when(payProofService).bind(any(), any(), any(), any());
        assertThrows(BizException.class, () -> service.match(1L, new BillDtos.MatchRequest()));
        verify(billMapper, never()).updateById(any(RentBill.class));
    }

    @Test
    void 批量核销一张回单挂全部() {
        bill(1L, "待收");
        bill(2L, "逾期");
        service.batchMatch(Arrays.asList(1L, 2L), Collections.singletonList(81L));
        verify(payProofService).bind(eq(PayProofService.RENT_BILL), eq(Arrays.asList(1L, 2L)),
                eq("批量核销"), eq(Collections.singletonList(81L)));
    }

    @Test
    void 稽核统计已核销无回单() {
        RentBill a = bill(1L, "已核销");
        RentBill b = bill(2L, "已核销");
        when(billMapper.selectList(any())).thenReturn(Arrays.asList(a, b));
        Map<Long, Integer> counts = new HashMap<>();
        counts.put(1L, 1);
        when(payProofService.countMap(eq(PayProofService.RENT_BILL), any())).thenReturn(counts);
        BillDtos.CashCheck cc = service.cashCheck();
        assertEquals(1, cc.getMatchedNoProof());
        assertEquals(1, cc.getMatchedNoProofDetail().size());
    }
}
```

（`cashCheck()` 里所有 `rentBillMapper.selectList` 都返回这两张单；`rentScheduleMapper` 是空 mock。只断言新加的两个字段。）

- [ ] **Step 2: 运行，确认失败**

Run: `cd backend && mvn -q test -Dtest=RentBillProofTest`
Expected: 编译失败。

- [ ] **Step 3: DTO**

`BillDtos`：
- `BillItem` 在 `remark` 后加 `private Integer proofCount;   // 已挂回单数`
- `MatchRequest` 加 `private List<Long> proofFileIds;   // 收款回单(必填)`
- `BatchMatchRequest` 加 `private List<Long> proofFileIds; // 一张回单挂全部勾选单`
- `RefundRequest` 加 `private List<Long> proofFileIds;   // 退款回单(必填)`
- `CashCheck` 在 `matchedNoVoucher` 后加 `private int matchedNoProof;       // 已核销无回单(10-07 纪要)`，在 `matchedNoVoucherDetail` 后加 `private List<String> matchedNoProofDetail;`

- [ ] **Step 4: RentBillService**

1. import `top.aole.rent.modules.payproof.service.PayProofService;`；字段：`voucherService` 之后加 `private final PayProofService payProofService;`。
2. 把现有 `@Transactional public BillDtos.BillItem match(Long id, BillDtos.MatchRequest req)` 改为 `private BillDtos.BillItem matchCore(Long id, BillDtos.MatchRequest req)`（去掉注解，方法体不变），在它上方新增：

```java
    @Transactional
    public BillDtos.BillItem match(Long id, BillDtos.MatchRequest req) {
        return match(id, req, "核销");
    }

    /** 核销前先挂收款回单(10-07 纪要);action 区分 核销/还款。 */
    @Transactional
    public BillDtos.BillItem match(Long id, BillDtos.MatchRequest req, String action) {
        payProofService.bind(PayProofService.RENT_BILL, java.util.Collections.singletonList(id), action,
                req == null ? null : req.getProofFileIds());
        return matchCore(id, req);
    }
```

3. `batchMatch` 整体替换为：

```java
    @Transactional
    public List<BillDtos.BillItem> batchMatch(List<Long> ids, List<Long> proofFileIds) {
        if (ids == null || ids.isEmpty()) {
            throw new BizException(400, "批量核销 ids 不能为空");
        }
        // 一张银行回单对应这一批收租单
        payProofService.bind(PayProofService.RENT_BILL, ids, "批量核销", proofFileIds);
        List<BillDtos.BillItem> out = new ArrayList<>();
        for (Long id : ids) {
            out.add(matchCore(id, null));   // 各单按应收全额核销
        }
        return out;
    }
```

4. `refund`：`rentBillMapper.insert(rf);` 之后加：

```java
        // 退款回单挂在新生成的退款单上;缺回单整笔回滚
        payProofService.bind(PayProofService.RENT_BILL, java.util.Collections.singletonList(rf.getId()), "退款",
                req == null ? null : req.getProofFileIds());
```

5. `list(...)`：`for (RentBill b : all) { items.add(toItem(b)); }` 之后加：

```java
        java.util.Map<Long, Integer> proofCounts = payProofService.countMap(PayProofService.RENT_BILL,
                all.stream().map(RentBill::getId).collect(java.util.stream.Collectors.toList()));
        for (BillDtos.BillItem it : items) {
            it.setProofCount(proofCounts.getOrDefault(it.getId(), 0));
        }
```

6. `cashCheck()`：在 ③ 之后、`cc.setBillNotGenerated(...)` 之前加：

```java
        // ④ 已核销无回单(10-07 纪要):正常/罚息单核销了却没挂收款回单(多为上线前历史单,可补传)
        List<String> noProof = new ArrayList<>();
        List<RentBill> matchedAll = rentBillMapper.selectList(new LambdaQueryWrapper<RentBill>()
                .eq(RentBill::getStatus, "已核销")
                .in(RentBill::getBillKind, "正常", "罚息"));
        java.util.Map<Long, Integer> proofCounts = payProofService.countMap(PayProofService.RENT_BILL,
                matchedAll.stream().map(RentBill::getId).collect(java.util.stream.Collectors.toList()));
        for (RentBill b : matchedAll) {
            if (proofCounts.getOrDefault(b.getId(), 0) == 0) {
                noProof.add(b.getBillNo() + " 已核销 " + (b.getAccountPeriod() == null ? "" : b.getAccountPeriod()) + " 无收款回单");
            }
        }
```

设值处加 `cc.setMatchedNoProof(noProof.size());`、`cc.setMatchedNoProofDetail(noProof);`，`setAllClear(...)` 条件末尾追加 `&& noProof.isEmpty()`。

- [ ] **Step 5: OverdueService.repay**

`rentBillService.match(oc.getRentBillId(), req);` 改为 `rentBillService.match(oc.getRentBillId(), req, "还款");`。

- [ ] **Step 6: 控制器权限**

`RentBillController`：
- `match` 上加 `@RequireRole(value = {"财务", "老板"}, action = "收租核销", targetType = "rent_bill")`
- `batchMatch` 上加 `@RequireRole(value = {"财务", "老板"}, action = "收租批量核销", targetType = "rent_bill")`，方法体改为 `return R.ok(rentBillService.batchMatch(req.getIds(), req.getProofFileIds()));`

`OverdueController.repay` 上加 `@RequireRole(value = {"财务", "老板"}, action = "逾期还款恢复", targetType = "overdue_case")`（`RequireRole` 已 import）。

- [ ] **Step 7: 运行测试**

Run: `cd backend && mvn -q test -Dtest=RentBillProofTest`
Expected: PASS。

- [ ] **Step 8: 提交**

```bash
git add backend/src/main/java/top/aole/rent/modules/billing backend/src/test/java/top/aole/rent/modules/billing/RentBillProofTest.java
git commit -m "feat(billing): 核销/批量/退款/还款必须先挂回单,核销与还款限财务老板,稽核加已核销无回单" -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 4: 赔偿收取带回单、减免必填原因；提醒去掉「赔偿待收」

**Files:**
- Modify: `backend/src/main/java/top/aole/rent/modules/inventory/dto/InvDtos.java`
- Modify: `backend/src/main/java/top/aole/rent/modules/inventory/service/InvService.java`
- Test: `backend/src/test/java/top/aole/rent/modules/inventory/InvServiceTest.java`（以及若 `MaintenanceTargetTest` 里 `new InvService(...)`，同样补参数）

**Interfaces:**
- Consumes: `PayProofService.bind/countMap/INV_DAMAGE`
- Produces: `SettleRequest.proofFileIds`、`DamageView.proofCount`

- [ ] **Step 1: 改测试（先红）**

`InvServiceTest`：
1. 字段 `private PayProofService payProofService;`；import `top.aole.rent.modules.payproof.service.PayProofService`、`static org.mockito.ArgumentMatchers.eq`。
2. 构造改为：

```java
        payProofService = mock(PayProofService.class);
        service = new InvService(itemMapper, rentalMapper, movementMapper, damageMapper, priceMapper,
                mock(InvCompanyMapper.class), customerMapper, contractMapper, fileObjectMapper, mock(AuditLogService.class),
                payProofService);
```

3. `overviewCountsAndReminders` 最后三行（注释 + 两个 assert + 最后一个 assertEquals）替换为：

```java
        // danger 在前:设备逾期;再 warning:需要维修/即将归还/合同到期。赔偿待收已移到「服务」模块,不再进资产提醒
        assertEquals("设备逾期", types.get(0));
        assertTrue(types.containsAll(Arrays.asList("需要维修", "即将归还", "合同到期")), types.toString());
        assertTrue(!types.contains("赔偿待收"), types.toString());
```

4. 追加：

```java
    private InvDamage damage(String status) {
        InvDamage d = new InvDamage();
        d.setId(9L);
        d.setItemId(1L);
        d.setPartName("格口");
        d.setAmount(new BigDecimal("50"));
        d.setSettleStatus(status);
        when(damageMapper.selectById(9L)).thenReturn(d);
        return d;
    }

    @Test
    void 赔偿已收取要先挂回单() {
        damage("待收取");
        InvDtos.SettleRequest req = new InvDtos.SettleRequest();
        req.setSettleStatus("已收取");
        req.setProofFileIds(Collections.singletonList(90L));
        service.settleDamage(9L, req);
        verify(payProofService).bind(eq(PayProofService.INV_DAMAGE), eq(Collections.singletonList(9L)),
                eq("赔偿收取"), eq(Collections.singletonList(90L)));
    }

    @Test
    void 赔偿减免必须写原因() {
        damage("待收取");
        InvDtos.SettleRequest req = new InvDtos.SettleRequest();
        req.setSettleStatus("已减免");
        BizException e = assertThrows(BizException.class, () -> service.settleDamage(9L, req));
        assertTrue(e.getMessage().contains("原因"), e.getMessage());
        verify(damageMapper, never()).update(any(), any());
    }

    @Test
    void 改回待收取不要回单() {
        damage("已减免");
        InvDtos.SettleRequest req = new InvDtos.SettleRequest();
        req.setSettleStatus("待收取");
        service.settleDamage(9L, req);
        verify(payProofService, never()).bind(any(), any(), any(), any());
    }
```

- [ ] **Step 2: 运行，确认失败**

Run: `cd backend && mvn -q test -Dtest=InvServiceTest`
Expected: 编译失败。

- [ ] **Step 3: 实现**

1. `InvDtos.SettleRequest` 加 `private java.util.List<Long> proofFileIds;   // 已收取时必填:收款回单`；`DamageView` 在 `remark` 后加 `private Integer proofCount;`。
2. `InvService` 在 `auditLogService` 字段后加 `private final top.aole.rent.modules.payproof.service.PayProofService payProofService;`。
3. `settleDamage`：状态合法性校验之后、`damageMapper.update(...)` 之前插入：

```java
        if ("已收取".equals(st) && !st.equals(d.getSettleStatus())) {
            // 10-07 纪要:收钱要有回单
            payProofService.bind(top.aole.rent.modules.payproof.service.PayProofService.INV_DAMAGE,
                    java.util.Collections.singletonList(id), "赔偿收取", req.getProofFileIds());
        }
        if ("已减免".equals(st) && trimToNull(req.getRemark()) == null) {
            throw new BizException(400, "减免赔偿必须写明原因");
        }
```

4. `toDamageViews`：在 `for` 循环之前加

```java
        java.util.Map<Long, Integer> proofCounts = payProofService.countMap(
                top.aole.rent.modules.payproof.service.PayProofService.INV_DAMAGE,
                rows.stream().map(InvDamage::getId).collect(Collectors.toList()));
```

循环里 `v.setCreateTime(d.getCreateTime());` 之后加 `v.setProofCount(proofCounts.getOrDefault(d.getId(), 0));`。

5. `overview(LocalDate today)`：删除 `if (!pending.isEmpty()) { o.getReminders().add(reminder("赔偿待收", ...)); }` 整个 if 块（保留上面计算 `pendingTotal` 和 `setPendingCompensation` 的行）。

- [ ] **Step 4: 运行测试**

Run: `cd backend && mvn -q test -Dtest=InvServiceTest+MaintenanceTargetTest`
Expected: PASS。

- [ ] **Step 5: 提交**

```bash
git add backend/src/main/java/top/aole/rent/modules/inventory backend/src/test/java/top/aole/rent/modules
git commit -m "feat(inventory): 赔偿收取须挂回单、减免须写原因;赔偿待收提醒移出资产管理" -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 5: 总资产接口（含/不含折旧）+ Excel

**Files:**
- Create: `backend/src/main/java/top/aole/rent/modules/asset/dto/AssetValueDtos.java`
- Create: `backend/src/main/java/top/aole/rent/modules/asset/service/AssetValueService.java`
- Modify: `backend/src/main/java/top/aole/rent/modules/asset/interfaces/AssetController.java`
- Test: `backend/src/test/java/top/aole/rent/modules/asset/AssetValueServiceTest.java`

**Interfaces:**
- Produces: `GET /rent/assets/total-value` → `AssetValueDtos.TotalValue`；`GET /rent/assets/total-value/export` → xlsx。分组项字段统一叫 `name`（spec 示例中的 `category`/`status` 键以此为准）。

- [ ] **Step 1: DTO**

```java
package top.aole.rent.modules.asset.dto;

import lombok.Data;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/** 总资产(10-07 纪要):不含折旧 = 集采价合计;含折旧 = 集采价 − 经营口径累计折旧。 */
public class AssetValueDtos {

    @Data
    public static class Group {
        private String name;
        private int count;
        private BigDecimal originalTotal = BigDecimal.ZERO;
        private BigDecimal accumulatedDepreciation = BigDecimal.ZERO;
        private BigDecimal netTotal = BigDecimal.ZERO;
    }

    @Data
    public static class TotalValue {
        /** 折旧计提到哪个月(YYYY-MM);从未计提为 null */
        private String depreciatedThrough;
        private int assetCount;
        private BigDecimal originalTotal = BigDecimal.ZERO;
        private BigDecimal accumulatedDepreciation = BigDecimal.ZERO;
        private BigDecimal netTotal = BigDecimal.ZERO;
        /** 缺集采价、未计入合计的台数 */
        private int missingPriceCount;
        private List<Group> byCategory = new ArrayList<>();
        private List<Group> byStatus = new ArrayList<>();
    }
}
```

- [ ] **Step 2: 写失败的测试**

```java
package top.aole.rent.modules.asset;

import org.junit.jupiter.api.Test;
import top.aole.rent.modules.asset.domain.Asset;
import top.aole.rent.modules.asset.domain.AssetDepreciationLine;
import top.aole.rent.modules.asset.dto.AssetValueDtos;
import top.aole.rent.modules.asset.service.AssetValueService;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class AssetValueServiceTest {

    private static Asset asset(long id, String category, String status, String price) {
        Asset a = new Asset();
        a.setId(id);
        a.setCategory(category);
        a.setStatus(status);
        a.setPurchasePrice(price == null ? null : new BigDecimal(price));
        return a;
    }

    private static AssetDepreciationLine line(long assetId, String period, String amount) {
        AssetDepreciationLine l = new AssetDepreciationLine();
        l.setAssetId(assetId);
        l.setPeriod(period);
        l.setDeprAmount(new BigDecimal(amount));
        return l;
    }

    @Test
    void 含与不含折旧() {
        AssetValueDtos.TotalValue t = AssetValueService.compute(
                Arrays.asList(asset(1, "播种墙", "在租", "180000"), asset(2, "货架", "投放", "20000")),
                Arrays.asList(line(1, "2026-08", "4500"), line(1, "2026-09", "4500"), line(2, "2026-09", "300")));
        assertEquals(2, t.getAssetCount());
        assertEquals(new BigDecimal("200000.00"), t.getOriginalTotal());
        assertEquals(new BigDecimal("9300.00"), t.getAccumulatedDepreciation());
        assertEquals(new BigDecimal("190700.00"), t.getNetTotal());
        assertEquals("2026-09", t.getDepreciatedThrough());
        assertEquals(2, t.getByCategory().size());
        assertEquals("播种墙", t.getByCategory().get(0).getName());
        assertEquals(new BigDecimal("171000.00"), t.getByCategory().get(0).getNetTotal());
    }

    @Test
    void 缺集采价不进合计() {
        AssetValueDtos.TotalValue t = AssetValueService.compute(
                Arrays.asList(asset(1, "货架", "在租", null), asset(2, "货架", "在租", "1000")),
                Collections.emptyList());
        assertEquals(1, t.getAssetCount());
        assertEquals(1, t.getMissingPriceCount());
        assertEquals(new BigDecimal("1000.00"), t.getOriginalTotal());
    }

    @Test
    void 没有折旧行净值等于原值() {
        AssetValueDtos.TotalValue t = AssetValueService.compute(
                Collections.singletonList(asset(1, "阁楼", "采购", "5000")), Collections.emptyList());
        assertEquals(t.getOriginalTotal(), t.getNetTotal());
        assertNull(t.getDepreciatedThrough());
    }

    @Test
    void 按状态固定顺序且隐藏空组() {
        AssetValueDtos.TotalValue t = AssetValueService.compute(
                Arrays.asList(asset(1, "货架", "收回待处置", "1"), asset(2, "货架", "采购", "1"), asset(3, "货架", "在租", "1")),
                Collections.emptyList());
        assertEquals(3, t.getByStatus().size());
        assertEquals("采购", t.getByStatus().get(0).getName());
        assertEquals("在租", t.getByStatus().get(1).getName());
        assertEquals("收回待处置", t.getByStatus().get(2).getName());
    }
}
```

（终态排除由 `totalValue()` 的查询条件 `in(status, COUNTED)` 保证；`compute` 只做汇总。）

- [ ] **Step 3: 运行，确认失败**

Run: `cd backend && mvn -q test -Dtest=AssetValueServiceTest`
Expected: 编译失败。

- [ ] **Step 4: 实现 AssetValueService**

```java
package top.aole.rent.modules.asset.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Service;
import top.aole.rent.common.exception.BizException;
import top.aole.rent.modules.asset.domain.Asset;
import top.aole.rent.modules.asset.domain.AssetDepreciationLine;
import top.aole.rent.modules.asset.dto.AssetValueDtos;
import top.aole.rent.modules.asset.mapper.AssetDepreciationLineMapper;
import top.aole.rent.modules.asset.mapper.AssetMapper;

import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 总资产(10-07 纪要):设备台账在账设备的原值(集采价)与净值(扣经营口径累计折旧)。
 * 已转让/报废不计;缺集采价的设备不混进合计,单独计数。仓库实物(资产管理)无单价,不在此统计。
 */
@Service
@RequiredArgsConstructor
public class AssetValueService {

    /** 计入总资产的状态,也是「按状态」分组的展示顺序 */
    public static final List<String> COUNTED = Arrays.asList("采购", "投放", "在租", "待转让", "收回待处置");
    private static final String BOOK = "ops";

    private final AssetMapper assetMapper;
    private final AssetDepreciationLineMapper lineMapper;

    public AssetValueDtos.TotalValue totalValue() {
        List<Asset> assets = assetMapper.selectList(new LambdaQueryWrapper<Asset>().in(Asset::getStatus, COUNTED));
        List<AssetDepreciationLine> lines = assets.isEmpty() ? Collections.emptyList()
                : lineMapper.selectList(new LambdaQueryWrapper<AssetDepreciationLine>()
                .eq(AssetDepreciationLine::getBook, BOOK)
                .in(AssetDepreciationLine::getAssetId, assets.stream().map(Asset::getId).collect(Collectors.toList())));
        return compute(assets, lines);
    }

    public static AssetValueDtos.TotalValue compute(List<Asset> assets, List<AssetDepreciationLine> lines) {
        Map<Long, BigDecimal> accum = new HashMap<>();
        String through = null;
        for (AssetDepreciationLine l : lines) {
            accum.merge(l.getAssetId(), nz(l.getDeprAmount()), BigDecimal::add);
            if (l.getPeriod() != null && (through == null || l.getPeriod().compareTo(through) > 0)) {
                through = l.getPeriod();
            }
        }
        AssetValueDtos.TotalValue t = new AssetValueDtos.TotalValue();
        t.setDepreciatedThrough(through);
        Map<String, AssetValueDtos.Group> byCat = new LinkedHashMap<>();
        Map<String, AssetValueDtos.Group> byStatus = new LinkedHashMap<>();
        for (String s : COUNTED) {
            byStatus.put(s, group(s));
        }
        for (Asset a : assets) {
            if (a.getPurchasePrice() == null) {
                t.setMissingPriceCount(t.getMissingPriceCount() + 1);
                continue;
            }
            BigDecimal orig = a.getPurchasePrice();
            BigDecimal dep = accum.getOrDefault(a.getId(), BigDecimal.ZERO);
            t.setAssetCount(t.getAssetCount() + 1);
            t.setOriginalTotal(t.getOriginalTotal().add(orig));
            t.setAccumulatedDepreciation(t.getAccumulatedDepreciation().add(dep));
            String cat = a.getCategory() == null || a.getCategory().isEmpty() ? "未分类" : a.getCategory();
            add(byCat.computeIfAbsent(cat, AssetValueService::group), orig, dep);
            add(byStatus.computeIfAbsent(a.getStatus(), AssetValueService::group), orig, dep);
        }
        t.setOriginalTotal(scale(t.getOriginalTotal()));
        t.setAccumulatedDepreciation(scale(t.getAccumulatedDepreciation()));
        t.setNetTotal(scale(t.getOriginalTotal().subtract(t.getAccumulatedDepreciation())));
        t.setByCategory(finish(byCat.values()));
        t.setByStatus(finish(byStatus.values()).stream().filter(g -> g.getCount() > 0).collect(Collectors.toList()));
        return t;
    }

    public byte[] exportExcel() {
        AssetValueDtos.TotalValue t = totalValue();
        try (Workbook wb = new XSSFWorkbook(); ByteArrayOutputStream bos = new ByteArrayOutputStream()) {
            sheet(wb, "按品类", "品类", t.getByCategory(), t);
            sheet(wb, "按状态", "状态", t.getByStatus(), t);
            wb.write(bos);
            return bos.toByteArray();
        } catch (Exception e) {
            throw new BizException(500, "总资产导出失败:" + e.getMessage());
        }
    }

    private static void sheet(Workbook wb, String title, String firstHead, List<AssetValueDtos.Group> groups,
                              AssetValueDtos.TotalValue t) {
        Sheet sh = wb.createSheet(title);
        String[] heads = {firstHead, "台数", "原值(不含折旧)", "累计折旧", "净值(含折旧)"};
        Row hr = sh.createRow(0);
        for (int i = 0; i < heads.length; i++) {
            hr.createCell(i).setCellValue(heads[i]);
            // 固定列宽(不用 autoSizeColumn:服务器镜像缺字体会抛异常,与客户导出同口径)
            sh.setColumnWidth(i, (i == 0 ? 14 : 18) * 256);
        }
        int r = 1;
        for (AssetValueDtos.Group g : groups) {
            r = row(sh, r, g.getName(), g.getCount(), g.getOriginalTotal(), g.getAccumulatedDepreciation(), g.getNetTotal());
        }
        r = row(sh, r, "合计", t.getAssetCount(), t.getOriginalTotal(), t.getAccumulatedDepreciation(), t.getNetTotal());
        Row note = sh.createRow(r + 1);
        note.createCell(0).setCellValue("折旧计提至 " + (t.getDepreciatedThrough() == null ? "(尚未计提)" : t.getDepreciatedThrough())
                + ";缺集采价未计入 " + t.getMissingPriceCount() + " 台;已转让/报废不计");
    }

    private static int row(Sheet sh, int r, String name, int count, BigDecimal orig, BigDecimal dep, BigDecimal net) {
        Row row = sh.createRow(r);
        row.createCell(0).setCellValue(name);
        row.createCell(1).setCellValue(count);
        row.createCell(2).setCellValue(orig.doubleValue());
        row.createCell(3).setCellValue(dep.doubleValue());
        row.createCell(4).setCellValue(net.doubleValue());
        return r + 1;
    }

    private static AssetValueDtos.Group group(String name) {
        AssetValueDtos.Group g = new AssetValueDtos.Group();
        g.setName(name);
        return g;
    }

    private static void add(AssetValueDtos.Group g, BigDecimal orig, BigDecimal dep) {
        g.setCount(g.getCount() + 1);
        g.setOriginalTotal(g.getOriginalTotal().add(orig));
        g.setAccumulatedDepreciation(g.getAccumulatedDepreciation().add(dep));
    }

    private static List<AssetValueDtos.Group> finish(Collection<AssetValueDtos.Group> gs) {
        List<AssetValueDtos.Group> out = new ArrayList<>();
        for (AssetValueDtos.Group g : gs) {
            g.setOriginalTotal(scale(g.getOriginalTotal()));
            g.setAccumulatedDepreciation(scale(g.getAccumulatedDepreciation()));
            g.setNetTotal(scale(g.getOriginalTotal().subtract(g.getAccumulatedDepreciation())));
            out.add(g);
        }
        return out;
    }

    private static BigDecimal nz(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }

    private static BigDecimal scale(BigDecimal v) {
        return v.setScale(2, RoundingMode.HALF_UP);
    }
}
```

- [ ] **Step 5: Controller**

`AssetController`：在 `bomExcelService` 字段之后加 `private final top.aole.rent.modules.asset.service.AssetValueService assetValueService;`；在 `list` 方法之后、`@GetMapping("/{id}")` 之前加：

```java
    @ApiOperation("总资产:不含折旧(集采价合计)/含折旧(扣经营口径累计折旧),按品类/状态分组")
    @RequireRole(value = {"老板", "财务"}, action = "查看总资产", targetType = "asset")
    @GetMapping("/total-value")
    public R<top.aole.rent.modules.asset.dto.AssetValueDtos.TotalValue> totalValue() {
        return R.ok(assetValueService.totalValue());
    }

    @ApiOperation("总资产导出 Excel(按品类/按状态两张表)")
    @RequireRole(value = {"老板", "财务"}, action = "导出总资产", targetType = "asset")
    @GetMapping("/total-value/export")
    public ResponseEntity<ByteArrayResource> exportTotalValue() {
        byte[] data = assetValueService.exportExcel();
        String fileName = "总资产-" + LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE) + ".xlsx";
        ContentDisposition cd = ContentDisposition.attachment().filename(fileName, StandardCharsets.UTF_8).build();
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, cd.toString())
                .contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .contentLength(data.length)
                .body(new ByteArrayResource(data));
    }
```

（`ResponseEntity`、`ByteArrayResource`、`ContentDisposition`、`HttpHeaders`、`MediaType`、`StandardCharsets`、`LocalDate`、`DateTimeFormatter`、`RequireRole` 在该文件已 import。）

- [ ] **Step 6: 运行测试**

Run: `cd backend && mvn -q test -Dtest=AssetValueServiceTest`
Expected: PASS。

- [ ] **Step 7: 全量后端测试**

Run: `cd backend && mvn -q test`
Expected: BUILD SUCCESS。若有其它测试 `new` 了 Task 2/3/4 改过构造器的服务，同样在末尾补 `mock(PayProofService.class)`。

- [ ] **Step 8: 提交**

```bash
git add backend/src/main/java/top/aole/rent/modules/asset backend/src/test/java/top/aole/rent/modules/asset/AssetValueServiceTest.java
git commit -m "feat(asset): 总资产接口与导出,含/不含折旧两版" -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 6: 前端回单组件与接口

**Files:**
- Create: `frontend/src/api/payProof.ts`
- Create: `frontend/src/components/payproof/ProofUploader.vue`
- Create: `frontend/src/components/payproof/ProofViewer.vue`

**Interfaces:**
- Produces:
  - `uploadProofFile(file: File): Promise<UploadedProof>`、`fetchProofs(bizType, bizId)`、`supplementProofs(bizType, bizId, fileIds)`
  - `<ProofUploader v-model="ids" />`（`number[]`）
  - `<ProofViewer v-model="visible" :biz-type :biz-id :title :can-supplement @changed />`

先核对 Vue 版本是否支持 `defineModel`（≥3.4）：`grep '"vue"' frontend/package.json`。低于 3.4 时，两个组件改用 `props.modelValue` + `emit('update:modelValue', v)`。

- [ ] **Step 1: `api/payProof.ts`**

```ts
import request from '@/utils/request'

/** 资金回单(10-07 纪要):登记付款/核销/退款/还款/赔偿收取前必须先上传 */
export type ProofBizType = 'payable' | 'rent_bill' | 'inv_damage'
export const PROOF_EXTS = ['jpg', 'jpeg', 'png', 'pdf']
export const PROOF_MAX_MB = 10

export interface ProofView {
  proofId: number
  fileId: number
  fileName: string
  contentType?: string
  size?: number
  action: string
  createByName?: string
  createTime?: string
}
export interface UploadedProof { id: number; fileName: string; size: number }

export function uploadProofFile(file: File): Promise<UploadedProof> {
  const fd = new FormData()
  fd.append('file', file)
  fd.append('bizType', 'pay_proof')
  return request.post('/rent/files', fd, { headers: { 'Content-Type': 'multipart/form-data' }, timeout: 0 })
}
export function fetchProofs(bizType: ProofBizType, bizId: number): Promise<ProofView[]> {
  return request.get('/rent/pay-proofs', { params: { bizType, bizId } })
}
export function supplementProofs(bizType: ProofBizType, bizId: number, fileIds: number[]): Promise<void> {
  return request.post('/rent/pay-proofs', { bizType, bizId, fileIds })
}
```

- [ ] **Step 2: `ProofUploader.vue`**

```vue
<script setup lang="ts">
import { ref } from 'vue'
import { ElMessage } from 'element-plus'
import { checkUploadFile } from '@/api/asset'
import { uploadProofFile, PROOF_EXTS, PROOF_MAX_MB, type UploadedProof } from '@/api/payProof'

/** 回单上传:选文件即上传,v-model 是已上传文件的 id 列表。移除只是不挂到本次登记上。 */
const fileIds = defineModel<number[]>({ default: () => [] })
const files = ref<UploadedProof[]>([])
const uploading = ref(false)
const input = ref<HTMLInputElement>()

async function onPick(e: Event) {
  const picked = Array.from((e.target as HTMLInputElement).files || [])
  ;(e.target as HTMLInputElement).value = ''
  for (const f of picked) {
    const err = checkUploadFile(f, PROOF_EXTS, PROOF_MAX_MB)
    if (err) { ElMessage.warning(err); return }
  }
  uploading.value = true
  try {
    for (const f of picked) files.value.push(await uploadProofFile(f))
    fileIds.value = files.value.map((f) => f.id)
  } finally {
    uploading.value = false
  }
}
function remove(i: number) {
  files.value.splice(i, 1)
  fileIds.value = files.value.map((f) => f.id)
}
</script>

<template>
  <div class="pu">
    <div v-for="(f, i) in files" :key="f.id" class="pu-file">
      <span>📎 {{ f.fileName }}</span>
      <el-button link type="danger" size="small" @click="remove(i)">移除</el-button>
    </div>
    <el-button size="small" :loading="uploading" @click="input?.click()">＋ 上传回单</el-button>
    <span class="pu-tip">银行回单或支付截图，jpg/png/pdf，单个 ≤ {{ PROOF_MAX_MB }}MB，可多张；上传后不可删除</span>
    <input ref="input" type="file" accept=".jpg,.jpeg,.png,.pdf" multiple hidden @change="onPick" />
  </div>
</template>

<style scoped>
.pu { display: flex; flex-direction: column; align-items: flex-start; gap: 4px; width: 100%; }
.pu-file { font-size: 13px; display: flex; gap: 8px; align-items: center; }
.pu-tip { font-size: 12px; color: #909399; }
</style>
```

- [ ] **Step 3: `ProofViewer.vue`**

```vue
<script setup lang="ts">
import { ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { downloadFileBlob } from '@/api/asset'
import { fetchProofs, supplementProofs, type ProofBizType, type ProofView } from '@/api/payProof'
import ProofUploader from './ProofUploader.vue'

const props = defineProps<{ bizType: ProofBizType; bizId: number; title?: string; canSupplement?: boolean }>()
const visible = defineModel<boolean>({ default: false })
const emit = defineEmits<{ (e: 'changed', count: number): void }>()

const list = ref<ProofView[]>([])
const loading = ref(false)
const newIds = ref<number[]>([])
const saving = ref(false)

async function load() {
  loading.value = true
  try { list.value = await fetchProofs(props.bizType, props.bizId) } finally { loading.value = false }
}
async function open(p: ProofView) {
  const blob = await downloadFileBlob(p.fileId)
  window.open(URL.createObjectURL(blob), '_blank')
}
async function supplement() {
  saving.value = true
  try {
    await supplementProofs(props.bizType, props.bizId, newIds.value)
    ElMessage.success('回单已补传')
    newIds.value = []
    await load()
    emit('changed', list.value.length)
  } finally { saving.value = false }
}
watch(visible, (v) => { if (v) { newIds.value = []; load() } })
</script>

<template>
  <el-dialog v-model="visible" :title="title || '回单'" width="520px" destroy-on-close>
    <div v-loading="loading">
      <el-empty v-if="!list.length" description="历史无回单" :image-size="56" />
      <div v-for="p in list" :key="p.proofId" class="pv-row">
        <el-link type="primary" @click="open(p)">📎 {{ p.fileName }}</el-link>
        <span class="pv-meta">{{ p.action }} · {{ p.createByName || '—' }} · {{ (p.createTime || '').replace('T', ' ').slice(0, 16) }}</span>
      </div>
      <template v-if="canSupplement">
        <el-divider />
        <ProofUploader v-model="newIds" />
      </template>
    </div>
    <template #footer>
      <el-button @click="visible = false">关闭</el-button>
      <el-button v-if="canSupplement" type="primary" :disabled="!newIds.length" :loading="saving" @click="supplement">补传</el-button>
    </template>
  </el-dialog>
</template>

<style scoped>
.pv-row { display: flex; flex-direction: column; padding: 6px 0; border-bottom: 1px dashed #ebeef5; }
.pv-meta { font-size: 12px; color: #909399; }
</style>
```

- [ ] **Step 4: 构建**

Run: `pnpm --dir frontend install`（首次），再 `pnpm --dir frontend build`
Expected: 构建成功。

- [ ] **Step 5: 提交**

```bash
git add frontend/src/api/payProof.ts frontend/src/components/payproof
git commit -m "feat(frontend): 回单上传与查看/补传组件" -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 7: 采购登记付款接回单

**Files:**
- Modify: `frontend/src/api/purchase.ts`（`PayableLine` 类型）
- Modify: `frontend/src/views/Purchase.vue`

- [ ] **Step 1: 类型**

`PayableLine` 接口加 `proofCount?: number`。

- [ ] **Step 2: 脚本**

1. import：`import ProofUploader from '@/components/payproof/ProofUploader.vue'`、`import ProofViewer from '@/components/payproof/ProofViewer.vue'`。
2. `payinForm` 初始对象加 `proofFileIds: [] as number[]`；`openPayin` 的 `Object.assign` 里加 `proofFileIds: []`。
3. `submitPayin` 开头加 `if (!payinForm.proofFileIds.length) { ElMessage.warning('请先上传付款回单'); return }`；`payPayable(...)` 的 body 加 `proofFileIds: payinForm.proofFileIds`。
4. 新增：

```ts
const proofView = reactive<{ show: boolean; id: number; title: string }>({ show: false, id: 0, title: '' })
function openProofs(row: PayableLine) {
  Object.assign(proofView, { show: true, id: row.id, title: `付款回单 · ${row.stage}` })
}
const canSupplementPay = ['财务', '老板'].includes(localStorage.getItem('rent_user_role') || '')
```

- [ ] **Step 3: 模板**

1. 应付明细表 `<el-table :data="detail.payables"` 在「操作」列之前加：

```vue
            <el-table-column label="回单" width="96">
              <template #default="{ row }">
                <el-link v-if="row.proofCount" type="primary" @click="openProofs(row)">📎 {{ row.proofCount }} 张</el-link>
                <el-tag v-else-if="row.status === '已付'" type="warning" size="small" effect="plain" style="cursor:pointer" @click="openProofs(row)">历史无回单</el-tag>
                <span v-else>—</span>
              </template>
            </el-table-column>
```

2. 「登记付款」弹窗里 `<el-form-item label="备注">` 之后加：

```vue
        <el-form-item label="付款回单" required><ProofUploader v-model="payinForm.proofFileIds" /></el-form-item>
```

「确认登记」按钮加 `:disabled="!payinForm.proofFileIds.length"`。

3. 模板最外层 div 内末尾加（`reloadDetail` 换成 `Purchase.vue` 里刷新详情的实际函数，`grep -n "function .*[Dd]etail" frontend/src/views/Purchase.vue` 查）：

```vue
    <ProofViewer v-model="proofView.show" biz-type="payable" :biz-id="proofView.id" :title="proofView.title"
      :can-supplement="canSupplementPay" @changed="reloadDetail" />
```

- [ ] **Step 4: 构建 + 提交**

Run: `pnpm --dir frontend build` → 成功。

```bash
git add frontend/src/api/purchase.ts frontend/src/views/Purchase.vue
git commit -m "feat(purchase-ui): 登记付款必须上传回单,应付行可查看/补传回单" -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 8: 收租页（核销/批量/退款/还款）接回单

**Files:**
- Modify: `frontend/src/api/rent.ts`
- Modify: `frontend/src/views/Rent.vue`

- [ ] **Step 1: api**

- `BillItem` 加 `proofCount?: number`；`CashCheck` 加 `matchedNoProof: number`、`matchedNoProofDetail: string[]`。
- `batchMatch` 改为：

```ts
export function batchMatch(ids: number[], proofFileIds: number[]): Promise<BillItem[]> {
  return request.post('/rent/bills/batch-match', { ids, proofFileIds })
}
```

- [ ] **Step 2: 脚本 —— 一个「收付款登记」弹窗替换四处 `ElMessageBox`**

加 import 与状态：

```ts
import ProofUploader from '@/components/payproof/ProofUploader.vue'
import ProofViewer from '@/components/payproof/ProofViewer.vue'

type CashMode = 'match' | 'batch' | 'refund' | 'repay'
const cash = reactive<{
  show: boolean; mode: CashMode; title: string; summary: string; bill?: BillItem; ids: number[]
  caseId?: number; amount: number; proofFileIds: number[]; saving: boolean
}>({ show: false, mode: 'match', title: '', summary: '', ids: [], amount: 0, proofFileIds: [], saving: false })
function openCash(mode: CashMode, opts: { bill?: BillItem; ids?: number[]; caseId?: number; title: string; summary: string; amount?: number }) {
  Object.assign(cash, {
    show: true, mode, bill: opts.bill, ids: opts.ids || [], caseId: opts.caseId, title: opts.title,
    summary: opts.summary, amount: opts.amount || 0, proofFileIds: [], saving: false,
  })
}
async function submitCash() {
  if (!cash.proofFileIds.length) { ElMessage.warning('请先上传回单'); return }
  cash.saving = true
  try {
    if (cash.mode === 'match') {
      await matchBill(cash.bill!.id, { proofFileIds: cash.proofFileIds })
      ElMessage.success('已核销')
    } else if (cash.mode === 'batch') {
      await batchMatch(cash.ids, cash.proofFileIds)
      ElMessage.success(`已批量核销 ${cash.ids.length} 张`)
    } else if (cash.mode === 'refund') {
      await refundBill(cash.bill!.id, { amount: cash.amount, reason: '退款', proofFileIds: cash.proofFileIds })
      ElMessage.success('已生成退款单')
    } else {
      const r = await overdueRepay(cash.caseId!, { proofFileIds: cash.proofFileIds })
      ElMessage.success(`已关案(${r.caseStatus})· 承租关系${r.assetsRestored ? '存续' : '已收回'}`)
      loadOverdue()
    }
    cash.show = false
    loadBills(); loadCashCheck()
  } finally {
    cash.saving = false
  }
}
const billProof = reactive<{ show: boolean; id: number; title: string }>({ show: false, id: 0, title: '' })
function openBillProof(row: BillItem) { Object.assign(billProof, { show: true, id: row.id, title: '回单 · ' + row.billNo }) }
const canFinance = ['财务', '老板'].includes(localStorage.getItem('rent_user_role') || '')
```

删除原来的 `async function doMatch`、`doBatchMatch`、`doRefund`、`stepRepay` 整段，改为：

```ts
function doMatch(row: BillItem) {
  openCash('match', { bill: row, title: '到账核销', summary: `${row.billNo} · 应收 ${money(row.amount)}` })
}
function doBatchMatch() {
  const targets = selected.value.filter((b) => b.status === '待收' || b.status === '逾期')
  if (!targets.length) { ElMessage.warning('请勾选待收/逾期收租单'); return }
  openCash('batch', { ids: targets.map((b) => b.id), title: '批量核销', summary: `${targets.length} 张 · 合计 ${money(selectedSum.value)}（按各单应收全额）` })
}
function doRefund(row: BillItem) {
  openCash('refund', { bill: row, title: '退款', summary: `${row.billNo} · 已收 ${money(row.receivedAmount)}`, amount: Number(row.receivedAmount || row.amount) })
}
function stepRepay(c: OverdueItem) {
  openCash('repay', { caseId: c.id, title: '还款恢复', summary: `核销逾期单 ${c.billNo} 并关闭本案` })
}
```

- [ ] **Step 3: 模板**

1. 稽核带在「已核销缺凭证」标签后加：

```vue
      <el-tag :type="cashCheck.matchedNoProof ? 'warning' : 'success'" size="small">已核销无回单 {{ cashCheck.matchedNoProof }}</el-tag>
```

明细 popover 末尾加：

```vue
          <div v-if="cashCheck.matchedNoProofDetail?.length"><b>已核销无回单(可补传)</b><div v-for="(d,i) in cashCheck.matchedNoProofDetail.slice(0,8)" :key="'d'+i">· {{ d }}</div></div>
```

2. 收租单表「凭证」列之后加：

```vue
          <el-table-column label="回单" width="80"><template #default="{ row }">
            <el-link v-if="row.proofCount" type="primary" @click="openBillProof(row)">📎 {{ row.proofCount }}</el-link>
            <el-tag v-else-if="row.status === '已核销' && (row.billKind === '正常' || row.billKind === '罚息')" type="warning" size="small" effect="plain" style="cursor:pointer" @click="openBillProof(row)">无回单</el-tag>
            <span v-else>—</span>
          </template></el-table-column>
```

3. 权限一致：「核销」按钮 `v-if` 改为 `canFinance && (row.status === '待收' || row.status === '逾期')`；「退款」按钮 `v-if` 前加 `canFinance && `；「批量核销」按钮加 `v-if="canFinance"`；「还款恢复」按钮加 `v-if="canFinance"`。
4. 根 div 内末尾加：

```vue
    <el-dialog v-model="cash.show" :title="cash.title" width="480px" destroy-on-close>
      <el-form label-width="90px">
        <el-form-item label="单据">{{ cash.summary }}</el-form-item>
        <el-form-item v-if="cash.mode === 'refund'" label="退款金额">
          <el-input-number v-model="cash.amount" :min="0.01" :precision="2" controls-position="right" style="width:180px" />
        </el-form-item>
        <el-form-item :label="cash.mode === 'refund' ? '退款回单' : '收款回单'" required>
          <ProofUploader v-model="cash.proofFileIds" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="cash.show = false">取消</el-button>
        <el-button type="primary" :disabled="!cash.proofFileIds.length" :loading="cash.saving" @click="submitCash">确认</el-button>
      </template>
    </el-dialog>
    <ProofViewer v-model="billProof.show" biz-type="rent_bill" :biz-id="billProof.id" :title="billProof.title"
      :can-supplement="canFinance" @changed="loadBills(); loadCashCheck()" />
```

- [ ] **Step 4: 构建 + 提交**

Run: `pnpm --dir frontend build` → 成功。

```bash
git add frontend/src/api/rent.ts frontend/src/views/Rent.vue
git commit -m "feat(rent-ui): 核销/批量/退款/还款统一走带回单的登记弹窗,稽核显示已核销无回单" -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 9: 新「服务」模块，资产管理瘦身

**Files:**
- Create: `frontend/src/views/Service.vue`
- Modify: `frontend/src/views/Inventory.vue`
- Modify: `frontend/src/api/inventory.ts`（`InvDamage` 加 `proofCount?: number`）
- Modify: `frontend/src/router/index.ts`
- Modify: `frontend/src/App.vue`
- Modify: `frontend/src/components/inventory/MovementForm.vue`

以下行号均指**改动前**的 `Inventory.vue`（1230 行版本，提交 0685fac）。

- [ ] **Step 1: 建 `Service.vue`**

脚本头：

```ts
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  fetchInvItems, fetchInvDamages, settleInvDamage, fetchCompPrices, saveCompPrices, fetchInvCompany, saveInvCompany,
  fetchInvOverview, currentRole, SETTLE_STATUSES, OPERATE_ROLES, SETTLE_ROLES,
  type InvItem, type InvDamage, type PriceRow, type CompanyInfo,
} from '@/api/inventory'
import {
  fetchMaintenances, createMaintenance, assignMaintenance, handleMaintenance, fetchSpareAlert,
  type MaintenanceItem, type SparePartAlertResponse,
} from '@/api/maintenance'
import { fetchAssets, type AssetListItem } from '@/api/asset'
import ProofUploader from '@/components/payproof/ProofUploader.vue'
import ProofViewer from '@/components/payproof/ProofViewer.vue'

const route = useRoute()
const router = useRouter()
const role = currentRole()
const canOperate = OPERATE_ROLES.includes(role)
const canSettle = SETTLE_ROLES.includes(role)
const canCompany = ['老板', '供应链'].includes(role)
const allItems = ref<InvItem[]>([])
const tab = ref<string>((route.query.tab as string) || 'maintenance')
```

然后**原样搬入**：
- 脚本：第 39-40 行（`fmtTime`、`money`）、第 43 行（`settleTag`）、第 370-382 行（`damageFilters`…`damageSum`）、第 391-425 行（设置）、第 428-535 行（维保工单）。
- 模板（放进 `<el-tabs v-model="tab">`，顺序：维保工单、损坏与赔偿、设置）：第 805-873 行、第 774-803 行、第 875-916 行；tabs 之后放第 1113-1192 行的两个维保 dialog。
- 样式：第 1198-1203 行（`.bar .grow .tip .bad .mt6 .mt12`）、第 1204 行（`.mb8`）。

搬完后的修改：
1. 维保脚本里 `submitMtCreate`、`submitMtHandle` 中的 `refreshAfterChange()` 调用删除（服务页没有库存表要刷新）。
2. 不搬原 `settle` 函数（第 383-389 行），改用：

```ts
const pendingSum = ref(0)
async function loadPending() { pendingSum.value = (await fetchInvOverview()).pendingCompensation }
const settleDlg = reactive<{ show: boolean; row?: InvDamage; proofFileIds: number[]; saving: boolean }>({ show: false, proofFileIds: [], saving: false })
async function settle(d: InvDamage, status: string) {
  if (status === d.settleStatus) return
  if (status === '已收取') {
    Object.assign(settleDlg, { show: true, row: d, proofFileIds: [], saving: false })
    return
  }
  if (status === '已减免') {
    const { value } = await ElMessageBox.prompt(`${d.partName} ${money(d.amount)} 减免原因`, '减免赔偿', {
      inputValidator: (v) => (v && v.trim() ? true : '请写明减免原因'),
    })
    await settleInvDamage(d.id, { settleStatus: status, remark: value.trim() })
  } else {
    await ElMessageBox.confirm(`${d.partName} ${money(d.amount)} 改回「${status}」？`, '赔偿处理', { type: 'info' })
    await settleInvDamage(d.id, { settleStatus: status })
  }
  ElMessage.success('已更新')
  await loadDamages(); loadPending()
}
async function submitSettle() {
  settleDlg.saving = true
  try {
    await settleInvDamage(settleDlg.row!.id, { settleStatus: '已收取', proofFileIds: settleDlg.proofFileIds })
    ElMessage.success('已标记已收取')
    settleDlg.show = false
    await loadDamages(); loadPending()
  } finally { settleDlg.saving = false }
}
const damageProof = reactive<{ show: boolean; id: number; title: string }>({ show: false, id: 0, title: '' })
function openDamageProof(row: InvDamage) { Object.assign(damageProof, { show: true, id: row.id, title: '赔偿回单 · ' + row.partName }) }

function loadTab(t: string) {
  if (t === 'damages') { loadPending(); return loadDamages() }
  if (t === 'maintenance') { loadMaintenanceRefs(); return loadMaintenances() }
  if (t === 'settings') return loadSettings()
}
watch(tab, (t) => { router.replace({ query: { ...route.query, tab: t } }); loadTab(t) })
onMounted(() => loadTab(tab.value))
```

（若 `settleInvDamage` 的参数类型是具体接口，在 `api/inventory.ts` 给它加 `proofFileIds?: number[]`。）
3. 损坏与赔偿工具栏「合计」后加 `<span class="tip">待收取合计 <b class="bad">{{ money(pendingSum) }}</b></span>`；表格「处理」列之后加：

```vue
          <el-table-column label="回单" width="80">
            <template #default="{ row }">
              <el-link v-if="row.proofCount" type="primary" @click="openDamageProof(row)">📎 {{ row.proofCount }}</el-link>
              <el-tag v-else-if="row.settleStatus === '已收取'" type="warning" size="small" effect="plain" style="cursor:pointer" @click="openDamageProof(row)">无回单</el-tag>
              <span v-else>—</span>
            </template>
          </el-table-column>
```

4. 根 div 末尾加：

```vue
    <el-dialog v-model="settleDlg.show" title="赔偿已收取" width="460px" destroy-on-close>
      <div v-if="settleDlg.row" class="tip mb8">{{ settleDlg.row.customerName || '—' }} · {{ settleDlg.row.partName }} · {{ money(settleDlg.row.amount) }}</div>
      <el-form label-width="90px">
        <el-form-item label="收款回单" required><ProofUploader v-model="settleDlg.proofFileIds" /></el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="settleDlg.show = false">取消</el-button>
        <el-button type="primary" :disabled="!settleDlg.proofFileIds.length" :loading="settleDlg.saving" @click="submitSettle">确认</el-button>
      </template>
    </el-dialog>
    <ProofViewer v-model="damageProof.show" biz-type="inv_damage" :biz-id="damageProof.id" :title="damageProof.title"
      :can-supplement="canSettle" @changed="loadDamages" />
```

根元素用 `<div class="svc">`，样式补 `.svc { padding-bottom: 24px; }`。

- [ ] **Step 2: `Inventory.vue` 瘦身**

1. 删除脚本第 370-425 行、第 428-535 行；删除模板第 774-916 行（三个 pane）、第 1113-1192 行（两个维保 dialog）。
2. import 清理：去掉 `fetchInvDamages, settleInvDamage, saveCompPrices, saveInvCompany, SETTLE_STATUSES, SETTLE_ROLES, type InvDamage`、整个 `@/api/maintenance` import、`fetchAssets, type AssetListItem` import；**保留** `fetchCompPrices`（`openMove` 用）、`fetchInvCompany`（打印标签用）、`settleTag`（出入库展开行用）。删除不再用的 `canSettle`、`canCompany`。
3. `onReminder` 删掉 `else if (r.type === '赔偿待收') {...}` 分支。
4. `loadTab` 删掉 `damages`、`maintenance`、`settings` 三行。
5. 旧书签兜底，放在 `const tab = ref(...)` 之前：

```ts
// 维保/赔偿/设置已移到「服务」模块(10-07 纪要),旧书签跳过去
const MOVED_TABS = ['damages', 'maintenance', 'settings']
if (MOVED_TABS.includes(String(route.query.tab))) {
  router.replace({ path: '/service', query: { tab: String(route.query.tab) } })
}
```

6. 提醒卡片 header 改为 `提醒（合同到期 30 天内 · 预计归还 7 天内 · 逾期 · 需要维修）`；统计区删除 `赔偿待收取` 那个 `stat`。
7. 二维码弹窗提示里「企业信息在「设置」里修改」改为「企业信息在「服务 › 设置」里修改」。
8. 自查：`grep -nE "mt[A-Z]|damage|priceRows|companyForm|loadSettings|loadDamages" frontend/src/views/Inventory.vue` 只应剩出入库展开行里的 `row.damages` 相关内容。

- [ ] **Step 3: 路由、侧栏、文案**

`router/index.ts`：
- `/maintenance` 改为 `{ path: '/maintenance', redirect: { path: '/service', query: { tab: 'maintenance' } } }`，上方注释改为 `// 维保工单已移到「服务」模块(10-07 纪要);保留旧路径重定向,避免旧书签 404`。
- `/inventory` 的 title 改为 `'资产管理 · 台账/出租/出入库/提醒'`。
- 其后新增 `{ path: '/service', name: 'service', meta: { title: '服务 · 维保/损坏赔偿/设置' }, component: () => import('@/views/Service.vue') },`

`App.vue`：
- `🏷️ 资产管理 · 出租/出入库/维保` 改为 `🏷️ 资产管理 · 台账/出租/出入库`
- 紧接其后加 `<el-menu-item index="/service">🛠️ 服务 · 维保/赔偿/设置</el-menu-item>`

`MovementForm.vue`：`资产管理 › 设置` 改为 `服务 › 设置`。

`api/inventory.ts`：`InvDamage` 接口加 `proofCount?: number`。

- [ ] **Step 4: 构建 + 提交**

Run: `pnpm --dir frontend build` → 成功。

```bash
git add frontend/src
git commit -m "refactor(service): 维保工单/损坏与赔偿/设置移出资产管理,新建服务模块;赔偿收取须传回单" -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 10: 设备台账页总资产卡片

**Files:**
- Modify: `frontend/src/api/asset.ts`
- Modify: `frontend/src/views/Asset.vue`

- [ ] **Step 1: api**

```ts
export interface AssetValueGroup { name: string; count: number; originalTotal: number; accumulatedDepreciation: number; netTotal: number }
export interface TotalAssetValue {
  depreciatedThrough?: string
  assetCount: number
  originalTotal: number
  accumulatedDepreciation: number
  netTotal: number
  missingPriceCount: number
  byCategory: AssetValueGroup[]
  byStatus: AssetValueGroup[]
}
export function fetchTotalAssetValue(): Promise<TotalAssetValue> {
  return request.get('/rent/assets/total-value')
}
export async function exportTotalAssetValue(): Promise<void> {
  const blob: Blob = await request.get('/rent/assets/total-value/export', { responseType: 'blob', timeout: 0 })
  const a = document.createElement('a')
  a.href = URL.createObjectURL(blob)
  a.download = `总资产-${new Date().toISOString().slice(0, 10)}.xlsx`
  a.click()
  URL.revokeObjectURL(a.href)
}
```

- [ ] **Step 2: Asset.vue**

脚本（`fetchTotalAssetValue, exportTotalAssetValue, type TotalAssetValue` 并入现有 `@/api/asset` import）：

```ts
const totalValue = ref<TotalAssetValue | null>(null)
const totalDrawer = ref(false)
const canSeeTotal = () => ['老板', '财务'].includes(localStorage.getItem('rent_user_role') || '')
async function loadTotalValue() {
  totalValue.value = canSeeTotal() ? await fetchTotalAssetValue() : null
}
```

`switchIdentity` 函数末尾加 `loadTotalValue()`；`onMounted` 里 `loadList()` 之后加 `loadTotalValue()`。

模板：`<div class="toolbar">…</div>` 之后、`<el-card shadow="never" class="filter-card">` 之前加：

```vue
    <el-card v-if="totalValue" shadow="never" class="total-card">
      <div class="tv-row">
        <div><div class="tv-v">{{ money(totalValue.originalTotal) }}</div><div class="tv-l">总资产 · 不含折旧（集采价）</div></div>
        <div><div class="tv-v dep">{{ money(totalValue.accumulatedDepreciation) }}</div><div class="tv-l">累计折旧</div></div>
        <div><div class="tv-v net">{{ money(totalValue.netTotal) }}</div><div class="tv-l">总资产 · 含折旧（净值）</div></div>
        <div class="tv-side">
          <span class="upload-tip">在账 {{ totalValue.assetCount }} 台 · 折旧计提至 {{ totalValue.depreciatedThrough || '尚未计提' }}</span>
          <span v-if="totalValue.missingPriceCount" class="upload-tip">⚠ {{ totalValue.missingPriceCount }} 台缺集采价，未计入</span>
          <el-button size="small" @click="totalDrawer = true">明细</el-button>
          <el-button size="small" @click="exportTotalAssetValue">导出</el-button>
        </div>
      </div>
    </el-card>
    <el-drawer v-model="totalDrawer" title="总资产明细" size="640px">
      <template v-if="totalValue">
        <h4>按品类</h4>
        <el-table :data="totalValue.byCategory" size="small" border>
          <el-table-column prop="name" label="品类" />
          <el-table-column prop="count" label="台数" width="70" align="right" />
          <el-table-column label="原值" align="right"><template #default="{ row }">{{ money(row.originalTotal) }}</template></el-table-column>
          <el-table-column label="累计折旧" align="right"><template #default="{ row }">{{ money(row.accumulatedDepreciation) }}</template></el-table-column>
          <el-table-column label="净值" align="right"><template #default="{ row }">{{ money(row.netTotal) }}</template></el-table-column>
        </el-table>
        <h4>按状态</h4>
        <el-table :data="totalValue.byStatus" size="small" border>
          <el-table-column prop="name" label="状态" />
          <el-table-column prop="count" label="台数" width="70" align="right" />
          <el-table-column label="原值" align="right"><template #default="{ row }">{{ money(row.originalTotal) }}</template></el-table-column>
          <el-table-column label="累计折旧" align="right"><template #default="{ row }">{{ money(row.accumulatedDepreciation) }}</template></el-table-column>
          <el-table-column label="净值" align="right"><template #default="{ row }">{{ money(row.netTotal) }}</template></el-table-column>
        </el-table>
        <p class="upload-tip">口径：设备台账中 采购/投放/在租/待转让/收回待处置 的设备；已转让、报废不计。仓库实物（资产管理）没有单价，不在此统计。</p>
      </template>
    </el-drawer>
```

样式（`<style scoped>` 末尾）：

```css
.total-card { margin-bottom: 10px; }
.tv-row { display: flex; align-items: center; gap: 28px; flex-wrap: wrap; }
.tv-v { font-size: 22px; font-weight: 700; color: #303133; }
.tv-v.dep { color: #e6a23c; }
.tv-v.net { color: #2f9e44; }
.tv-l { font-size: 12px; color: #909399; }
.tv-side { margin-left: auto; display: flex; align-items: center; gap: 8px; flex-wrap: wrap; }
```

`money` 用 `Asset.vue` 现有同名函数。

- [ ] **Step 3: 构建 + 提交**

Run: `pnpm --dir frontend build` → 成功。

```bash
git add frontend/src/api/asset.ts frontend/src/views/Asset.vue
git commit -m "feat(asset-ui): 设备台账页加总资产卡片(含/不含折旧)与明细导出" -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 11: 总验证、上线核对清单、推送

**Files:**
- Create: `docs/checklists/2026-10-13-子项目A上线核对.md`

- [ ] **Step 1: 全量验证**

```bash
cd backend && mvn -q test
```

```bash
pnpm --dir frontend build
```

两条都必须成功；失败先修再继续。

- [ ] **Step 2: 写上线核对清单**

`docs/checklists/2026-10-13-子项目A上线核对.md`：

```markdown
# 子项目 A 上线核对（资产管理重组 / 总资产 / 资金回单）

部署后用老板或财务账号逐项核对，打勾留档。

## 迁移
- [ ] Flyway 已执行到 V122，`SHOW CREATE TABLE yc_rent_pay_proof` 有唯一键 uk_pay_proof

## 资金回单（每项都走：不传回单 → 按钮置灰/接口 400；传回单 → 成功；单据上显示「📎 N」并能打开）
- [ ] 采购应付 · 登记付款
- [ ] 收租单 · 核销
- [ ] 收租单 · 批量核销（一张回单勾两张单，两张都显示 📎 1）
- [ ] 收租单 · 退款（回单挂在新生成的 -RF 退款单上）
- [ ] 逾期案 · 还款恢复（回单挂在该逾期收租单上）
- [ ] 服务 › 损坏与赔偿 · 已收取；已减免不写原因被拒
- [ ] 历史单据「历史无回单 / 无回单」可补传
- [ ] 业务、供应链账号看不到核销/还款按钮，直接调接口返回 403
- [ ] 收租页稽核带出现「已核销无回单 N」

## 模块重组
- [ ] 侧栏有「服务 · 维保/赔偿/设置」；资产管理只剩 提醒与报表 / 资产台账 / 出租管理 / 出入库记录
- [ ] 旧书签 /maintenance、/inventory?tab=damages、/inventory?tab=settings 跳到服务模块对应标签
- [ ] 资产管理提醒不再出现「赔偿待收」；服务 › 损坏与赔偿顶部显示待收取合计

## 总资产
- [ ] 老板、财务在设备台账页看到总资产卡片；其它身份看不到，直接调 /rent/assets/total-value 返回 403
- [ ] 不含折旧 = 在账设备集采价合计；含折旧 = 原值 − 累计折旧；与凭证中心折旧明细抽查 2 台一致
- [ ] 导出 Excel 两张表，末行合计与卡片一致
```

- [ ] **Step 3: 推送**

```bash
git add docs/checklists
git commit -m "docs: 子项目A上线核对清单" -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
git fetch origin
git status -sb
git push origin main
```

`git status -sb` 显示 `behind` 时，先 `git pull --rebase origin main`，解决冲突并重跑 Step 1 再推。

- [ ] **Step 4: 更新工作区 HANDOFF**

在 `D:\软件下载目录\克劳德\HANDOFF.md` 追加「子项目 A 已推送待部署核对」一节：推送的提交范围、核对清单路径、本机无法起后端的说明（HANDOFF 不在仓库内，不提交）。
