package com.evops.coldchain.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.evops.coldchain.CcConstants;
import com.evops.coldchain.entity.Drug;
import com.evops.coldchain.entity.ExceptionEvent;
import com.evops.coldchain.entity.ExceptionEvidence;
import com.evops.coldchain.entity.OpLog;
import com.evops.coldchain.entity.Route;
import com.evops.coldchain.entity.RouteSegmentSnap;
import com.evops.coldchain.entity.RouteVersion;
import com.evops.coldchain.entity.ShipmentOrder;
import com.evops.coldchain.entity.TemperatureLog;
import com.evops.coldchain.entity.TransportBatch;
import com.evops.coldchain.entity.Vehicle;
import com.evops.coldchain.mapper.DrugMapper;
import com.evops.coldchain.mapper.ExceptionEvidenceMapper;
import com.evops.coldchain.mapper.RouteMapper;
import com.evops.coldchain.mapper.RouteVersionMapper;
import com.evops.coldchain.mapper.ShipmentOrderMapper;
import com.evops.coldchain.mapper.TransportBatchMapper;
import com.evops.coldchain.mapper.VehicleMapper;
import com.evops.common.BusinessException;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 台账聚合查询：一个批次一张完整台账——
 * 批次（含档案快照）、发货单、车辆、温度留痕、异常+凭证、全链路操作日志，
 * 并对照药品档案当前版本，直观呈现“按原版本核对、档案可先行变更”。
 */
@Service
public class LedgerService {

    @Resource
    private TransportBatchMapper batchMapper;
    @Resource
    private ShipmentOrderMapper orderMapper;
    @Resource
    private VehicleMapper vehicleMapper;
    @Resource
    private DrugMapper drugMapper;
    @Resource
    private RouteMapper routeMapper;
    @Resource
    private RouteVersionMapper routeVersionMapper;
    @Resource
    private ExceptionEvidenceMapper evidenceMapper;
    @Resource
    private com.evops.coldchain.mapper.ProbeCertMapper probeCertMapper;
    @Resource
    private TransportBatchService batchService;
    @Resource
    private RouteService routeService;
    @Resource
    private HandoverService handoverService;
    @Resource
    private OpLogService opLogService;

    public Map<String, Object> batchLedger(Long batchId) {
        TransportBatch batch = batchMapper.selectById(batchId);
        if (batch == null) {
            throw new BusinessException("运输批次不存在: " + batchId);
        }
        ShipmentOrder order = orderMapper.selectById(batch.getOrderId());
        Vehicle vehicle = vehicleMapper.selectById(batch.getVehicleId());
        Drug drug = drugMapper.selectById(batch.getDrugId());
        Route route = batch.getRouteId() == null ? null : routeMapper.selectById(batch.getRouteId());
        RouteVersion routeVersion = batch.getRouteVersionId() == null
                ? null : routeVersionMapper.selectById(batch.getRouteVersionId());
        // 批次持有的路线温区分段快照（发运时固化，验收可逐段追到 t_cc_route_segment_snap）
        List<RouteSegmentSnap> routeSegmentSnaps = batch.getRouteVersionId() == null
                ? new ArrayList<>() : routeService.listSnapshotsByBatch(batchId);

        List<TemperatureLog> temps = batchService.listTemperatures(batchId);
        List<ExceptionEvent> exceptions = batchService.listExceptions(batchId);

        List<Map<String, Object>> exceptionList = new ArrayList<>();
        for (ExceptionEvent event : exceptions) {
            List<ExceptionEvidence> evidences = evidenceMapper.selectList(new QueryWrapper<ExceptionEvidence>()
                    .eq("exception_id", event.getId())
                    .orderByAsc("id"));
            Map<String, Object> em = new LinkedHashMap<>();
            em.put("exception", event);
            em.put("evidences", evidences);
            exceptionList.add(em);
        }

        List<OpLog> opLogs = opLogService.listByBatch(batchId);

        Map<String, Object> ledger = new LinkedHashMap<>();
        ledger.put("batch", batch);
        ledger.put("order", order);
        ledger.put("vehicle", vehicle);
        ledger.put("drug", drug);
        // 路线温区分段：版本引用 + 发运时固化的逐段快照（温度判定/责任追溯以此为准）
        if (batch.getRouteVersionId() != null) {
            Map<String, Object> routeInfo = new LinkedHashMap<>();
            routeInfo.put("route", route);
            routeInfo.put("version", routeVersion);
            routeInfo.put("season", batch.getRouteSeason());
            routeInfo.put("frozenVersionNo", batch.getRouteVersionNo());
            routeInfo.put("segmentSnapshots", routeSegmentSnaps);
            ledger.put("route", routeInfo);
        }
        // 版本对照：核对依据以批次快照为准，drug.currentVersion 仅展示档案已演进到的版本
        Map<String, Object> versionCheck = new LinkedHashMap<>();
        versionCheck.put("batchFrozenVersion", batch.getDrugVersionNo());
        versionCheck.put("drugCurrentVersion", drug == null ? null : drug.getCurrentVersion());
        versionCheck.put("checkByFrozenVersion", true);
        ledger.put("versionCheck", versionCheck);
        // 车载探头校准凭证快照（发运时固化；途中替换探头/作废凭证均不改变本批次）
        if (batch.getProbeId() != null) {
            Map<String, Object> probeInfo = new LinkedHashMap<>();
            probeInfo.put("probeId", batch.getProbeId());
            probeInfo.put("bindId", batch.getProbeBindId());
            probeInfo.put("certId", batch.getProbeCertId());
            probeInfo.put("serialNo", batch.getProbeSerialNoSnap());
            probeInfo.put("rangeMin", batch.getProbeRangeMinSnap());
            probeInfo.put("rangeMax", batch.getProbeRangeMaxSnap());
            probeInfo.put("calibrateOrg", batch.getProbeCalibrateOrgSnap());
            probeInfo.put("certNo", batch.getProbeCertNoSnap());
            probeInfo.put("validUntil", batch.getProbeValidUntilSnap());
            probeInfo.put("sha256", batch.getProbeSha256Snap());
            probeInfo.put("certAtShip", batch.getProbeCertId() == null
                    ? null : probeCertMapper.selectById(batch.getProbeCertId()));
            ledger.put("probe", probeInfo);
        }
        ledger.put("temperatures", temps);
        ledger.put("exceptions", exceptionList);

        // 分仓交接节点：交接单（责任主体随单转移）+ 每单只追加的封签核验事实，
        // 原运输批次台账仍能串起派车/发运/温度/交接全部节点
        List<com.evops.coldchain.entity.HandoverOrder> handovers = handoverService.listByBatch(batchId);
        List<Map<String, Object>> handoverList = new ArrayList<>();
        int handedBoxes = 0;
        for (com.evops.coldchain.entity.HandoverOrder ho : handovers) {
            Map<String, Object> hm = new LinkedHashMap<>();
            hm.put("handover", ho);
            hm.put("sealVerifies", handoverService.listVerifies(ho.getId()));
            handoverList.add(hm);
            if (CcConstants.HANDOVER_COMPLETED.equals(ho.getStatus())) {
                handedBoxes += ho.getBoxQty();
            }
        }
        if (!handovers.isEmpty()) {
            Map<String, Object> handoverInfo = new LinkedHashMap<>();
            handoverInfo.put("totalBoxes", batch.getTotalBoxes());
            handoverInfo.put("orders", handoverList);
            handoverInfo.put("completedBoxes", handedBoxes);
            ledger.put("handovers", handoverInfo);
        }

        ledger.put("opLogs", opLogs);
        return ledger;
    }
}
