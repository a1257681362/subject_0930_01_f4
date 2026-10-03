package com.evops.coldchain.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.evops.coldchain.CcConstants;
import com.evops.coldchain.dto.HandoverSplitRequest;
import com.evops.coldchain.dto.SealVerifyRequest;
import com.evops.coldchain.entity.HandoverOrder;
import com.evops.coldchain.entity.HandoverSealVerify;
import com.evops.coldchain.entity.TransportBatch;
import com.evops.coldchain.mapper.HandoverOrderMapper;
import com.evops.coldchain.mapper.HandoverSealVerifyMapper;
import com.evops.coldchain.mapper.SequenceMapper;
import com.evops.coldchain.mapper.TransportBatchMapper;
import com.evops.common.BusinessException;
import com.evops.common.UserContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 冷链批次分仓交接服务（V4 核心）。
 *
 * 规则：
 * - 一车货到达中转仓后，可把一个在途运输批次按箱拆成多个交接单（{@link #split}）；
 *   拆单提交即一次性强校验箱数平衡、封签唯一、温度/接收人不缺失，任一不满足返回具体原因并整单拒绝。
 * - 封签核验事实只追加（{@link #verifySeal}）：每核验一次写一行 MATCH/MISMATCH，永不修改/删除；
 *   放行/交接以“最近一次核验结论”为卡口，但全部历史核验行保留可追溯。
 * - 只有封签最近一次核验 MATCH 的交接单才能完成交接（{@link #complete}）；
 *   完成瞬间责任主体随单转移并固化接收仓/接收人，未完成核验的箱子不能继续派送
 *   （{@link #releaseForDelivery} 再次卡口）。
 * - 冷链药品运输权限收紧（RESTRICTED）时，拆单/核验/交接/放行全部停止推进，
 *   但已确认的封签核验事实原样保留；放开后凭已确认事实可继续。
 * - 原运输批次不删不改，交接单与核验记录都带 batch_id，批次台账可追溯全部节点。
 */
@Service
public class HandoverService {

    @Resource
    private HandoverOrderMapper handoverMapper;
    @Resource
    private HandoverSealVerifyMapper sealVerifyMapper;
    @Resource
    private TransportBatchMapper batchMapper;
    @Resource
    private SequenceMapper sequenceMapper;
    @Resource
    private PolicyService policyService;
    @Resource
    private OpLogService opLogService;

    /**
     * 分仓拆单：一车货 -> 多个交接单。
     * 箱数不平/封签重复/温度或接收人缺失时，汇总所有具体原因并整体拒绝（不产生任何交接单行）。
     */
    @Transactional
    public List<HandoverOrder> split(HandoverSplitRequest req) {
        // 权限卡口：收紧时停止推进（已确认事实保留，不回滚）
        policyService.requireHandoverAllowed();

        TransportBatch batch = requireBatch(req.getBatchId());
        requireArrivedAtWarehouse(batch);

        if (batch.getTotalBoxes() == null) {
            throw new BusinessException(String.format(
                    "批次 %s 派车时未登记总箱数，无法做分仓拆单箱数平衡；请先在派车时登记 totalBoxes",
                    batch.getBatchNo()));
        }

        // 一个批次只做一次拆单：箱数平衡以批次总箱数为基准，重复拆单必然重箱/重封签
        Long existing = handoverMapper.selectCount(new QueryWrapper<HandoverOrder>()
                .eq("batch_id", batch.getId()));
        if (existing != null && existing > 0) {
            throw new BusinessException(String.format(
                    "批次 %s 已完成分仓拆单（现有交接单 %d 张），不能重复拆单；原交接单仍可继续核验/交接",
                    batch.getBatchNo(), existing));
        }

        List<HandoverSplitRequest.Item> items = req.getItems();
        List<String> reasons = new ArrayList<>();
        int sumBoxes = 0;
        Set<String> seen = new HashSet<>();
        Set<String> duplicated = new HashSet<>();

        for (int i = 0; i < items.size(); i++) {
            HandoverSplitRequest.Item item = items.get(i);
            String label = "第" + (i + 1) + "单";

            if (item.getBoxQty() == null || item.getBoxQty() <= 0) {
                reasons.add(label + "箱数缺失或不是正数");
            } else {
                sumBoxes += item.getBoxQty();
            }
            if (item.getSealNo() == null || item.getSealNo().trim().isEmpty()) {
                reasons.add(label + "封签号缺失");
            } else if (!seen.add(item.getSealNo().trim())) {
                duplicated.add(item.getSealNo().trim());
            }
            if (item.getHandoverTemperature() == null) {
                reasons.add(label + "交接温度缺失（温度缺失禁止提交）");
            }
            if (item.getReceiverName() == null || item.getReceiverName().trim().isEmpty()) {
                reasons.add(label + "接收人缺失");
            }
        }

        // 箱数平衡：合计必须恰好等于批次派车时固化的总箱数
        if (sumBoxes != batch.getTotalBoxes()) {
            reasons.add(String.format("箱数不平：%d 张交接单箱数合计 %d 箱，与批次总箱数 %d 箱不符（差额 %d 箱，%s）",
                    items.size(), sumBoxes, batch.getTotalBoxes(), sumBoxes - batch.getTotalBoxes(),
                    sumBoxes > batch.getTotalBoxes() ? "超出" : "不足"));
        }
        if (!duplicated.isEmpty()) {
            reasons.add("封签重复：封签号 " + String.join("、", duplicated)
                    + " 在本次提交的交接单中重复出现，每个封签只能归属一张交接单");
        }

        if (!reasons.isEmpty()) {
            throw new BusinessException("分仓拆单校验未通过，已禁止提交：" + String.join("；", reasons));
        }

        List<HandoverOrder> orders = new ArrayList<>();
        List<String> briefs = new ArrayList<>();
        for (HandoverSplitRequest.Item item : items) {
            HandoverOrder order = new HandoverOrder();
            order.setHandoverNo(generateHandoverNo());
            order.setBatchId(batch.getId());
            order.setWarehouseName(req.getWarehouseName());
            order.setBoxQty(item.getBoxQty());
            order.setSealNo(item.getSealNo().trim());
            order.setHandoverTemperature(item.getHandoverTemperature());
            order.setReceiverName(item.getReceiverName().trim());
            order.setReceiverPhone(item.getReceiverPhone());
            order.setDeliveredFlag(false);
            order.setStatus(CcConstants.HANDOVER_PENDING);
            handoverMapper.insert(order);
            orders.add(order);
            briefs.add(String.format("%s(%d箱/封签%s/%s℃/接收人%s)",
                    order.getHandoverNo(), order.getBoxQty(), order.getSealNo(),
                    order.getHandoverTemperature(), order.getReceiverName()));
        }

        opLogService.log(CcConstants.ENT_HANDOVER, null, batch.getId(), CcConstants.ACT_HANDOVER_SPLIT,
                String.format("批次 %s 在 %s 分仓拆单：一车货拆为 %d 张交接单，箱数合计 %d/%d 平衡。明细：%s",
                        batch.getBatchNo(), req.getWarehouseName(), orders.size(), sumBoxes,
                        batch.getTotalBoxes(), String.join("；", briefs)));
        return orders;
    }

    /**
     * 封签核验（只追加事实）：每次核验新增一行 MATCH/MISMATCH，永不修改/删除。
     * 权限收紧时停止新的核验，但此前已确认（MATCH）的事实原样保留。
     */
    @Transactional
    public HandoverSealVerify verifySeal(SealVerifyRequest req) {
        policyService.requireHandoverAllowed();

        HandoverOrder order = requireHandover(req.getHandoverId());
        if (CcConstants.HANDOVER_ABANDONED.equals(order.getStatus())) {
            throw new BusinessException("交接单 " + order.getHandoverNo() + " 已随批次撤回放弃，不能再核验封签");
        }
        if (CcConstants.HANDOVER_COMPLETED.equals(order.getStatus())) {
            throw new BusinessException("交接单 " + order.getHandoverNo() + " 已完成交接且责任已转移，不能再追加封签核验");
        }

        int confirmedBoxes = req.getBoxQty() == null ? order.getBoxQty() : req.getBoxQty();
        // 核验箱数与声明箱数不一致，即使勾选相符也按不符留痕，防止“勾选 MATCH 掩盖箱数不平”
        boolean boxConsistent = confirmedBoxes == order.getBoxQty();
        boolean match = Boolean.TRUE.equals(req.getMatch()) && boxConsistent;

        HandoverSealVerify verify = new HandoverSealVerify();
        verify.setHandoverId(order.getId());
        verify.setBatchId(order.getBatchId());
        verify.setSealNo(order.getSealNo());
        verify.setBoxQty(confirmedBoxes);
        verify.setResult(match ? CcConstants.SEAL_MATCH : CcConstants.SEAL_MISMATCH);
        verify.setNote(boxConsistent ? req.getNote()
                : String.format("核验箱数 %d 与交接单声明箱数 %d 不一致；%s",
                        confirmedBoxes, order.getBoxQty(), req.getNote() == null ? "" : req.getNote()));
        verify.setVerifiedBy(UserContext.currentName());
        verify.setVerifyTime(LocalDateTime.now());
        sealVerifyMapper.insert(verify);

        // 状态卡口只看“最近一次核验结论”，历史核验行全部保留
        if (match) {
            order.setStatus(CcConstants.HANDOVER_SEAL_VERIFIED);
        } else {
            order.setStatus(CcConstants.HANDOVER_PENDING);
        }
        handoverMapper.updateById(order);

        opLogService.log(CcConstants.ENT_HANDOVER, order.getId(), order.getBatchId(),
                CcConstants.ACT_SEAL_VERIFY,
                String.format("交接单 %s 封签 %s 核验：结论=%s，确认箱数=%d（声明 %d），核验人=%s，备注=%s。"
                                + "核验事实只追加，权限收紧/停止推进均不删除已确认记录",
                        order.getHandoverNo(), order.getSealNo(), verify.getResult(),
                        confirmedBoxes, order.getBoxQty(), verify.getVerifiedBy(),
                        verify.getNote() == null ? "" : verify.getNote()));
        return verify;
    }

    /**
     * 完成交接：封签核验通过（最近一次 MATCH 且箱数一致）后，责任主体随单转移到接收仓/接收人。
     * 未完成核验或最近核验不符的箱子禁止完成交接。
     */
    @Transactional
    public HandoverOrder complete(Long handoverId) {
        policyService.requireHandoverAllowed();

        HandoverOrder order = requireHandover(handoverId);
        TransportBatch batch = requireBatch(order.getBatchId());
        if (CcConstants.HANDOVER_ABANDONED.equals(order.getStatus())) {
            throw new BusinessException("交接单 " + order.getHandoverNo() + " 已随批次撤回放弃");
        }
        if (CcConstants.HANDOVER_COMPLETED.equals(order.getStatus())) {
            throw new BusinessException("交接单 " + order.getHandoverNo() + " 已完成交接，责任主体已随单转移");
        }

        HandoverSealVerify latest = latestVerify(order.getId());
        if (latest == null) {
            throw new BusinessException(String.format(
                    "交接单 %s 封签 %s 尚未核验：未完成核验的箱子不能交接、不能继续派送",
                    order.getHandoverNo(), order.getSealNo()));
        }
        if (!CcConstants.SEAL_MATCH.equals(latest.getResult())) {
            throw new BusinessException(String.format(
                    "交接单 %s 封签 %s 最近一次核验结论为 %s（%s），封签核验未通过，禁止完成交接",
                    order.getHandoverNo(), order.getSealNo(), latest.getResult(),
                    latest.getNote() == null ? "" : latest.getNote()));
        }

        String previousOwner = batch.getVehicleId() == null ? "原承运方"
                : "原承运方（车辆ID=" + batch.getVehicleId() + "）";
        // 责任主体随单转移：固化接收仓与接收人，原批次仍保留全部节点
        order.setStatus(CcConstants.HANDOVER_COMPLETED);
        order.setHandoverTime(LocalDateTime.now());
        order.setHandoverOwnerOrg(order.getWarehouseName());
        order.setHandoverOwnerName(order.getReceiverName());
        handoverMapper.updateById(order);

        opLogService.log(CcConstants.ENT_HANDOVER, order.getId(), batch.getId(),
                CcConstants.ACT_HANDOVER_COMPLETE,
                String.format("交接单 %s 交接完成：%d 箱（封签 %s，交接温度 %s℃）责任主体由 %s 转移至 %s/%s；"
                                + "原运输批次 %s 仍保留派车/发运/温度/交接全部节点可追溯",
                        order.getHandoverNo(), order.getBoxQty(), order.getSealNo(),
                        order.getHandoverTemperature(), previousOwner,
                        order.getHandoverOwnerOrg(), order.getHandoverOwnerName(), batch.getBatchNo()));
        return order;
    }

    /**
     * 放行下游派送：未完成核验、未完成交接（责任未转移）的箱子不能继续派送。
     */
    @Transactional
    public HandoverOrder releaseForDelivery(Long handoverId) {
        policyService.requireHandoverAllowed();

        HandoverOrder order = requireHandover(handoverId);
        if (Boolean.TRUE.equals(order.getDeliveredFlag())) {
            throw new BusinessException("交接单 " + order.getHandoverNo() + " 已放行下游派送，不能重复放行");
        }
        if (CcConstants.HANDOVER_ABANDONED.equals(order.getStatus())) {
            throw new BusinessException("交接单 " + order.getHandoverNo() + " 已随批次撤回放弃，不能派送");
        }

        HandoverSealVerify latest = latestVerify(order.getId());
        if (latest == null || !CcConstants.SEAL_MATCH.equals(latest.getResult())) {
            throw new BusinessException(String.format(
                    "交接单 %s 封签 %s 未完成核验或核验未通过：未完成核验的箱子不能继续派送",
                    order.getHandoverNo(), order.getSealNo()));
        }
        if (!CcConstants.HANDOVER_COMPLETED.equals(order.getStatus())) {
            throw new BusinessException(String.format(
                    "交接单 %s 尚未完成交接（当前状态 %s），责任主体未随单转移，不能继续派送",
                    order.getHandoverNo(), order.getStatus()));
        }

        order.setDeliveredFlag(true);
        order.setDeliveredTime(LocalDateTime.now());
        handoverMapper.updateById(order);

        opLogService.log(CcConstants.ENT_HANDOVER, order.getId(), order.getBatchId(),
                CcConstants.ACT_HANDOVER_DELIVER,
                String.format("交接单 %s 放行下游派送：%d 箱封签 %s 已核验相符、交接已完成，"
                                + "当前责任主体=%s/%s",
                        order.getHandoverNo(), order.getBoxQty(), order.getSealNo(),
                        order.getHandoverOwnerOrg(), order.getHandoverOwnerName()));
        return order;
    }

    /**
     * 批次撤回连带处理：尚未完成交接（责任未转移）的交接单置 ABANDONED；
     * 已完成交接的责任主体已随单转移，不改其状态。任何封签核验事实都保留不删。
     */
    @Transactional
    public void abandonForBatch(TransportBatch batch) {
        List<HandoverOrder> orders = listByBatch(batch.getId());
        for (HandoverOrder order : orders) {
            if (CcConstants.HANDOVER_COMPLETED.equals(order.getStatus())
                    || CcConstants.HANDOVER_ABANDONED.equals(order.getStatus())) {
                continue;
            }
            order.setStatus(CcConstants.HANDOVER_ABANDONED);
            handoverMapper.updateById(order);
            opLogService.log(CcConstants.ENT_HANDOVER, order.getId(), batch.getId(),
                    CcConstants.ACT_HANDOVER_ABANDON,
                    String.format("批次 %s 撤回：交接单 %s（%d箱/封签%s）连带放弃，原始交接单与封签核验记录全部保留",
                            batch.getBatchNo(), order.getHandoverNo(), order.getBoxQty(), order.getSealNo()));
        }
    }

    // ---------------- 查询 ----------------

    public HandoverOrder requireHandover(Long id) {
        HandoverOrder order = handoverMapper.selectById(id);
        if (order == null) {
            throw new BusinessException("分仓交接单不存在: " + id);
        }
        return order;
    }

    public List<HandoverOrder> listByBatch(Long batchId) {
        return handoverMapper.selectList(new QueryWrapper<HandoverOrder>()
                .eq("batch_id", batchId)
                .orderByAsc("id"));
    }

    /** 封签核验事实（只追加）：按时间/插入顺序返回全部行 */
    public List<HandoverSealVerify> listVerifies(Long handoverId) {
        return sealVerifyMapper.selectList(new QueryWrapper<HandoverSealVerify>()
                .eq("handover_id", handoverId)
                .orderByAsc("id"));
    }

    public List<HandoverSealVerify> listVerifiesByBatch(Long batchId) {
        return sealVerifyMapper.selectList(new QueryWrapper<HandoverSealVerify>()
                .eq("batch_id", batchId)
                .orderByAsc("id"));
    }

    /** 最近一次封签核验结论（卡口口径）；无核验记录返回 null */
    private HandoverSealVerify latestVerify(Long handoverId) {
        List<HandoverSealVerify> verifies = sealVerifyMapper.selectList(new QueryWrapper<HandoverSealVerify>()
                .eq("handover_id", handoverId)
                .orderByDesc("id")
                .last("LIMIT 1"));
        return verifies.isEmpty() ? null : verifies.get(0);
    }

    private TransportBatch requireBatch(Long batchId) {
        TransportBatch batch = batchMapper.selectById(batchId);
        if (batch == null) {
            throw new BusinessException("运输批次不存在: " + batchId);
        }
        return batch;
    }

    /** 货到中转仓发生在途阶段：必须已发运（含异常挂起），未发运/已签收/已撤回不可拆单 */
    private void requireArrivedAtWarehouse(TransportBatch batch) {
        if (CcConstants.BATCH_REVOKED.equals(batch.getStatus())) {
            throw new BusinessException("批次 " + batch.getBatchNo() + " 已撤回作废，不能分仓交接");
        }
        if (CcConstants.BATCH_SIGNED.equals(batch.getStatus())) {
            throw new BusinessException("批次 " + batch.getBatchNo() + " 已签收闭环，无需分仓交接");
        }
        if (CcConstants.BATCH_DISPATCHED.equals(batch.getStatus())) {
            throw new BusinessException("批次 " + batch.getBatchNo() + " 尚未发运，货到中转仓后才能分仓拆单");
        }
    }

    /** 稳定交接单号：JJ + 序列（单调唯一，重启不复用） */
    private String generateHandoverNo() {
        return "JJ" + sequenceMapper.nextHandoverNo();
    }
}
