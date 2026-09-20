package top.aole.rent.modules.contract;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import top.aole.rent.modules.contract.domain.Contract;
import top.aole.rent.modules.contract.dto.BoqDtos;
import top.aole.rent.modules.contract.service.ContractBoqExcelService;
import top.aole.rent.modules.contract.service.ContractBoqService;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * 合同清单(挂在合同上):《工程量清单计价表》解析/导出回读、金额大写。
 */
class ContractBoqTest {

    private final ContractBoqExcelService excel = new ContractBoqExcelService(mock(ContractBoqService.class));

    @Test
    void upperAmountFollowsChineseConvention() {
        assertEquals("人民币壹佰叁拾叁万伍仟玖佰柒拾肆元捌角整", ContractBoqService.upperAmount(new BigDecimal("1335974.80")));
        assertEquals("人民币壹拾万元整", ContractBoqService.upperAmount(new BigDecimal("100000")));
        assertEquals("人民币壹佰万零壹元整", ContractBoqService.upperAmount(new BigDecimal("1000001")));
        assertEquals("人民币贰仟壹佰肆拾捌元伍角柒分", ContractBoqService.upperAmount(new BigDecimal("2148.57")));
        assertEquals("人民币零元整", ContractBoqService.upperAmount(BigDecimal.ZERO));
        assertEquals("人民币负贰拾捌万贰仟肆佰肆拾壹元贰角整", ContractBoqService.upperAmount(new BigDecimal("-282441.20")));
    }

    @Test
    void exportedSheetCanBeImportedBack() throws Exception {
        Contract contract = new Contract();
        contract.setId(1L);
        contract.setNo("HT-2026-001");
        contract.setTaxRate(new BigDecimal("0.13"));

        BoqDtos.Boq boq = new BoqDtos.Boq();
        boq.getLines().add(line(1, "立体智能播种墙", null, "3入口，3提升，8水平小车", "台", "6", "219036", "1314216", false, "112格口4层7列"));
        boq.getLines().add(line(2, "周转筐", null, null, "个", "672", null, null, true, "赠送，单独采购单价40元"));
        boq.getLines().add(line(3, "合作优惠", null, null, null, null, null, "-282441.2", true, "1-5台17.5%+第6台20%优惠"));
        boq.setTotalWithTax(new BigDecimal("1031774.80"));
        boq.setTotalWithoutTax(new BigDecimal("913075.04"));
        boq.setTaxAmount(new BigDecimal("118699.76"));
        boq.setTaxRate(contract.getTaxRate());
        boq.setTotalUpper(ContractBoqService.upperAmount(boq.getTotalWithTax()));

        byte[] xlsx = excel.export(contract, boq, false);
        List<BoqDtos.Line> back = parse(new ByteArrayInputStream(xlsx));

        assertEquals(3, back.size()); // 合计行不导入
        BoqDtos.Line a = back.get(0);
        assertEquals("立体智能播种墙", a.getName());
        assertEquals("3入口，3提升，8水平小车", a.getSpec());
        assertEquals("台", a.getUnit());
        assertEquals(0, a.getQty().compareTo(new BigDecimal("6")));
        assertEquals(0, a.getUnitPrice().compareTo(new BigDecimal("219036")));
        assertEquals(0, a.getAmount().compareTo(new BigDecimal("1314216")));
        assertEquals(Boolean.FALSE, a.getAmountManual()); // 数量×单价 对得上 → 自动算

        BoqDtos.Line gift = back.get(1);
        assertNull(gift.getAmount()); // 赠送行导出为「-」,导回仍是空
        assertEquals(Boolean.TRUE, gift.getAmountManual());

        BoqDtos.Line discount = back.get(2);
        assertEquals(0, discount.getAmount().compareTo(new BigDecimal("-282441.2")));
        assertEquals(Boolean.TRUE, discount.getAmountManual());
    }

    /** 业务提供的真实表格(E 盘附件);本机有这份文件时才跑。 */
    @Test
    void parsesRealPricingSheetWhenAvailable() throws Exception {
        File f = new File("E:\\云山项目\\耀石公司\\5、系统设置\\附件\\1、播种墙工程量清单计价表.xls");
        Assumptions.assumeTrue(f.isFile());
        List<String> messages = new ArrayList<>();
        List<BoqDtos.Line> lines;
        try (InputStream in = new FileInputStream(f)) {
            lines = parse(in, messages);
        }
        assertEquals(11, lines.size()); // 11 条明细,合计行跳过
        assertEquals(1, messages.size());
        assertTrue(messages.get(0).contains("合计"), messages.toString());

        BoqDtos.Line first = lines.get(0);
        assertEquals("立体智能播种墙", first.getName());
        assertEquals("台", first.getUnit());
        assertEquals(0, first.getQty().compareTo(new BigDecimal("6")));
        assertEquals(0, first.getAmount().compareTo(new BigDecimal("1314216")));

        BoqDtos.Line basket = lines.get(3); // 周转筐:金额「-」= 赠送
        assertEquals("周转筐", basket.getName());
        assertNull(basket.getAmount());
        assertEquals(Boolean.TRUE, basket.getAmountManual());

        BoqDtos.Line discount = lines.get(10); // 合作优惠:负数
        assertEquals("合作优惠", discount.getName());
        assertEquals(0, discount.getAmount().compareTo(new BigDecimal("-282441.2")));

        BigDecimal total = BigDecimal.ZERO;
        for (BoqDtos.Line l : lines) {
            total = total.add(l.getAmount() == null ? BigDecimal.ZERO : l.getAmount());
        }
        assertEquals(0, total.compareTo(new BigDecimal("1335974.80")), "合计应为表格里的 1,335,974.8,实际 " + total);
        assertEquals("人民币壹佰叁拾叁万伍仟玖佰柒拾肆元捌角整", ContractBoqService.upperAmount(total));
        assertTrue(Files.size(f.toPath()) > 0);
    }

    private static BoqDtos.Line line(int seq, String name, String model, String spec, String unit,
                                     String qty, String price, String amount, boolean manual, String remark) {
        BoqDtos.Line l = new BoqDtos.Line();
        l.setSeq(seq);
        l.setName(name);
        l.setModel(model);
        l.setSpec(spec);
        l.setUnit(unit);
        l.setQty(qty == null ? null : new BigDecimal(qty));
        l.setUnitPrice(price == null ? null : new BigDecimal(price));
        l.setAmount(amount == null ? null : new BigDecimal(amount));
        l.setAmountManual(manual);
        l.setRemark(remark);
        return l;
    }

    private List<BoqDtos.Line> parse(InputStream in) throws Exception {
        return parse(in, new ArrayList<>());
    }

    @SuppressWarnings("unchecked")
    private List<BoqDtos.Line> parse(InputStream in, List<String> messages) throws Exception {
        java.lang.reflect.Method m = ContractBoqExcelService.class.getDeclaredMethod("parse", InputStream.class, List.class);
        m.setAccessible(true);
        return (List<BoqDtos.Line>) m.invoke(excel, in, messages);
    }
}
