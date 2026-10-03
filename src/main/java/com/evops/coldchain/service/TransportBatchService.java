package com.evops.coldchain.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.evops.coldchain.CcConstants;
import com.evops.coldchain.dto.DispatchRequest;
import com.evops.coldchain.dto.EvidenceAppendRequest;
import com.evops.coldchain.dto.ExceptionMarkRequest;
import com.evops.coldchain.dto.RevokeRequest;
import com.evops.coldchain.dto.SignRequest;
import com.evops.coldchain.dto.TempReportRequest;
import com.evops.coldchain.entity.DrugVersion;
import com.evops.coldchain.entity.ExceptionEvent;
import com.evops.coldchain.entity.ExceptionEvidence;
import com.evops.coldchain.entity.RouteSegmentSnap;
import com.evops.coldchain.entity.RouteVersion;
import com.evops.coldchain.entity.ShipmentOrder;
import com.evops.coldchain.entity.TemperatureLog;
import com.evops.coldchain.entity.TransportBatch;
import com.evops.coldchain.entity.Vehicle;
import com.evops.coldchain.entity.VehicleProbe;
import com.evops.coldchain.mapper.ExceptionEventMapper;
import com.evops.coldchain.mapper.ExceptionEvidenceMapper;
import com.evops.coldchain.mapper.SequenceMapper;
import com.evops.coldchain.mapper.ShipmentOrderMapper;
import com.evops.coldchain.mapper.TemperatureLogMapper;
import com.evops.coldchain.mapper.TransportBatchMapper;
import com.evops.common.BusinessException;
import com.evops.common.UserContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 运输批次服务（冷链台账核心）。
 *
 * 版本与凭证规则：
 * - 派车时把发货单锁定的档案版本复制为批次快照；此后核对/签收看快照，不看档案现值。
 * - 温度记录、异常事件、凭证全部追加（INSERT only），不覆盖既有凭证。
 * - 签收 = 按快照核对实物 + 无未处理异常，通过后状态 SIGNED 闭环。
 * - 撤回只置 REVOKED 状态，原始记录全部保留。
 */
@Service
public class TransportBatchService {

    @Resource
    private TransportBatchMapper batchMapper;
    @Resource
    private ShipmentOrderMapper orderMapper;
    @Resource
    private TemperatureLogMapper temperatureMapper;
    @Resource
    private ExceptionEventMapper exceptionMapper;
    @Resource
    private ExceptionEvidenceMapper evidenceMapper;
    @Resource
    private SequenceMapper sequenceMapper;
    @Resource
    private OrderService orderService;
    @Resource
    private VehicleService vehicleService;
    @Resource
    private DrugService drugService;
    @Resource
    private RouteService routeService;
    @Resource
    private ProbeService probeService;
    @Resource
    private OpLogService opLogService;

    /** 派车：生成稳定批次号，固化档案快照，关联发货单与车辆 */
    @Transactional
    public TransportBatch dispatch(DispatchRequest req) {
        ShipmentOrder order = orderService.requireOrder(req.getOrderId());
        if (!CcConstants.ORDER_CREATED.equals(order.getStatus())) {
            throw new BusinessException("发货单当前状态不允许派车: " + order.getStatus());
        }
        Vehicle vehicle = vehicleService.requireActiveVehicle(req.getVehicleId());

        // 取发货单锁定的原版本——档案之后再变更也与本批次无关
        DrugVersion version = drugService.requireVersion(order.getDrugId(), order.getDrugVersionNo());

        // 路线温区分段：派车只登记走哪条路线，季节版本的选定与逐段快照推迟到“发运”。
        // 因此派车后、发运前发布的新版本仍会作用于该未发运批次；一旦发运即与路线版本脱钩。
        if (req.getRouteId() != null) {
            routeService.requireActiveRoute(req.getRouteId());
        }

        TransportBatch batch = new TransportBatch();
        batch.setBatchNo(generateBatchNo());
        batch.setOrderId(order.getId());
        batch.setVehicleId(vehicle.getId());
        batch.setDrugId(order.getDrugId());
        // 档案快照
        batch.setDrugVersionNo(version.getVersionNo());
        batch.setApprovalNoSnap(version.getApprovalNo());
        batch.setTempMinSnap(version.getTempMin());
        batch.setTempMaxSnap(version.getTempMax());
        batch.setPackageSpecSnap(version.getPackageSpec());
        // 路线引用（季节版本与分段在发运时才选定并快照，故此处 version* 留空）
        batch.setRouteId(req.getRouteId());
        // 运输信息
        batch.setReceiverOrg(order.getReceiverOrg());
        batch.setShipQty(order.getShipQty());
        batch.setDispatchTime(LocalDateTime.now());
        batch.setHasTempExcursion(false);
        batch.setStatus(CcConstants.BATCH_DISPATCHED);
        batchMapper.insert(batch);

        // 发货单同步为已派车
        order.setStatus(CcConstants.ORDER_DISPATCHED);
        orderMapper.updateById(order);

        opLogService.log(CcConstants.ENT_BATCH, batch.getId(), batch.getId(), CcConstants.ACT_DISPATCH,
                String.format("批次 %s 派车：发货单=%s，车辆=%s，按档案 V%d 快照（批准文号=%s，温区=%s~%s℃，包装=%s）%s",
                        batch.getBatchNo(), order.getOrderNo(), vehicle.getPlateNo(),
                        version.getVersionNo(), version.getApprovalNo(),
                        version.getTempMin(), version.getTempMax(), version.getPackageSpec(),
                        req.getRouteId() == null ? ""
                                : "；已指定运输路线，温区分段版本将在发运时按当时季节选定并逐段快照"));
        return batch;
    }

    /** 发运：已派车 -> 在途；发运瞬间选定路线季节生效版本并逐段固化快照 */
    @Transactional
    public TransportBatch ship(Long batchId) {
        TransportBatch batch = requireActiveBatch(batchId);
        if (!CcConstants.BATCH_DISPATCHED.equals(batch.getStatus())) {
            throw new BusinessException("仅已派车批次允许发运，当前状态: " + batch.getStatus());
        }

        // 发运前卡口：车辆必须绑定仍在有效期内的持证探头（接口与页面共用这一道校验）
        VehicleProbe probeBinding = probeService.requireValidBindingForShip(batch.getVehicleId());

        int snapCount = 0;
        RouteVersion routeVersion = null;
        if (batch.getRouteId() != null) {
            if (batch.getRouteVersionId() == null) {
                // 未发运批次：发运瞬间取当时季节的生效版本（派车后发布的新版本在此刻生效）
                routeVersion = routeService.selectEffectiveAt(batch.getRouteId(), LocalDateTime.now());
                batch.setRouteVersionId(routeVersion.getId());
                batch.setRouteSeason(routeVersion.getSeason());
                batch.setRouteVersionNo(routeVersion.getVersionNo());
                snapCount = routeService.snapshotForBatch(batch.getId(), routeVersion.getId()).size();
            }
        }

        // 车载探头校准凭证逐字段快照：发运后探头替换/证书作废均不影响在途批次与温度归属
        batch.setProbeId(probeBinding.getProbeId());
        batch.setProbeBindId(probeBinding.getId());
        batch.setProbeCertId(probeBinding.getCertId());
        batch.setProbeSerialNoSnap(probeBinding.getSerialNoSnap());
        batch.setProbeRangeMinSnap(probeBinding.getRangeMinSnap());
        batch.setProbeRangeMaxSnap(probeBinding.getRangeMaxSnap());
        batch.setProbeCalibrateOrgSnap(probeBinding.getCalibrateOrgSnap());
        batch.setProbeCertNoSnap(probeBinding.getCertNoSnap());
        batch.setProbeValidUntilSnap(probeBinding.getValidUntilSnap());
        batch.setProbeSha256Snap(probeBinding.getSha256Snap());

        batch.setStatus(CcConstants.BATCH_SHIPPED);
        batch.setShipTime(LocalDateTime.now());
        batchMapper.updateById(batch);

        ShipmentOrder order = orderService.requireOrder(batch.getOrderId());
        order.setStatus(CcConstants.ORDER_SHIPPED);
        orderMapper.updateById(order);

        opLogService.log(CcConstants.ENT_BATCH, batchId, batchId, CcConstants.ACT_SHIP,
                "批次 " + batch.getBatchNo() + " 发运在途"
                        + String.format("；车载探头序列号=%s，量程=%s~%s℃，校准机构=%s，证书编号=%s，有效期至=%s，摘要=%s（发运时固化，途中替换不回写）",
                                probeBinding.getSerialNoSnap(), probeBinding.getRangeMinSnap(),
                                probeBinding.getRangeMaxSnap(), probeBinding.getCalibrateOrgSnap(),
                                probeBinding.getCertNoSnap(), probeBinding.getValidUntilSnap(),
                                probeBinding.getSha256Snap())
                        + (routeVersion == null ? "" : String.format(
                                "；路线季节版本=%s V%d，逐段固化温区分段 %d 段（发运后版本变更不影响本批次）",
                                routeVersion.getSeason(), routeVersion.getVersionNo(), snapCount)));
        return batch;
    }

    /**
     * 温度上报：按测温时间追加留痕。
     * - 无路线批次：以批次档案快照温区判定越限（V1 口径）。
     * - 有路线温区分段快照的批次：按上报里程命中分段，以该段允许温区判定；
     *   同一段内“连续越限”持续时间不超过该段缓冲时长则只留痕、不开异常；
     *   一旦超过缓冲时长，自动追加一条温度异常事件（定位到该段责任人）。
     * 不改动任何历史温度行。
     */
    @Transactional
    public TemperatureLog reportTemperature(TempReportRequest req) {
        TransportBatch batch = requireActiveBatch(req.getBatchId());
        // 派车后、闭环前（含异常挂起）允许持续上报；已签收/已撤回不可上报
        if (CcConstants.BATCH_SIGNED.equals(batch.getStatus())) {
            throw new BusinessException("批次已签收闭环，不能再上报温度");
        }
        if (!CcConstants.BATCH_DISPATCHED.equals(batch.getStatus())
                && !CcConstants.BATCH_SHIPPED.equals(batch.getStatus())
                && !CcConstants.BATCH_EXCEPTION.equals(batch.getStatus())) {
            throw new BusinessException("批次当前状态不允许上报温度: " + batch.getStatus());
        }

        List<RouteSegmentSnap> snaps = batch.getRouteVersionId() == null
                ? java.util.Collections.emptyList()
                : routeService.listSnapshotsByBatch(batch.getId());

        RouteSegmentSnap hit = null;
        BigDecimal min;
        BigDecimal max;
        Integer bufferMinutes = null;
        if (!snaps.isEmpty()) {
            if (req.getMileageKm() == null) {
                throw new BusinessException("该批次已配置路线温区分段，温度上报必须携带当前里程 mileageKm");
            }
            hit = matchSegment(snaps, req.getMileageKm());
            if (hit == null) {
                throw new BusinessException(String.format(
                        "上报里程 %skm 未落在路线任一分段内（0~%skm），无法判定温区",
                        req.getMileageKm(), snaps.get(snaps.size() - 1).getToKm()));
            }
            min = hit.getTempMin();
            max = hit.getTempMax();
            bufferMinutes = hit.getBufferMinutes();
        } else {
            min = batch.getTempMinSnap();
            max = batch.getTempMaxSnap();
        }

        boolean inRange = req.getTemperature().compareTo(min) >= 0
                && req.getTemperature().compareTo(max) <= 0;

        TemperatureLog log = new TemperatureLog();
        log.setBatchId(batch.getId());
        log.setRecordTime(req.getRecordTime());
        log.setTemperature(req.getTemperature());
        log.setInRange(inRange);
        log.setRecordedBy(req.getRecordedBy());
        log.setRemark(req.getRemark());
        log.setMileageKm(req.getMileageKm());
        log.setSegmentSeq(hit == null ? null : hit.getSegmentSeq());
        // 测点归属发运时固化的探头；该字段只随新行追加，替换探头后历史温度行永不回写
        log.setProbeId(batch.getProbeId());
        temperatureMapper.insert(log);

        String scope = hit == null
                ? String.format("快照温区 %s~%s℃", min, max)
                : String.format("里程 %skm 第%d段（%s~%skm，责任人=%s）允许温区 %s~%s℃，缓冲 %d 分钟",
                        req.getMileageKm(), hit.getSegmentSeq(), hit.getFromKm(), hit.getToKm(),
                        hit.getOwnerName(), min, max, bufferMinutes);
        opLogService.log(CcConstants.ENT_TEMPERATURE, log.getId(), batch.getId(), CcConstants.ACT_TEMP_REPORT,
                String.format("批次 %s 温度留痕：%s = %s℃（%s，%s；采集探头=%s）",
                        batch.getBatchNo(), req.getRecordTime(), req.getTemperature(), scope,
                        inRange ? "正常" : "越限", batch.getProbeSerialNoSnap()));

        if (!inRange) {
            // 缓冲判定：同段连续越限（不含正常点打断）是否已超过缓冲时长
            boolean bufferExceeded = bufferMinutes == null
                    || isContinuousExcursionBeyondBuffer(batch.getId(), log, hit, bufferMinutes);

            if (bufferExceeded) {
                String desc = hit == null
                        ? String.format("系统自动标记：%s 测温 %s℃ 超出快照温区 %s~%s℃",
                                req.getRecordTime(), req.getTemperature(), min, max)
                        : String.format("系统自动标记：%s 里程 %skm 第%d段（责任人=%s）测温 %s℃ 超出允许温区 %s~%s℃，连续越限已超过缓冲 %d 分钟",
                                req.getRecordTime(), req.getMileageKm(), hit.getSegmentSeq(),
                                hit.getOwnerName(), req.getTemperature(), min, max, bufferMinutes);
                markExceptionInternal(batch, CcConstants.EXC_TEMP, desc, req.getRecordTime(),
                        hit == null ? null : hit.getSegmentSeq());
            }
        }
        return log;
    }

    /** 按里程命中分段 [fromKm, toKm) */
    private RouteSegmentSnap matchSegment(List<RouteSegmentSnap> snaps, BigDecimal mileage) {
        if (mileage.compareTo(BigDecimal.ZERO) < 0) {
            return null;
        }
        for (RouteSegmentSnap s : snaps) {
            boolean geFrom = mileage.compareTo(s.getFromKm()) >= 0;
            boolean ltTo = mileage.compareTo(s.getToKm()) < 0;
            // 末段终点（=总里程）纳入最后一段，避免到达终点时无法判定
            boolean atEnd = mileage.compareTo(s.getToKm()) == 0 && s.getSegmentSeq().equals(snaps.size());
            if (geFrom && (ltTo || atEnd)) {
                return s;
            }
        }
        return null;
    }

    /**
     * 判断本次越限是否使“同段连续越限”持续时间超过缓冲时长。
     * 沿时间倒序回看本批温度：遇到正常点或越过段边界即打断连续越限；
     * 连续起点为空（越限前无记录）时，保守按“本次即起点”计算。
     */
    private boolean isContinuousExcursionBeyondBuffer(Long batchId, TemperatureLog current,
                                                      RouteSegmentSnap hit, int bufferMinutes) {
        List<TemperatureLog> history = temperatureMapper.selectList(new QueryWrapper<TemperatureLog>()
                .eq("batch_id", batchId)
                .orderByAsc("record_time")
                .orderByAsc("id"));

        LocalDateTime streakStart = current.getRecordTime();
        for (int i = history.size() - 1; i >= 0; i--) {
            TemperatureLog t = history.get(i);
            if (t.getId().equals(current.getId())) {
                streakStart = t.getRecordTime();
                continue;
            }
            boolean sameSegment = hit == null
                    || (t.getSegmentSeq() != null && t.getSegmentSeq().equals(hit.getSegmentSeq()));
            if (!sameSegment || Boolean.TRUE.equals(t.getInRange())) {
                break; // 正常点或切换分段，连续越限被打断
            }
            streakStart = t.getRecordTime();
        }
        long minutes = Duration.between(streakStart, current.getRecordTime()).toMinutes();
        return minutes > bufferMinutes;
    }

    /** 人工异常标记（追加，不触碰已有异常/凭证） */
    @Transactional
    public ExceptionEvent markException(ExceptionMarkRequest req) {
        TransportBatch batch = requireActiveBatch(req.getBatchId());
        String type = normalizeType(req.getExceptionType());
        LocalDateTime occurred = req.getOccurredTime() == null ? LocalDateTime.now() : req.getOccurredTime();
        return markExceptionInternal(batch, type, req.getDescription(), occurred, null);
    }

    private ExceptionEvent markExceptionInternal(TransportBatch batch, String type, String description,
                                                 LocalDateTime occurred, Integer segmentSeq) {
        ExceptionEvent event = new ExceptionEvent();
        event.setBatchId(batch.getId());
        event.setExceptionType(type);
        event.setDescription(description);
        event.setOccurredTime(occurred);
        event.setStatus(CcConstants.EXC_OPEN);
        event.setSegmentSeq(segmentSeq);
        exceptionMapper.insert(event);

        // 批次进入异常挂起（已签收/已撤回的不允许走到这里，前置已拦截）
        if (!CcConstants.BATCH_EXCEPTION.equals(batch.getStatus())) {
            batch.setStatus(CcConstants.BATCH_EXCEPTION);
        }
        if (CcConstants.EXC_TEMP.equals(type)) {
            batch.setHasTempExcursion(true);
        }
        batchMapper.updateById(batch);

        opLogService.log(CcConstants.ENT_EXCEPTION, event.getId(), batch.getId(), CcConstants.ACT_EXCEPTION_MARK,
                String.format("批次 %s 追加异常[%s]：%s", batch.getBatchNo(), type, description));
        return event;
    }

    /** 追加凭证：永远新增一行，不覆盖/更新/删除已有凭证 */
    @Transactional
    public ExceptionEvidence appendEvidence(EvidenceAppendRequest req) {
        ExceptionEvent event = requireException(req.getExceptionId());
        requireActiveBatch(event.getBatchId());

        ExceptionEvidence evidence = new ExceptionEvidence();
        evidence.setExceptionId(event.getId());
        evidence.setFileName(req.getFileName());
        evidence.setFileUrl(req.getFileUrl());
        evidence.setNote(req.getNote());
        evidenceMapper.insert(evidence);

        opLogService.log(CcConstants.ENT_EXCEPTION, event.getId(), event.getBatchId(),
                CcConstants.ACT_EVIDENCE_APPEND,
                String.format("异常#%d 追加凭证：%s %s（备注：%s）",
                        event.getId(),
                        req.getFileName() == null ? "" : req.getFileName(),
                        req.getFileUrl() == null ? "" : req.getFileUrl(),
                        req.getNote() == null ? "" : req.getNote()));
        return evidence;
    }

    /** 异常处理闭环：只追加处理说明（resolve* 字段），原描述与凭证保持不变 */
    @Transactional
    public ExceptionEvent resolveException(Long exceptionId, String resolveNote) {
        ExceptionEvent event = requireException(exceptionId);
        requireActiveBatch(event.getBatchId());
        if (CcConstants.EXC_RESOLVED.equals(event.getStatus())) {
            throw new BusinessException("异常已处理，不能重复处理");
        }
        event.setStatus(CcConstants.EXC_RESOLVED);
        event.setResolveNote(resolveNote);
        event.setResolveBy(UserContext.currentId());
        event.setResolveTime(LocalDateTime.now());
        exceptionMapper.updateById(event);

        opLogService.log(CcConstants.ENT_EXCEPTION, event.getId(), event.getBatchId(),
                CcConstants.ACT_EXCEPTION_RESOLVE,
                "异常#" + event.getId() + " 处理闭环：" + resolveNote);
        return event;
    }

    /**
     * 签收闭环：必须按派车时固化的档案快照逐项核对，
     * 且不存在未处理（OPEN）异常。核对口径不随档案后续变更而改变。
     */
    @Transactional
    public TransportBatch sign(SignRequest req) {
        TransportBatch batch = requireActiveBatch(req.getBatchId());
        if (CcConstants.BATCH_SIGNED.equals(batch.getStatus())) {
            throw new BusinessException("批次已签收，不能重复签收");
        }

        // 1) 实物与快照核对（按原版本）
        if (!batch.getApprovalNoSnap().equals(req.getReceivedApprovalNo())) {
            throw new BusinessException(String.format(
                    "签收核对不符：实物批准文号[%s]与批次快照（原档案 V%d）[%s]不一致",
                    req.getReceivedApprovalNo(), batch.getDrugVersionNo(), batch.getApprovalNoSnap()));
        }
        if (!batch.getPackageSpecSnap().equals(req.getReceivedPackageSpec())) {
            throw new BusinessException(String.format(
                    "签收核对不符：实物包装规格[%s]与批次快照（原档案 V%d）[%s]不一致",
                    req.getReceivedPackageSpec(), batch.getDrugVersionNo(), batch.getPackageSpecSnap()));
        }
        if (!batch.getShipQty().equals(req.getConfirmQty())) {
            throw new BusinessException(String.format(
                    "签收核对不符：实收数量[%d]与发运数量[%d]不一致", req.getConfirmQty(), batch.getShipQty()));
        }

        // 2) 未处理异常拦截
        Long openCount = exceptionMapper.selectCount(new QueryWrapper<ExceptionEvent>()
                .eq("batch_id", batch.getId())
                .eq("status", CcConstants.EXC_OPEN));
        if (openCount != null && openCount > 0) {
            throw new BusinessException("存在 " + openCount + " 条未处理异常，暂不能签收闭环；请先处理或追加凭证");
        }

        // 核对通过，闭环
        batch.setStatus(CcConstants.BATCH_SIGNED);
        batch.setSignBy(req.getSignBy());
        batch.setSignRemark(req.getSignRemark());
        batch.setSignTime(LocalDateTime.now());
        batchMapper.updateById(batch);

        ShipmentOrder order = orderService.requireOrder(batch.getOrderId());
        order.setStatus(CcConstants.ORDER_SIGNED);
        orderMapper.updateById(order);

        opLogService.log(CcConstants.ENT_BATCH, batch.getId(), batch.getId(), CcConstants.ACT_SIGN,
                String.format("批次 %s 由 %s 签收闭环：按原档案 V%d 快照核对一致（批准文号=%s，包装=%s，数量=%d）%s",
                        batch.getBatchNo(), req.getSignBy(), batch.getDrugVersionNo(),
                        batch.getApprovalNoSnap(), batch.getPackageSpecSnap(), batch.getShipQty(),
                        Boolean.TRUE.equals(batch.getHasTempExcursion()) ? "；途中曾越限（异常均已处理）" : ""));
        return batch;
    }

    /**
     * 撤回：仅置 REVOKED 作废标记并留痕。
     * 不删除批次、温度、异常、凭证、日志任何一行。已签收闭环的批次不可撤回。
     */
    @Transactional
    public TransportBatch revoke(RevokeRequest req) {
        TransportBatch batch = requireBatch(req.getBatchId());
        if (CcConstants.BATCH_SIGNED.equals(batch.getStatus())) {
            throw new BusinessException("批次已签收闭环，不能撤回");
        }
        if (CcConstants.BATCH_REVOKED.equals(batch.getStatus())) {
            throw new BusinessException("批次已撤回，不能重复撤回");
        }
        batch.setStatus(CcConstants.BATCH_REVOKED);
        batchMapper.updateById(batch);

        ShipmentOrder order = orderService.requireOrder(batch.getOrderId());
        if (!CcConstants.ORDER_SIGNED.equals(order.getStatus())) {
            order.setStatus(CcConstants.ORDER_REVOKED);
            orderMapper.updateById(order);
        }

        opLogService.log(CcConstants.ENT_BATCH, batch.getId(), batch.getId(), CcConstants.ACT_REVOKE,
                "批次 " + batch.getBatchNo() + " 撤回作废（原始记录全部保留）。原因：" + req.getReason());
        return batch;
    }

    // ---------------- 查询 ----------------

    public TransportBatch requireBatch(Long batchId) {
        TransportBatch batch = batchMapper.selectById(batchId);
        if (batch == null) {
            throw new BusinessException("运输批次不存在: " + batchId);
        }
        return batch;
    }

    private TransportBatch requireActiveBatch(Long batchId) {
        TransportBatch batch = requireBatch(batchId);
        if (CcConstants.BATCH_REVOKED.equals(batch.getStatus())) {
            throw new BusinessException("批次已撤回作废，不能操作: " + batch.getBatchNo());
        }
        return batch;
    }

    public ExceptionEvent requireException(Long exceptionId) {
        ExceptionEvent event = exceptionMapper.selectById(exceptionId);
        if (event == null) {
            throw new BusinessException("异常事件不存在: " + exceptionId);
        }
        return event;
    }

    public List<TemperatureLog> listTemperatures(Long batchId) {
        return temperatureMapper.selectList(new QueryWrapper<TemperatureLog>()
                .eq("batch_id", batchId)
                .orderByAsc("record_time")
                .orderByAsc("id"));
    }

    public List<ExceptionEvent> listExceptions(Long batchId) {
        return exceptionMapper.selectList(new QueryWrapper<ExceptionEvent>()
                .eq("batch_id", batchId)
                .orderByAsc("id"));
    }

    public List<ExceptionEvidence> listEvidence(Long exceptionId) {
        return evidenceMapper.selectList(new QueryWrapper<ExceptionEvidence>()
                .eq("exception_id", exceptionId)
                .orderByAsc("id"));
    }

    public List<TransportBatch> listByOrder(Long orderId) {
        return batchMapper.selectList(new QueryWrapper<TransportBatch>()
                .eq("order_id", orderId)
                .orderByDesc("id"));
    }

    private String normalizeType(String type) {
        if (type == null) {
            return CcConstants.EXC_OTHER;
        }
        switch (type.trim().toUpperCase()) {
            case CcConstants.EXC_TEMP:
            case CcConstants.EXC_DAMAGE:
            case CcConstants.EXC_DELAY:
                return type.trim().toUpperCase();
            default:
                return CcConstants.EXC_OTHER;
        }
    }

    /** 稳定批次号：年-序列（序列由 H2 生成，单调唯一，绝不复用） */
    private String generateBatchNo() {
        return String.format("PC%tY%06d", LocalDateTime.now(), sequenceMapper.nextBatchNo());
    }
}
