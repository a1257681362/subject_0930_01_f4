package com.evops.coldchain;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.evops.coldchain.dto.DispatchRequest;
import com.evops.coldchain.dto.DrugCreateRequest;
import com.evops.coldchain.dto.OrderCreateRequest;
import com.evops.coldchain.dto.ProbeBindRequest;
import com.evops.coldchain.dto.ProbeCreateRequest;
import com.evops.coldchain.dto.VehicleCreateRequest;
import com.evops.coldchain.entity.ColdChainPolicy;
import com.evops.coldchain.entity.Drug;
import com.evops.coldchain.entity.HandoverOrder;
import com.evops.coldchain.entity.HandoverSealVerify;
import com.evops.coldchain.entity.TransportBatch;
import com.evops.coldchain.entity.Vehicle;
import com.evops.coldchain.mapper.ColdChainPolicyMapper;
import com.evops.coldchain.mapper.HandoverOrderMapper;
import com.evops.coldchain.mapper.HandoverSealVerifyMapper;
import com.evops.coldchain.service.DrugService;
import com.evops.coldchain.service.OpLogService;
import com.evops.coldchain.service.OrderService;
import com.evops.coldchain.service.ProbeService;
import com.evops.coldchain.service.TransportBatchService;
import com.evops.coldchain.service.VehicleService;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 冷链批次分仓交接（V4）验收测试。
 *
 * 真实 HTTP 入口（TestRestTemplate + Basic 认证）驱动，再用 Mapper 直查物理表，
 * 确保交接单与封签核验一路追落到 t_cc_handover_order / t_cc_handover_seal_verify：
 * 1) 一车多单拆分、箱数/封签/温度/接收人核验通过落库；
 * 2) 箱数不平/封签重复/温度缺失必须给出具体原因且整单禁止提交（物理表无新增行）；
 * 3) 未完成封签核验不能完成交接、不能继续派送；
 * 4) 封签核验只追加（不符 -> 箱数不符 -> 相符，三行并存，历史不被覆盖）；
 * 5) 交接完成后责任主体随单转移，原运输批次台账仍能追溯全部节点；
 * 6) 冷链权限收紧后分仓交接停止推进（拆/验/交接/放行全卡口），已确认封签事实保留；放开后恢复；
 * 7) 批次撤回连带放弃未交接单，已交接单责任已转移不受影响，核验事实不删。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class HandoverSplitIntegrationTest {

    private static final ObjectMapper M = new ObjectMapper();
    private static final String BASE = "/api/cold-chain";
    private static final LocalDateTime T0 = LocalDateTime.of(2026, 10, 1, 8, 0);

    /** 全局自增序号，保证跨用例药品批准文号/车牌/探头序列号唯一（同一内存库跨用例持久化） */
    private static final AtomicInteger SEQ = new AtomicInteger(7000);

    @LocalServerPort
    private int port;
    @Autowired
    private TestRestTemplate http;

    @Autowired private DrugService drugService;
    @Autowired private VehicleService vehicleService;
    @Autowired private OrderService orderService;
    @Autowired private TransportBatchService batchService;
    @Autowired private ProbeService probeService;
    @Autowired private OpLogService opLogService;

    @Autowired private HandoverOrderMapper handoverMapper;
    @Autowired private HandoverSealVerifyMapper sealVerifyMapper;
    @Autowired private ColdChainPolicyMapper policyMapper;

    private long vehicleId;

    /** 每个用例前把全局冷链权限恢复为放开，避免 RESTRICTED 跨用例残留 */
    @BeforeEach
    void resetPolicy() {
        ColdChainPolicy policy = policyMapper.selectOne(new QueryWrapper<ColdChainPolicy>()
                .eq("policy_key", CcConstants.POLICY_KEY_COLD_CHAIN));
        assertNotNull(policy);
        if (!CcConstants.POLICY_ENABLED.equals(policy.getStatus())) {
            policy.setStatus(CcConstants.POLICY_ENABLED);
            policy.setReason("用例初始化恢复放开");
            policyMapper.updateById(policy);
        }
    }

    // ====================================================================
    // 1) 正常主线：一车两单 -> 封签核验 -> 责任转移 -> 放行派送 -> 批次台账追全节点
    // ====================================================================
    @Test
    void splitMultiOrders_verifyCompleteDeliver_ledgerTracesAllNodes() {
        TransportBatch batch = prepareShippedBatch("V4-主线", 50);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("batchId", batch.getId());
        body.put("warehouseName", "杭州中转仓");
        body.put("items", java.util.Arrays.asList(
                item(30, "SEAL-MAIN-01", 5.10, "王仓管"),
                item(20, "SEAL-MAIN-02", 4.80, "李分拣")));
        JsonNode split = post("/handovers/split", body);
        assertTrue(split.path("success").asBoolean(), split.toString());
        assertEquals(2, split.path("data").size());
        for (JsonNode o : split.path("data")) {
            assertTrue(o.path("handoverNo").asText().startsWith("JJ"));
            assertEquals(CcConstants.HANDOVER_PENDING, o.path("status").asText());
        }
        long id1 = split.path("data").get(0).path("id").asLong();
        long id2 = split.path("data").get(1).path("id").asLong();

        // 物理表落库：交接单两行、箱数合计等于批次总箱数
        List<HandoverOrder> rows = handoverMapper.selectList(new QueryWrapper<HandoverOrder>()
                .eq("batch_id", batch.getId()).orderByAsc("id"));
        assertEquals(2, rows.size());
        assertEquals(50, rows.get(0).getBoxQty() + rows.get(1).getBoxQty());
        assertEquals("杭州中转仓", rows.get(0).getWarehouseName());

        // 未核验：完成交接 / 放行派送都必须被拦截
        JsonNode notVerified = post("/handovers/" + id1 + "/complete", new LinkedHashMap<>());
        assertFalse(notVerified.path("success").asBoolean());
        assertTrue(notVerified.path("message").asText().contains("尚未核验"), notVerified.toString());
        JsonNode deliverBlocked = post("/handovers/" + id2 + "/deliver", new LinkedHashMap<>());
        assertFalse(deliverBlocked.path("success").asBoolean());
        assertTrue(deliverBlocked.path("message").asText().contains("未完成核验"), deliverBlocked.toString());

        // 第1单核验相符 -> 完成交接（责任转移）-> 放行派送
        JsonNode v1 = post("/handovers/seal-verifications", verifyBody(id1, true, null, null));
        assertTrue(v1.path("success").asBoolean(), v1.toString());
        assertEquals(CcConstants.SEAL_MATCH, v1.path("data").path("result").asText());
        assertEquals("dispatcher", v1.path("data").path("verifiedBy").asText());
        assertEquals(CcConstants.HANDOVER_SEAL_VERIFIED,
                handoverMapper.selectById(id1).getStatus());

        JsonNode c1 = post("/handovers/" + id1 + "/complete", new LinkedHashMap<>());
        assertTrue(c1.path("success").asBoolean(), c1.toString());
        HandoverOrder completed = handoverMapper.selectById(id1);
        assertEquals(CcConstants.HANDOVER_COMPLETED, completed.getStatus());
        assertEquals("杭州中转仓", completed.getHandoverOwnerOrg(), "责任主体随单转移到接收仓");
        assertEquals("王仓管", completed.getHandoverOwnerName());
        assertNotNull(completed.getHandoverTime());

        JsonNode d1 = post("/handovers/" + id1 + "/deliver", new LinkedHashMap<>());
        assertTrue(d1.path("success").asBoolean(), d1.toString());
        assertTrue(handoverMapper.selectById(id1).getDeliveredFlag());
        assertNotNull(handoverMapper.selectById(id1).getDeliveredTime());

        // 第2单仍未核验：不能派送
        JsonNode d2 = post("/handovers/" + id2 + "/deliver", new LinkedHashMap<>());
        assertFalse(d2.path("success").asBoolean());
        assertTrue(d2.path("message").asText().contains("未完成核验"));

        // 原运输批次台账仍能追溯交接全部节点
        JsonNode ledger = get("/batches/" + batch.getId() + "/ledger");
        assertTrue(ledger.path("success").asBoolean(), ledger.toString());
        JsonNode hoInfo = ledger.path("data").path("handovers");
        assertEquals(50, hoInfo.path("totalBoxes").asInt());
        assertEquals(30, hoInfo.path("completedBoxes").asInt());
        assertEquals(2, hoInfo.path("orders").size());
        assertEquals(1, hoInfo.path("orders").get(0).path("sealVerifies").size());

        List<com.evops.coldchain.entity.OpLog> logs = opLogService.listByBatch(batch.getId());
        assertTrue(logs.stream().anyMatch(l -> CcConstants.ACT_HANDOVER_SPLIT.equals(l.getAction())));
        assertTrue(logs.stream().anyMatch(l -> CcConstants.ACT_SEAL_VERIFY.equals(l.getAction())));
        assertTrue(logs.stream().anyMatch(l -> CcConstants.ACT_HANDOVER_COMPLETE.equals(l.getAction())));
        assertTrue(logs.stream().anyMatch(l -> CcConstants.ACT_HANDOVER_DELIVER.equals(l.getAction())));
        logs.forEach(l -> {
            assertNotNull(l.getOperator());
            assertNotNull(l.getCreateTime());
        });
    }

    // ====================================================================
    // 2) 箱数不平 / 封签重复 / 温度缺失：具体原因 + 整单禁止提交（无物理行）
    // ====================================================================
    @Test
    void invalidSplit_boxUnbalanced_sealDuplicated_tempMissing_blockedWithReasons() {
        TransportBatch batch = prepareShippedBatch("V4-校验", 50);
        long before = handoverMapper.selectCount(null);

        // 2.1 箱数不平：30+25=55 ≠ 50，必须指出差额
        JsonNode unbalanced = post("/handovers/split", splitBody(batch.getId(),
                item(30, "SEAL-E1-01", 5.0, "王仓管"),
                item(25, "SEAL-E1-02", 5.0, "李分拣")));
        assertFalse(unbalanced.path("success").asBoolean());
        String m1 = unbalanced.path("message").asText();
        assertTrue(m1.contains("箱数不平"), m1);
        assertTrue(m1.contains("差额 5"), m1);
        assertTrue(m1.contains("超出"), m1);

        // 2.2 箱数不足：20+20=40 ≠ 50
        JsonNode insufficient = post("/handovers/split", splitBody(batch.getId(),
                item(20, "SEAL-E1-03", 5.0, "王仓管"),
                item(20, "SEAL-E1-04", 5.0, "李分拣")));
        assertFalse(insufficient.path("success").asBoolean());
        assertTrue(insufficient.path("message").asText().contains("差额 -10"));
        assertTrue(insufficient.path("message").asText().contains("不足"));

        // 2.3 封签重复：两单同一封签
        JsonNode dupSeal = post("/handovers/split", splitBody(batch.getId(),
                item(30, "SEAL-DUP", 5.0, "王仓管"),
                item(20, "SEAL-DUP", 5.0, "李分拣")));
        assertFalse(dupSeal.path("success").asBoolean());
        assertTrue(dupSeal.path("message").asText().contains("封签重复"));
        assertTrue(dupSeal.path("message").asText().contains("SEAL-DUP"));

        // 2.4 温度缺失（JSON 不传 handoverTemperature）：参数校验给出具体原因
        Map<String, Object> noTemp = new LinkedHashMap<>();
        noTemp.put("boxQty", 30);
        noTemp.put("sealNo", "SEAL-E1-05");
        noTemp.put("receiverName", "王仓管");
        Map<String, Object> other = item(20, "SEAL-E1-06", 4.9, "李分拣");
        JsonNode missingTemp = post("/handovers/split", splitBody(batch.getId(), noTemp, other));
        assertFalse(missingTemp.path("success").asBoolean());
        assertTrue(missingTemp.path("message").asText().contains("交接温度"), missingTemp.toString());

        // 接收人缺失
        Map<String, Object> noReceiver = new LinkedHashMap<>();
        noReceiver.put("boxQty", 30);
        noReceiver.put("sealNo", "SEAL-E1-07");
        noReceiver.put("handoverTemperature", 5.0);
        JsonNode missingReceiver = post("/handovers/split", splitBody(batch.getId(),
                noReceiver, item(20, "SEAL-E1-08", 4.9, "李分拣")));
        assertFalse(missingReceiver.path("success").asBoolean());
        assertTrue(missingReceiver.path("message").asText().contains("接收人"));

        // 多次失败后物理表一行未增；修正为合法提交后成功
        assertEquals(before, handoverMapper.selectCount(null), "校验失败必须整单回滚，不得产生交接单行");
        JsonNode ok = post("/handovers/split", splitBody(batch.getId(),
                item(30, "SEAL-FIX-01", 5.0, "王仓管"),
                item(20, "SEAL-FIX-02", 4.9, "李分拣")));
        assertTrue(ok.path("success").asBoolean(), ok.toString());
        assertEquals(before + 2, handoverMapper.selectCount(null));
    }

    // ====================================================================
    // 3) 封签核验只追加：不符、箱数不符、相符三行并存；最终按最近一次结论放行
    // ====================================================================
    @Test
    void sealVerifyAppendOnly_historyKept_latestMatchUnblocks() {
        TransportBatch batch = prepareShippedBatch("V4-封签", 10);
        JsonNode split = post("/handovers/split", splitBody(batch.getId(),
                item(6, "SEAL-V3-01", 4.2, "王仓管"),
                item(4, "SEAL-V3-02", 4.5, "李分拣")));
        assertTrue(split.path("success").asBoolean(), split.toString());
        long id1 = split.path("data").get(0).path("id").asLong();

        // 第一次核验：封签不符
        JsonNode mismatch = post("/handovers/seal-verifications", verifyBody(id1, false, null, "封签有撬动痕迹"));
        assertTrue(mismatch.path("success").asBoolean(), mismatch.toString());
        assertEquals(CcConstants.SEAL_MISMATCH, mismatch.path("data").path("result").asText());
        assertEquals(CcConstants.HANDOVER_PENDING, handoverMapper.selectById(id1).getStatus());
        assertFalse(post("/handovers/" + id1 + "/complete", new LinkedHashMap<>())
                .path("success").asBoolean());

        // 第二次核验：勾选相符但箱数 5≠6，系统判 MISMATCH 并注明箱数不一致
        JsonNode boxMismatch = post("/handovers/seal-verifications", verifyBody(id1, true, 5, "复核"));
        assertTrue(boxMismatch.path("success").asBoolean());
        assertEquals(CcConstants.SEAL_MISMATCH, boxMismatch.path("data").path("result").asText());
        assertTrue(boxMismatch.path("data").path("note").asText().contains("箱数 5 与交接单声明箱数 6 不一致"));
        assertEquals(CcConstants.HANDOVER_PENDING, handoverMapper.selectById(id1).getStatus());

        // 第三次核验：相符且箱数一致
        JsonNode match = post("/handovers/seal-verifications", verifyBody(id1, true, null, null));
        assertTrue(match.path("success").asBoolean());
        assertEquals(CcConstants.SEAL_MATCH, match.path("data").path("result").asText());

        // 三次核验三行并存（只追加），前两行原值不变
        List<HandoverSealVerify> verifies = sealVerifyMapper.selectList(new QueryWrapper<HandoverSealVerify>()
                .eq("handover_id", id1).orderByAsc("id"));
        assertEquals(3, verifies.size(), "封签核验事实必须只追加，不得覆盖");
        assertEquals(CcConstants.SEAL_MISMATCH, verifies.get(0).getResult());
        assertEquals("封签有撬动痕迹", verifies.get(0).getNote());
        assertEquals(CcConstants.SEAL_MISMATCH, verifies.get(1).getResult());
        assertEquals(CcConstants.SEAL_MATCH, verifies.get(2).getResult());

        // 最近一次相符 -> 交接与放行放行
        assertTrue(post("/handovers/" + id1 + "/complete", new LinkedHashMap<>())
                .path("success").asBoolean());
        assertTrue(post("/handovers/" + id1 + "/deliver", new LinkedHashMap<>())
                .path("success").asBoolean());
    }

    // ====================================================================
    // 4) 冷链权限收紧：分仓交接停止推进；已确认封签事实保留；放开后凭事实继续
    // ====================================================================
    @Test
    void permissionRestricted_stopsHandover_keepsConfirmedFacts_thenResume() {
        TransportBatch batch = prepareShippedBatch("V4-权限", 30);
        JsonNode split = post("/handovers/split", splitBody(batch.getId(),
                item(18, "SEAL-POL-01", 5.2, "王仓管"),
                item(12, "SEAL-POL-02", 5.0, "李分拣")));
        assertTrue(split.path("success").asBoolean(), split.toString());
        long id1 = split.path("data").get(0).path("id").asLong();
        long id2 = split.path("data").get(1).path("id").asLong();

        // 收紧前先确认第1单封签（已确认事实）
        JsonNode confirmed = post("/handovers/seal-verifications", verifyBody(id1, true, null, null));
        assertTrue(confirmed.path("success").asBoolean());
        long verifiedRowsBefore = sealVerifyMapper.selectCount(new QueryWrapper<HandoverSealVerify>()
                .eq("batch_id", batch.getId()));
        assertEquals(1, verifiedRowsBefore);

        // 收紧权限
        Map<String, Object> reason = new LinkedHashMap<>();
        reason.put("reason", "监管飞检整改，暂停冷链交接");
        JsonNode restricted = post("/policy/restrict", reason);
        assertTrue(restricted.path("success").asBoolean(), restricted.toString());
        assertEquals(CcConstants.POLICY_RESTRICTED, restricted.path("data").path("status").asText());
        assertEquals(CcConstants.POLICY_RESTRICTED,
                policyMapper.selectById(restricted.path("data").path("id").asLong()).getStatus());

        // 另一个在途批次：拆单也必须停止
        TransportBatch otherBatch = prepareShippedBatch("V4-权限-另一车", 10);
        JsonNode splitBlocked = post("/handovers/split", splitBody(otherBatch.getId(),
                item(10, "SEAL-POL-X1", 5.0, "赵分拣")));
        assertFalse(splitBlocked.path("success").asBoolean());
        String stopMsg = splitBlocked.path("message").asText();
        assertTrue(stopMsg.contains("权限已收紧"), stopMsg);
        assertTrue(stopMsg.contains("监管飞检整改，暂停冷链交接"), stopMsg);

        // 封签核验 / 完成交接 / 放行派送全部停止推进
        JsonNode verifyBlocked = post("/handovers/seal-verifications", verifyBody(id2, true, null, null));
        assertFalse(verifyBlocked.path("success").asBoolean());
        assertTrue(verifyBlocked.path("message").asText().contains("权限已收紧"));
        JsonNode completeBlocked = post("/handovers/" + id1 + "/complete", new LinkedHashMap<>());
        assertFalse(completeBlocked.path("success").asBoolean());
        assertTrue(completeBlocked.path("message").asText().contains("权限已收紧"));
        JsonNode deliverBlocked = post("/handovers/" + id1 + "/deliver", new LinkedHashMap<>());
        assertFalse(deliverBlocked.path("success").asBoolean());
        assertTrue(deliverBlocked.path("message").asText().contains("权限已收紧"));

        // 交接单与已确认封签事实保留可查，一行不丢、状态不变
        JsonNode detail = get("/handovers/" + id1);
        assertTrue(detail.path("success").asBoolean(), "收紧期间查询不受影响");
        assertEquals(CcConstants.HANDOVER_SEAL_VERIFIED,
                detail.path("data").path("handover").path("status").asText());
        assertEquals(1, detail.path("data").path("sealVerifies").size());
        assertEquals(CcConstants.SEAL_MATCH,
                detail.path("data").path("sealVerifies").get(0).path("result").asText());
        assertEquals(verifiedRowsBefore, sealVerifyMapper.selectCount(new QueryWrapper<HandoverSealVerify>()
                .eq("batch_id", batch.getId())), "收紧不得删除/改写已确认封签事实");
        assertEquals(2, handoverMapper.selectCount(new QueryWrapper<HandoverOrder>()
                .eq("batch_id", batch.getId())));

        // 放开权限：凭此前已确认事实，第1单直接完成/放行；第2单补核验后完成
        Map<String, Object> enableReason = new LinkedHashMap<>();
        enableReason.put("reason", "整改通过，恢复冷链交接");
        JsonNode enabled = post("/policy/enable", enableReason);
        assertTrue(enabled.path("success").asBoolean(), enabled.toString());
        assertEquals(CcConstants.POLICY_ENABLED, enabled.path("data").path("status").asText());

        assertTrue(post("/handovers/" + id1 + "/complete", new LinkedHashMap<>())
                .path("success").asBoolean(), "放开后凭收紧前已确认的封签事实完成交接");
        assertTrue(post("/handovers/" + id1 + "/deliver", new LinkedHashMap<>())
                .path("success").asBoolean());
        assertTrue(post("/handovers/seal-verifications", verifyBody(id2, true, null, null))
                .path("success").asBoolean());
        assertTrue(post("/handovers/" + id2 + "/complete", new LinkedHashMap<>())
                .path("success").asBoolean());
        // 收紧前的确认行仍在最前
        List<HandoverSealVerify> all = sealVerifyMapper.selectList(new QueryWrapper<HandoverSealVerify>()
                .eq("handover_id", id1).orderByAsc("id"));
        assertEquals(1, all.size());
        assertEquals(CcConstants.SEAL_MATCH, all.get(0).getResult());
    }

    // ====================================================================
    // 5) 批次撤回：未完成交接单连带 ABANDONED；已完成交接单责任已转移不动；核验事实保留
    // ====================================================================
    @Test
    void batchRevoke_abandonsPendingHandovers_keepsCompletedAndFacts() {
        TransportBatch batch = prepareShippedBatch("V4-撤回", 40);
        JsonNode split = post("/handovers/split", splitBody(batch.getId(),
                item(25, "SEAL-RV-01", 4.6, "王仓管"),
                item(15, "SEAL-RV-02", 4.7, "李分拣")));
        assertTrue(split.path("success").asBoolean(), split.toString());
        long id1 = split.path("data").get(0).path("id").asLong();
        long id2 = split.path("data").get(1).path("id").asLong();

        // 第1单核验+交接完成（责任已转移）；第2单只核验不交接
        assertTrue(post("/handovers/seal-verifications", verifyBody(id1, true, null, null))
                .path("success").asBoolean());
        assertTrue(post("/handovers/" + id1 + "/complete", new LinkedHashMap<>())
                .path("success").asBoolean());
        assertTrue(post("/handovers/seal-verifications", verifyBody(id2, true, null, null))
                .path("success").asBoolean());

        batchService.revoke(revokeReq(batch.getId(), "客户取消"));

        // 已完成交接单保持 COMPLETED（责任主体已随单转移）；未交接单 ABANDONED
        assertEquals(CcConstants.HANDOVER_COMPLETED, handoverMapper.selectById(id1).getStatus());
        assertEquals("杭州中转仓", handoverMapper.selectById(id1).getHandoverOwnerOrg());
        assertEquals(CcConstants.HANDOVER_ABANDONED, handoverMapper.selectById(id2).getStatus());

        // 封签核验事实一行不删
        assertEquals(2, sealVerifyMapper.selectCount(new QueryWrapper<HandoverSealVerify>()
                .eq("batch_id", batch.getId())));

        // 放弃后的交接单不能再核验/放行
        assertFalse(post("/handovers/seal-verifications", verifyBody(id2, false, null, "再核验"))
                .path("success").asBoolean());
        assertFalse(post("/handovers/" + id2 + "/deliver", new LinkedHashMap<>())
                .path("success").asBoolean());
    }

    // ---------------- 辅助 ----------------

    /** 建档 -> 订单 -> 派车（固化总箱数）-> 绑定持证探头 -> 发运，返回在途批次 */
    private TransportBatch prepareShippedBatch(String tag, int totalBoxes) {
        int n = SEQ.incrementAndGet();
        DrugCreateRequest dc = new DrugCreateRequest();
        dc.setDrugName("HPV疫苗-" + tag);
        dc.setApprovalNo("国药准字S2026" + String.format("%04d", n));
        dc.setTempMin(new BigDecimal("2.00"));
        dc.setTempMax(new BigDecimal("8.00"));
        dc.setPackageSpec("0.5ml/支");
        Drug drug = drugService.create(dc);

        VehicleCreateRequest vc = new VehicleCreateRequest();
        vc.setPlateNo("京A·V4-" + n);
        vc.setCarrierName("华冷物流");
        vc.setDriverName("张师傅");
        Vehicle vehicle = vehicleService.create(vc);
        this.vehicleId = vehicle.getId();

        OrderCreateRequest oc = new OrderCreateRequest();
        oc.setDrugId(drug.getId());
        oc.setReceiverOrg("杭州市第一人民医院");
        oc.setShipQty(100);
        oc.setSenderOrg("上海中心仓");
        oc.setPlanShipTime(T0);
        Long orderId = orderService.create(oc).getId();

        DispatchRequest dr = new DispatchRequest();
        dr.setOrderId(orderId);
        dr.setVehicleId(vehicle.getId());
        dr.setTotalBoxes(totalBoxes);
        TransportBatch batch = batchService.dispatch(dr);
        assertEquals(totalBoxes, batch.getTotalBoxes().intValue());

        bindValidProbe(vehicle.getId(), "req-" + tag + "-" + n, "SN-V4-" + n, T0.plusYears(1));
        return batchService.ship(batch.getId());
    }

    private void bindValidProbe(Long vehicleId, String requestId, String serialNo, LocalDateTime validUntil) {
        ProbeCreateRequest pc = new ProbeCreateRequest();
        pc.setSerialNo(serialNo);
        pc.setModel("TP-100");
        pc.setRangeMin(new BigDecimal("-40.00"));
        pc.setRangeMax(new BigDecimal("60.00"));
        pc.setCalibrateOrg("华东计量院");
        pc.setValidUntil(validUntil);
        pc.setCertNo("CERT-" + serialNo);
        pc.setCalibrateDate(T0.minusDays(1));
        pc.setCertFileName(serialNo + ".txt");
        pc.setCertFileContent("calibration certificate of " + serialNo);
        Long probeId = probeService.createProbe(pc).getId();

        ProbeBindRequest pb = new ProbeBindRequest();
        pb.setRequestId(requestId);
        pb.setVehicleId(vehicleId);
        pb.setProbeId(probeId);
        probeService.bindIdempotent(pb);
    }

    private com.evops.coldchain.dto.RevokeRequest revokeReq(Long batchId, String reason) {
        com.evops.coldchain.dto.RevokeRequest req = new com.evops.coldchain.dto.RevokeRequest();
        req.setBatchId(batchId);
        req.setReason(reason);
        return req;
    }

    private Map<String, Object> item(int boxQty, String sealNo, double temp, String receiver) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("boxQty", boxQty);
        m.put("sealNo", sealNo);
        m.put("handoverTemperature", temp);
        m.put("receiverName", receiver);
        return m;
    }

    private Map<String, Object> splitBody(long batchId, Map<String, Object>... items) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("batchId", batchId);
        body.put("warehouseName", "杭州中转仓");
        body.put("items", java.util.Arrays.asList(items));
        return body;
    }

    private Map<String, Object> verifyBody(long handoverId, boolean match, Integer boxQty, String note) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("handoverId", handoverId);
        body.put("match", match);
        if (boxQty != null) {
            body.put("boxQty", boxQty);
        }
        if (note != null) {
            body.put("note", note);
        }
        return body;
    }

    private JsonNode post(String path, Object body) {
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            HttpEntity<Object> entity = new HttpEntity<>(body == null ? new LinkedHashMap<>() : body, headers);
            String url = "http://localhost:" + port + BASE + path;
            String resp = http.withBasicAuth("dispatcher", "dispatcher123")
                    .postForObject(url, entity, String.class);
            return M.readTree(resp);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private JsonNode get(String path) {
        try {
            String url = "http://localhost:" + port + BASE + path;
            String resp = http.withBasicAuth("dispatcher", "dispatcher123").getForObject(url, String.class);
            return M.readTree(resp);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
