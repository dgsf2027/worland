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
