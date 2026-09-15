package top.aole.rent.modules.inventory;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
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
import top.aole.rent.common.exception.BizException;
import top.aole.rent.modules.contract.domain.Contract;
import top.aole.rent.modules.contract.mapper.ContractMapper;
import top.aole.rent.modules.customer.domain.Customer;
import top.aole.rent.modules.customer.mapper.CustomerMapper;
import top.aole.rent.modules.file.domain.FileObject;
import top.aole.rent.modules.file.mapper.FileObjectMapper;
import top.aole.rent.modules.inventory.domain.InvCompPrice;
import top.aole.rent.modules.inventory.domain.InvCompany;
import top.aole.rent.modules.inventory.domain.InvDamage;
import top.aole.rent.modules.inventory.domain.InvItem;
import top.aole.rent.modules.inventory.domain.InvMovement;
import top.aole.rent.modules.inventory.domain.InvRental;
import top.aole.rent.modules.inventory.dto.InvDtos;
import top.aole.rent.modules.inventory.mapper.InvCompPriceMapper;
import top.aole.rent.modules.inventory.mapper.InvCompanyMapper;
import top.aole.rent.modules.inventory.mapper.InvDamageMapper;
import top.aole.rent.modules.inventory.mapper.InvItemMapper;
import top.aole.rent.modules.inventory.mapper.InvMovementMapper;
import top.aole.rent.modules.inventory.mapper.InvRentalMapper;
import top.aole.rent.modules.inventory.service.InvRules;
import top.aole.rent.modules.inventory.service.InvService;

import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 资产管理:预订/出库/归还的数量流转、损坏缺件赔偿计算、提醒口径。
 */
class InvServiceTest {

    private InvItemMapper itemMapper;
    private InvRentalMapper rentalMapper;
    private InvMovementMapper movementMapper;
    private InvDamageMapper damageMapper;
    private InvCompPriceMapper priceMapper;
    private CustomerMapper customerMapper;
    private ContractMapper contractMapper;
    private InvService service;

    @BeforeAll
    static void initLambdaCache() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        for (Class<?> c : Arrays.asList(InvItem.class, InvRental.class, InvMovement.class, InvDamage.class,
                InvCompPrice.class, InvCompany.class, FileObject.class, Contract.class, Customer.class)) {
            TableInfoHelper.initTableInfo(assistant, c);
        }
    }

    @BeforeEach
    void setUp() {
        itemMapper = mock(InvItemMapper.class);
        rentalMapper = mock(InvRentalMapper.class);
        movementMapper = mock(InvMovementMapper.class);
        damageMapper = mock(InvDamageMapper.class);
        priceMapper = mock(InvCompPriceMapper.class);
        customerMapper = mock(CustomerMapper.class);
        contractMapper = mock(ContractMapper.class);
        FileObjectMapper fileObjectMapper = mock(FileObjectMapper.class);
        when(fileObjectMapper.selectList(any())).thenReturn(Collections.emptyList());
        service = new InvService(itemMapper, rentalMapper, movementMapper, damageMapper, priceMapper,
                mock(InvCompanyMapper.class), customerMapper, contractMapper, fileObjectMapper, mock(AuditLogService.class));
        UserContext.set(new CurrentUser(1006L, "供应链", "供应链", null));
    }

    @AfterEach
    void tearDown() {
        UserContext.clear();
    }

    private static InvItem item(int stock, int reserved, int rented) {
        InvItem it = new InvItem();
        it.setId(1L);
        it.setCode("ZC20260915001");
        it.setName("播种墙");
        it.setSpec("120 格");
        it.setUnit("套");
        it.setStockQty(stock);
        it.setReservedQty(reserved);
        it.setRentedQty(rented);
        it.setRepairQty(0);
        it.setScrappedQty(0);
        it.setTotalQty(stock + reserved + rented);
        return it;
    }

    private static InvRental rental(String status, int qty, int out, int returned) {
        InvRental r = new InvRental();
        r.setId(7L);
        r.setRentalNo("CZ20260915001");
        r.setItemId(1L);
        r.setCustomerName("某物流");
        r.setQty(qty);
        r.setOutQty(out);
        r.setReturnedQty(returned);
        r.setStatus(status);
        r.setStartDate(LocalDate.of(2026, 9, 1));
        r.setExpectedReturnDate(LocalDate.of(2026, 12, 1));
        return r;
    }

    private static InvCompPrice price(String name, String damage, String missing) {
        InvCompPrice p = new InvCompPrice();
        p.setPartName(name);
        p.setDamagePrice(new BigDecimal(damage));
        p.setMissingPrice(new BigDecimal(missing));
        return p;
    }

    @SuppressWarnings("unchecked")
    private List<String> itemSetSqls() {
        ArgumentCaptor<LambdaUpdateWrapper<InvItem>> captor = ArgumentCaptor.forClass(LambdaUpdateWrapper.class);
        verify(itemMapper, times(1)).update(isNull(), captor.capture());
        return Arrays.stream(captor.getValue().getSqlSet().split(",")).map(String::trim).collect(Collectors.toList());
    }

    @Test
    void createRentalReservesStockAndSnapshotsCrmCustomer() {
        when(itemMapper.selectById(1L)).thenReturn(item(10, 0, 0));
        Customer c = new Customer();
        c.setId(3L);
        c.setName("华东仓配");
        when(customerMapper.selectById(3L)).thenReturn(c);
        when(itemMapper.update(isNull(), any())).thenReturn(1);
        ArgumentCaptor<InvRental> saved = ArgumentCaptor.forClass(InvRental.class);
        doAnswer(inv -> { ((InvRental) inv.getArgument(0)).setId(9L); return 1; }).when(rentalMapper).insert(saved.capture());

        InvDtos.RentalSave req = new InvDtos.RentalSave();
        req.setItemId(1L);
        req.setCustomerId(3L);
        req.setQty(4);
        req.setInstallAddress("嘉兴仓 2 号库");
        req.setStartDate(LocalDate.of(2026, 9, 15));
        req.setExpectedReturnDate(LocalDate.of(2027, 3, 15));
        assertEquals(9L, service.createRental(req));

        InvRental r = saved.getValue();
        assertEquals("华东仓配", r.getCustomerName());
        assertEquals(InvRules.R_RESERVED, r.getStatus());
        assertTrue(r.getRentalNo().startsWith("CZ"));
        assertEquals(Arrays.asList("stock_qty = stock_qty - 4", "reserved_qty = reserved_qty + 4"), itemSetSqls());
    }

    @Test
    void createRentalFailsWhenStockShort() {
        when(itemMapper.selectById(1L)).thenReturn(item(2, 0, 0));
        when(itemMapper.update(isNull(), any())).thenReturn(0);
        InvDtos.RentalSave req = new InvDtos.RentalSave();
        req.setItemId(1L);
        req.setCustomerName("散客");
        req.setQty(3);
        req.setStartDate(LocalDate.of(2026, 9, 15));
        req.setExpectedReturnDate(LocalDate.of(2026, 10, 15));
        BizException e = assertThrows(BizException.class, () -> service.createRental(req));
        assertTrue(e.getMessage().contains("库存"), e.getMessage());
        verify(rentalMapper, never()).insert(any(InvRental.class));
    }

    @Test
    void outboundCannotExceedPending() {
        when(rentalMapper.selectById(7L)).thenReturn(rental(InvRules.R_RESERVED, 5, 3, 0));
        InvDtos.OutRequest req = new InvDtos.OutRequest();
        req.setQty(3);
        BizException e = assertThrows(BizException.class, () -> service.outbound(7L, req));
        assertTrue(e.getMessage().contains("待出库数量 2"), e.getMessage());
    }

    @Test
    void outboundMovesReservedToRented() {
        when(rentalMapper.selectById(7L)).thenReturn(rental(InvRules.R_RESERVED, 5, 0, 0));
        when(rentalMapper.update(isNull(), any())).thenReturn(1);
        when(itemMapper.selectById(1L)).thenReturn(item(5, 5, 0));
        when(itemMapper.update(isNull(), any())).thenReturn(1);
        InvDtos.OutRequest req = new InvDtos.OutRequest();
        req.setQty(5);
        req.setAccessories("灯条×5,控制器×1");
        req.setConditionLevel("完好");
        InvDtos.MovementResult res = service.outbound(7L, req);
        assertEquals(InvRules.R_RENTED, res.getRentalStatus());
        assertEquals(Arrays.asList("reserved_qty = reserved_qty - 5", "rented_qty = rented_qty + 5"), itemSetSqls());
        ArgumentCaptor<InvMovement> m = ArgumentCaptor.forClass(InvMovement.class);
        verify(movementMapper).insert(m.capture());
        assertEquals(InvRules.M_OUT, m.getValue().getType());
        assertEquals("灯条×5,控制器×1", m.getValue().getAccessories());
    }

    @Test
    void fullReturnSplitsStatusesAndComputesCompensation() {
        when(rentalMapper.selectById(7L)).thenReturn(rental(InvRules.R_RENTED, 3, 3, 0));
        when(rentalMapper.update(isNull(), any())).thenReturn(1);
        when(itemMapper.update(isNull(), any())).thenReturn(1);
        when(priceMapper.selectList(any())).thenReturn(Arrays.asList(
                price("格口", "30", "50"), price("电子标签", "80", "120"), price("货架", "0", "0")));
        doAnswer(inv -> { ((InvMovement) inv.getArgument(0)).setId(55L); return 1; }).when(movementMapper).insert(any(InvMovement.class));
        List<InvDamage> damages = new ArrayList<>();
        doAnswer(inv -> { damages.add(inv.getArgument(0)); return 1; }).when(damageMapper).insert(any(InvDamage.class));

        InvDtos.ReturnRequest req = new InvDtos.ReturnRequest();
        req.setQty(3);
        req.setGoodQty(2);
        req.setRepairQty(1);
        req.setScrapQty(0);
        req.setConditionLevel("损坏");
        InvDtos.DamageLine l1 = new InvDtos.DamageLine();
        l1.setPartName("格口");
        l1.setDamagedQty(2);
        l1.setMissingQty(1);
        InvDtos.DamageLine l2 = new InvDtos.DamageLine();
        l2.setPartName("电子标签");
        l2.setMissingQty(3);
        InvDtos.DamageLine l3 = new InvDtos.DamageLine();
        l3.setPartName("货架");
        l3.setDamagedQty(1);
        InvDtos.DamageLine empty = new InvDtos.DamageLine();
        empty.setPartName("控制器");
        req.setDamages(Arrays.asList(l1, l2, l3, empty));

        InvDtos.MovementResult res = service.returnBack(7L, req);

        // 格口 2×30 + 1×50 = 110;电子标签 3×120 = 360;货架未定价 0
        assertEquals(new BigDecimal("470.00"), res.getCompensationTotal());
        assertEquals(Collections.singletonList("货架"), res.getUnpricedParts());
        assertEquals(InvRules.R_RETURNED, res.getRentalStatus());
        assertEquals(3, damages.size());
        assertTrue(damages.stream().allMatch(d -> d.getMovementId() == 55L && d.getRentalId() == 7L));
        assertEquals(Arrays.asList("rented_qty = rented_qty - 3", "stock_qty = stock_qty + 2",
                "repair_qty = repair_qty + 1", "scrapped_qty = scrapped_qty + 0"), itemSetSqls());
    }

    @Test
    void returnSplitMustAddUp() {
        when(rentalMapper.selectById(7L)).thenReturn(rental(InvRules.R_RENTED, 3, 3, 0));
        InvDtos.ReturnRequest req = new InvDtos.ReturnRequest();
        req.setQty(3);
        req.setGoodQty(1);
        req.setRepairQty(1);
        assertThrows(BizException.class, () -> service.returnBack(7L, req));
        verify(itemMapper, never()).update(isNull(), any());
    }

    @Test
    void cancelPartiallyOutReleasesRemainingReservation() {
        when(rentalMapper.selectById(7L)).thenReturn(rental(InvRules.R_RENTED, 5, 2, 0));
        when(itemMapper.selectById(1L)).thenReturn(item(0, 3, 2));
        when(itemMapper.update(isNull(), any())).thenReturn(1);
        service.cancelRental(7L);
        assertEquals(Arrays.asList("reserved_qty = reserved_qty - 3", "stock_qty = stock_qty + 3"), itemSetSqls());
    }

    @Test
    void rentalStatusRules() {
        assertEquals(InvRules.R_RESERVED, InvRules.rentalStatus(rental(null, 3, 0, 0)));
        assertEquals(InvRules.R_RENTED, InvRules.rentalStatus(rental(null, 3, 2, 2)));
        assertEquals(InvRules.R_RENTED, InvRules.rentalStatus(rental(null, 3, 3, 2)));
        assertEquals(InvRules.R_RETURNED, InvRules.rentalStatus(rental(null, 3, 3, 3)));
        assertEquals("ZC20260915013", InvRules.nextCode("ZC20260915", "ZC20260915012"));
        assertEquals("ZC20260915001", InvRules.nextCode("ZC20260915", null));
    }

    @Test
    void overviewCountsAndReminders() throws Exception {
        InvItem it = item(4, 2, 3);
        it.setRepairQty(1);
        it.setTotalQty(10);
        when(itemMapper.selectList(any())).thenReturn(Collections.singletonList(it));
        InvRental overdue = rental(InvRules.R_RENTED, 3, 3, 1);
        overdue.setExpectedReturnDate(LocalDate.of(2026, 9, 10));
        InvRental soon = rental(InvRules.R_RESERVED, 2, 0, 0);
        soon.setId(8L);
        soon.setExpectedReturnDate(LocalDate.of(2026, 9, 20));
        soon.setContractId(100L);
        when(rentalMapper.selectList(any())).thenReturn(Arrays.asList(overdue, soon));
        Contract c = new Contract();
        c.setId(100L);
        c.setNo("HT-001");
        c.setStartDate(LocalDate.of(2025, 10, 1));
        c.setTermMonths(12);
        when(contractMapper.selectBatchIds(any())).thenReturn(Collections.singletonList(c));
        InvDamage d = new InvDamage();
        d.setAmount(new BigDecimal("110"));
        when(damageMapper.selectList(any())).thenReturn(Collections.singletonList(d));

        Method m = InvService.class.getDeclaredMethod("overview", LocalDate.class);
        m.setAccessible(true);
        InvDtos.Overview o = (InvDtos.Overview) m.invoke(service, LocalDate.of(2026, 9, 15));

        assertEquals(10, o.getTotalQty());
        assertEquals(6, o.getInStoreQty());
        assertEquals(4, o.getIdleQty());
        assertEquals(3, o.getRentedQty());
        assertEquals(1, o.getOverdueRentalCount());
        assertEquals(new BigDecimal("110.00"), o.getPendingCompensation());
        List<String> types = o.getReminders().stream().map(InvDtos.Reminder::getType).collect(Collectors.toList());
        // danger 在前:设备逾期;再 warning:需要维修/即将归还/合同到期(2026-10-01 在 30 天内);info:赔偿待收
        assertEquals("设备逾期", types.get(0));
        assertTrue(types.containsAll(Arrays.asList("需要维修", "即将归还", "合同到期", "赔偿待收")), types.toString());
        assertEquals("赔偿待收", types.get(types.size() - 1));
    }
}
