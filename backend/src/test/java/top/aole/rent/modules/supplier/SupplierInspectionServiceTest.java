package top.aole.rent.modules.supplier;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import top.aole.rent.common.audit.AuditLogService;
import top.aole.rent.common.auth.CurrentUser;
import top.aole.rent.common.auth.UserContext;
import top.aole.rent.modules.file.domain.FileObject;
import top.aole.rent.modules.file.mapper.FileObjectMapper;
import top.aole.rent.modules.supplier.domain.Supplier;
import top.aole.rent.modules.supplier.domain.SupplierInspection;
import top.aole.rent.modules.supplier.dto.InspectionDtos;
import top.aole.rent.modules.supplier.mapper.SupplierInspectionMapper;
import top.aole.rent.modules.supplier.mapper.SupplierMapper;
import top.aole.rent.modules.supplier.service.SupplierInspectionService;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 考察结果 ↔ 供应商上游 关联同步:导入新增并判定合格 / 改判不合格淘汰 / 再改回合格恢复。
 */
class SupplierInspectionServiceTest {

    private SupplierInspectionMapper inspectionMapper;
    private SupplierMapper supplierMapper;
    private SupplierInspectionService service;

    @BeforeAll
    static void initLambdaCache() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, SupplierInspection.class);
        TableInfoHelper.initTableInfo(assistant, Supplier.class);
        TableInfoHelper.initTableInfo(assistant, FileObject.class);
    }

    @BeforeEach
    void setUp() {
        inspectionMapper = mock(SupplierInspectionMapper.class);
        supplierMapper = mock(SupplierMapper.class);
        FileObjectMapper fileObjectMapper = mock(FileObjectMapper.class);
        when(fileObjectMapper.selectList(any())).thenReturn(Collections.emptyList());
        service = new SupplierInspectionService(inspectionMapper, supplierMapper, fileObjectMapper, mock(AuditLogService.class));
        UserContext.set(new CurrentUser(1001L, "老板", "老板", null));
    }

    @AfterEach
    void tearDown() {
        UserContext.clear();
    }

    @Test
    void importCreatesRowsAndOnlyPassedOnesJoinUpstream() {
        when(inspectionMapper.selectList(any())).thenReturn(new ArrayList<>());
        when(supplierMapper.selectList(any())).thenReturn(new ArrayList<>());
        doAnswer(inv -> {
            ((SupplierInspection) inv.getArgument(0)).setId(10L);
            return 1;
        }).doAnswer(inv -> {
            ((SupplierInspection) inv.getArgument(0)).setId(11L);
            return 1;
        }).when(inspectionMapper).insert(any(SupplierInspection.class));
        doAnswer(inv -> {
            ((Supplier) inv.getArgument(0)).setId(500L);
            return 1;
        }).when(supplierMapper).insert(any(Supplier.class));

        List<SupplierInspectionService.SheetRow> rows = new ArrayList<>();
        rows.add(sheetRow(2, 1, "合格厂家有限公司", SupplierInspectionService.PASSED));
        rows.add(sheetRow(3, 2, "不合格厂家有限公司", SupplierInspectionService.FAILED));
        rows.add(sheetRow(4, 3, "合格厂家有限公司", SupplierInspectionService.FAILED)); // 表内重复

        InspectionDtos.ImportResult res = service.importRows(rows);

        assertEquals(3, res.getTotal());
        assertEquals(2, res.getCreated());
        assertEquals(1, res.getPassed());
        assertEquals(1, res.getFailed());
        assertEquals(1, res.getSkipped());

        ArgumentCaptor<Supplier> created = ArgumentCaptor.forClass(Supplier.class);
        verify(supplierMapper, times(1)).insert(created.capture());
        assertEquals("合格厂家有限公司", created.getValue().getName());
        assertEquals("入库", created.getValue().getStatus());
        assertEquals("播种墙", created.getValue().getMainCategory());
    }

    @Test
    void changingPassedToFailedRetiresSupplierAndBackRestoresIt() {
        SupplierInspection row = new SupplierInspection();
        row.setId(20L);
        row.setCompanyName("改判厂家有限公司");
        row.setResult(SupplierInspectionService.PASSED);
        row.setSupplierId(600L);
        when(inspectionMapper.selectById(20L)).thenReturn(row);

        Supplier linked = new Supplier();
        linked.setId(600L);
        linked.setName("改判厂家有限公司");
        linked.setStatus("入库");
        when(supplierMapper.selectById(600L)).thenReturn(linked);

        InspectionDtos.DecideRequest fail = new InspectionDtos.DecideRequest();
        fail.setResult(SupplierInspectionService.FAILED);
        InspectionDtos.DecideResult r1 = service.decide(20L, fail);

        assertNull(r1.getSupplierId());
        assertNull(row.getSupplierId());
        assertEquals(SupplierInspectionService.FAILED, row.getResult());
        assertTrue(r1.getMessage().contains("淘汰"));
        verify(supplierMapper, times(1)).update(isNull(), any(Wrapper.class));

        // 模拟供应商已被置淘汰,再改回合格 → 按名称找回并恢复入库,不新建
        linked.setStatus("淘汰");
        linked.setRetireReason("考察改判不合格(考察#20)");
        when(supplierMapper.selectList(any())).thenReturn(Collections.singletonList(linked));
        InspectionDtos.DecideRequest pass = new InspectionDtos.DecideRequest();
        pass.setResult(SupplierInspectionService.PASSED);
        InspectionDtos.DecideResult r2 = service.decide(20L, pass);

        assertEquals(Long.valueOf(600L), r2.getSupplierId());
        assertFalse(r2.getSupplierCreated());
        assertTrue(r2.getMessage().contains("恢复"));
        verify(supplierMapper, times(2)).update(isNull(), any(Wrapper.class));
        verify(supplierMapper, never()).insert(any(Supplier.class));
    }

    private SupplierInspectionService.SheetRow sheetRow(int rowNum, int sortNo, String name, String result) {
        SupplierInspectionService.SheetRow sr = new SupplierInspectionService.SheetRow();
        sr.setRowNum(rowNum);
        sr.setSortNo(sortNo);
        sr.setCompanyName(name);
        sr.setBusinessScope(Collections.singletonList("播种墙"));
        sr.setResult(result);
        return sr;
    }
}
