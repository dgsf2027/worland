package top.aole.rent.modules.transfer.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import top.aole.rent.modules.transfer.domain.TransferOrder;
import top.aole.rent.modules.transfer.domain.TransferOrderLine;
import top.aole.rent.modules.transfer.dto.TransferDtos;
import top.aole.rent.modules.transfer.mapper.TransferOrderLineMapper;
import top.aole.rent.modules.transfer.mapper.TransferOrderMapper;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/**
 * 转让/处置的只读查询(按设备聚合),供设备租赁台账反向展示「转让/处置记录」。
 *
 * <p><b>为什么单独一个服务</b>:{@link TransferService} 依赖 {@code AssetService}(设备状态机 owner),
 * 若让 {@code AssetService} 反向依赖它就成环了。本服务只依赖转让模块自己的两个 mapper,没有环。
 * 批量口径与 {@code RentCoverageService} 一致:一次查完,列表页不 N+1。
 */
@Service
@RequiredArgsConstructor
public class TransferQueryService {

    private final TransferOrderMapper orderMapper;
    private final TransferOrderLineMapper lineMapper;

    /**
     * 按设备聚合处置记录,每台设备的记录按业务时间倒序(最近一次在前;时间为空的排最后)。
     * 没有记录的设备不出现在返回的 map 里。
     */
    public Map<Long, List<TransferDtos.DisposalLine>> disposalsByAsset(Collection<Long> assetIds) {
        Map<Long, List<TransferDtos.DisposalLine>> result = new HashMap<>();
        List<Long> ids = distinct(assetIds);
        if (ids.isEmpty()) {
            return result;
        }
        List<TransferOrderLine> lines = lineMapper.selectList(new LambdaQueryWrapper<TransferOrderLine>()
                .in(TransferOrderLine::getAssetId, ids));
        if (lines == null || lines.isEmpty()) {
            return result;
        }
        Map<Long, TransferOrder> orderById = loadOrders(lines);
        for (TransferOrderLine l : lines) {
            TransferOrder o = l.getTransferOrderId() == null ? null : orderById.get(l.getTransferOrderId());
            TransferDtos.DisposalLine d = new TransferDtos.DisposalLine();
            d.setId(l.getId());
            d.setOrderId(l.getTransferOrderId());
            d.setTransferPrice(l.getTransferPrice());
            d.setGain(l.getGain());
            if (o != null) {
                d.setOrderNo(o.getNo());
                d.setType(o.getType());
                d.setStatus(o.getStatus());
                d.setBizTime(o.getBizTime());
            }
            result.computeIfAbsent(l.getAssetId(), k -> new ArrayList<>()).add(d);
        }
        Comparator<TransferDtos.DisposalLine> byTimeDesc = Comparator
                .comparing(TransferDtos.DisposalLine::getBizTime,
                        Comparator.nullsLast(Comparator.reverseOrder()));
        result.values().forEach(list -> list.sort(byTimeDesc));
        return result;
    }

    /** 列表页用的处置状态摘要:最近一次的「类型 · 状态」;无记录的设备不在 map 里。 */
    public Map<Long, String> disposalStatusByAsset(Collection<Long> assetIds) {
        Map<Long, String> out = new HashMap<>();
        for (Map.Entry<Long, List<TransferDtos.DisposalLine>> e : disposalsByAsset(assetIds).entrySet()) {
            if (e.getValue().isEmpty()) {
                continue;
            }
            TransferDtos.DisposalLine latest = e.getValue().get(0);
            if (latest.getType() == null && latest.getStatus() == null) {
                continue;   // 单头查不到(已删),不给出误导性的摘要
            }
            out.put(e.getKey(), nz(latest.getType()) + " · " + nz(latest.getStatus()));
        }
        return out;
    }

    private Map<Long, TransferOrder> loadOrders(List<TransferOrderLine> lines) {
        LinkedHashSet<Long> orderIds = new LinkedHashSet<>();
        for (TransferOrderLine l : lines) {
            if (l.getTransferOrderId() != null) {
                orderIds.add(l.getTransferOrderId());
            }
        }
        Map<Long, TransferOrder> out = new HashMap<>();
        if (orderIds.isEmpty()) {
            return out;
        }
        List<TransferOrder> orders = orderMapper.selectBatchIds(new ArrayList<>(orderIds));
        if (orders != null) {
            orders.forEach(o -> out.put(o.getId(), o));
        }
        return out;
    }

    private static List<Long> distinct(Collection<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return new ArrayList<>();
        }
        LinkedHashSet<Long> set = new LinkedHashSet<>(ids);
        set.remove(null);
        return new ArrayList<>(set);
    }

    private static String nz(String v) {
        return v == null ? "?" : v;
    }
}
