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
        assertEquals(Long.valueOf(11L), cap.getAllValues().get(0).getBizId());
        assertEquals(Long.valueOf(12L), cap.getAllValues().get(1).getBizId());
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
        PayProof a = new PayProof();
        a.setBizId(1L);
        PayProof b = new PayProof();
        b.setBizId(1L);
        PayProof c = new PayProof();
        c.setBizId(2L);
        when(proofMapper.selectList(any())).thenReturn(Arrays.asList(a, b, c));
        Map<Long, Integer> m = service.countMap(PayProofService.RENT_BILL, Arrays.asList(1L, 2L, 3L));
        assertEquals(Integer.valueOf(2), m.get(1L));
        assertEquals(Integer.valueOf(1), m.get(2L));
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
