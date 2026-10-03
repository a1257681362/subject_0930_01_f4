# EVOPS 基础工作区

这是 springboot 的骨架项目。

技术栈：Java 8、Spring Boot 2.7、MyBatis-Plus、H2 本地文件数据库、Shiro、Thymeleaf。

启动前准备：

1. 执行 `mvn -q -DskipTests compile` 验证骨架构建。
2. 执行 `mvn spring-boot:run` 启动应用；Spring Boot 会自动执行 `src/main/resources/schema.sql`。
3. H2 数据文件默认写入工作区 `data/evops`。如需修改路径，编辑 `application.yml` 中的 `jdbc:h2:file:` 连接串。

根目录的 `..\..\docs\schema\evops.sql` 是交付副本，应与工作区 `src/main/resources/schema.sql` 保持一致。

## 冷链药品运输台账（V1）

在骨架上新增 `com.evops.coldchain` 模块，跑通建档 → 派车 → 发运 → 签收 → 异常标记；
结算与监管报送留待后续版本。

### 核心规则

- **药品档案版本化**：建档生成 V1；变更只追加新版本行，旧版本批准文号/温区/包装原值永不修改。
- **批次按原版本核对**：发货单创建即锁定档案版本，派车时把该版本（批准文号、储存温区上下限、
  包装规格、版本号）快照冗余到运输批次。档案之后即使先变更，在途批次核对、签收一律以快照为准。
- **稳定批次号**：由 H2 序列生成（`PC年+6位序列`），数据库层唯一、单调、重启不复用。
- **途中温度按时间留痕**：以批次快照温区判定是否越限，越限自动追加温度异常事件。
- **异常只追加**：异常事件、凭证（附件/照片/说明）全部 INSERT-only，不覆盖、不更新、不删除；
  处理闭环只追加处理说明，不改原描述与凭证。
- **签收才闭环**：签收需按快照核对实物批准文号、包装规格、数量，且无未处理（OPEN）异常。
- **撤回不删数据**：仅置 `REVOKED` 作废标记；批次、温度、异常、凭证、日志原始行全部保留。
- **关键操作留痕**：建档/变更/派车/发运/温度上报/异常标记/凭证追加/异常处理/签收/撤回
  统一写入 `t_cc_op_log`，记录操作者账号、操作者 ID 与时间（MyBatis-Plus 自动填充）。

### 表结构（详见 schema.sql）

`t_cc_drug` / `t_cc_drug_version`（档案+版本）、`t_cc_vehicle`、`t_cc_shipment_order`、
`t_cc_transport_batch`（含档案快照）、`t_cc_temperature_log`、`t_cc_exception`、
`t_cc_exception_evidence`、`t_cc_op_log`，以及 `seq_cc_drug_no/seq_cc_order_no/seq_cc_batch_no` 序列。
V2 另增路线温区分段四张表（见下节）与 `seq_cc_route_no` 序列。

### 接口（均在 `/api/cold-chain` 下，HTTP Basic 认证）

内置账号：`dispatcher/dispatcher123`（调度）、`carrier/carrier123`（在途）、
`receiver/receiver123`（签收）、`bootstrap/bootstrap`。

| 动作 | 方法与路径 |
| --- | --- |
| 药品建档 | `POST /drugs` |
| 档案变更（追加版本） | `POST /drugs/{id}/versions` |
| 查看档案版本 | `GET /drugs/{id}/versions` |
| 车辆登记 / 列表 | `POST /vehicles` / `GET /vehicles` |
| 创建发货单（锁版本） | `POST /orders` |
| 派车（生成批次+快照） | `POST /batches/dispatch` |
| 发运 | `POST /batches/{id}/ship` |
| 温度上报 | `POST /batches/temperatures` |
| 异常标记 | `POST /batches/exceptions` |
| 追加异常凭证 | `POST /batches/exceptions/evidence` |
| 异常处理闭环 | `POST /batches/exceptions/{id}/resolve` |
| 按快照核对签收 | `POST /batches/sign` |
| 撤回（作废不删） | `POST /batches/revoke` |
| 批次完整台账 | `GET /batches/{id}/ledger` |

示例：

```bash
curl -u dispatcher:dispatcher123 -H 'Content-Type: application/json' \
  -X POST http://localhost:8080/api/cold-chain/drugs \
  -d '{"drugName":"HPV疫苗","approvalNo":"国药准字S20200011","tempMin":2,"tempMax":8,"packageSpec":"0.5ml/支"}'
```

### 验证

`mvn test` 运行 `ColdChainFlowIntegrationTest`，覆盖全链路及关键不变量：
档案先变更后批次仍按 V1 核对、越限自动异常、凭证追加不覆盖、OPEN 异常拦截签收、
按原版本签收闭环、撤回不删记录、批次号稳定唯一、全链路操作者/时间留痕。

## 冷链运输 V2：路线温区分段

在 V1 台账上增加“按季节维护的路线温区分段”。同一路线可按春/夏/秋/冬（及 GENERAL 通用）
各维护一套版本，每个版本由若干相邻分段组成，每段指定**允许温度、越限缓冲时长（分钟）和责任人**。

### 核心规则

- **分段相邻无空档/无重叠**：分段按里程 `[fromKm, toKm)` 从 0 连续覆盖到路线 `total_km`；
  相邻段必须首尾相接（首段从 0 起、末段到总里程止）。温区倒置、缓冲为负、责任人为空、
  超出总里程一律拒绝，并返回明确停点说明。
- **季节版本只追加**：草稿 `DRAFT` 可反复编排（仅草稿分段允许重排）；发布后置 `EFFECTIVE`，
  同季节当前在效版本被热替换为 `SUPERSEDED`，历史版本与分段行原值永不修改。
- **发布前展示差异**：`GET /routes/versions/{id}/diff` 按段序号给出
  `ADDED/REMOVED/MODIFIED/UNCHANGED`；发布响应也回带差异。
- **新版本只影响未发运批次**：派车只登记走哪条路线；**发运（批次开始执行）瞬间**才按
  “当前季节 + 当前时间”选取生效版本（当季未配置回退 GENERAL），把其全部分段**逐段快照**到
  `t_cc_route_segment_snap`。因此派车后、发运前发布的新版本仍会作用于该批；一旦发运，版本
  热替换/撤回即与本批次脱钩，在途、签收继续引用原快照。
- **按分段温区 + 缓冲时长判定越限**：温度上报需带 `mileageKm`，按里程命中快照分段温区；
  同一段内连续越限未超过该段缓冲时长只留痕、不开异常，超过缓冲才自动开温度异常并定位到该段
  责任人；中途回正常或切换分段会打断连续越限计时。
- **撤回只能撤销尚未生效的版本**：`DRAFT`，或已发布但生效时间窗未到且**从未被批次引用**的
  版本可撤回为 `REVOKED`；已生效或已被批次固化快照的版本拒绝撤回（只能再发新版本调整）。
- 预约生效（未来 `effectiveFrom`）的版本，其时间窗不得与同季节其他生效版本重叠。
- 建档/草稿/发布/撤回与派车均写 `t_cc_op_log`（实体类型 `ROUTE`）。

### 表结构增量（详见 schema.sql，版本标记 `cold-chain-2`）

- 新增 `t_cc_route`（路线主档）、`t_cc_route_version`（季节版本）、
  `t_cc_route_segment`（版本分段，只追加）、`t_cc_route_segment_snap`（批次逐段快照）、
  序列 `seq_cc_route_no`。
- `t_cc_transport_batch` 增 `route_id/route_version_id/route_season/route_version_no`；
  `t_cc_temperature_log` 增 `mileage_km/segment_seq`；`t_cc_exception` 增 `segment_seq`。

### 接口增量（仍在 `/api/cold-chain` 下，HTTP Basic）

| 动作 | 方法与路径 |
| --- | --- |
| 路线建档 / 列表 / 详情 | `POST /routes` / `GET /routes` / `GET /routes/{id}` |
| 按季节编排分段草稿 | `POST /routes/{id}/versions/draft` |
| 查看版本分段 | `GET /routes/versions/{versionId}/segments` |
| 发布前分段差异 | `GET /routes/versions/{versionId}/diff` |
| 发布（热替换/预约生效） | `POST /routes/versions/publish` |
| 撤回未生效版本 | `POST /routes/versions/withdraw` |
| 派车携带路线（逐段快照） | `POST /batches/dispatch`（body 增加 `routeId`） |
| 分段温度上报（带里程） | `POST /batches/temperatures`（body 增加 `mileageKm`） |

示例（两段连续覆盖 0~100km 的通用季节版本）：

```bash
curl -u dispatcher:dispatcher123 -H 'Content-Type: application/json' \
  -X POST http://localhost:8080/api/cold-chain/routes \
  -d '{"routeName":"沪杭冷链线","origin":"上海中心仓","destination":"杭州市一院","totalKm":100.00}'

curl -u dispatcher:dispatcher123 -H 'Content-Type: application/json' \
  -X POST http://localhost:8080/api/cold-chain/routes/1/versions/draft \
  -d '{"season":"GENERAL","changeReason":"秋冬季分段",
       "segments":[
         {"fromKm":0,"toKm":50,"tempMin":2,"tempMax":8,"bufferMinutes":30,"ownerName":"王冷链","ownerPhone":"13800000001"},
         {"fromKm":50,"toKm":100,"tempMin":0,"tempMax":5,"bufferMinutes":60,"ownerName":"李干线","ownerPhone":"13800000002"}]}'
```

### V2 验收

`RouteTemperatureSegmentIntegrationTest` 以真实 HTTP 入口（TestRestTemplate + Basic 认证）驱动，
再用 Mapper 直查物理表，确保配置结果**一路追到温区分段与批次快照落库**：
路线建档→季节草稿→发布前差异→发布热替换→派车登记路线→发运逐段快照→按里程分段温区/缓冲判定；
并验证空档/重叠/未覆盖全程/倒置/越界/温区非法/缓冲为负/责任人为空等**失败停点**返回明确说明，
新版本只影响未派车批次、已派车批次持原快照、撤回仅限未生效未被引用版本、季节优先与 GENERAL 回退、
预约时间窗重叠拒绝。

## 冷链运输 V3：车载温度探头校准凭证管理

在 V1/V2 台账上增加车载温度探头的校准凭证全生命周期管理：探头建档登记序列号、量程、
校准机构与有效期；发运前车辆必须绑定仍在有效期内的持证探头；替换探头保留前后关系，
历史温度记录不回写；证书上传生成 SHA-256 摘要，下载与作废逐次写审计流水。

### 核心规则

- **探头建档**：序列号库内唯一；登记量程上下限、校准机构、当前校准有效期；可选随附首份证书。
- **凭证只追加**：新一轮校准=上传新的 VALID 凭证并切换为当前凭证；作废只追加 `VOID` 标记与
  原因/时间，不删除文件、不改原行；作废凭证仍可下载追溯。上传时对文件内容计算 **SHA-256**
  摘要（64 位十六进制，同内容同摘要）。
- **发运前强制绑定有效探头**：发运卡口要求车辆存在当前绑定，且探头在用、凭证 `VALID`、
  未过有效期；未绑定/已过期/已作废一律拦截（接口与页面同一道校验，页面下拉直接禁选）。
  发运瞬间把探头序列号、量程、校准机构、证书编号、有效期、SHA-256 逐字段**快照到批次**，
  此后探头替换、凭证作废都与在途批次脱钩。
- **替换保留前后关系**：车辆-探头绑定只追加；旧绑定置 `released_time` 并写
  `replaced_by_bind_id` 指向新绑定，替换链双向可查。
- **历史温度不回写**：每条温度留痕带 `probe_id`；替换后新测点归新探头，旧温度行原值不动
  （温度表仍为只追加，应用层无更新路径）。
- **关联幂等**：`POST /probes/bind` 必带 `requestId`；同一 requestId 的重复请求直接回第一次的
  绑定结果（`replay=true`），不新增绑定行、不改替换链、不写第二条流水；requestId 换参数拒绝。
- **下载/作废审计**：证书下载记 `last_download_time` 并写 `CERT_DOWNLOAD` 流水（响应头
  `X-Cert-SHA256` 回带摘要），作废写 `CERT_VOID` 流水。

### 表结构增量（详见 schema.sql，版本标记 `cold-chain-3`）

- 新增 `t_cc_temp_probe`（探头主档）、`t_cc_probe_cert`（校准凭证，含 sha256/作废/下载记账）、
  `t_cc_vehicle_probe`（车辆绑定，只追加，含替换链与快照）、`t_cc_probe_bind_request`
  （requestId 幂等登记，唯一约束兜底）、序列 `seq_cc_probe_no`（探头编号 `PW+8位`）。
- `t_cc_transport_batch` 增探头凭证快照列（probe_id/bind_id/cert_id/序列号/量程/校准机构/
  证书编号/有效期/摘要）；`t_cc_temperature_log` 增 `probe_id`。

### 接口增量（仍在 `/api/cold-chain` 下，HTTP Basic）

| 动作 | 方法与路径 |
| --- | --- |
| 探头建档（可选随附首份凭证） | `POST /probes` |
| 探头列表 / 详情（含校准历史） | `GET /probes` / `GET /probes/{id}` |
| 上传新校准凭证（生成 SHA-256） | `POST /probes/certs/upload` |
| 下载证书（写审计流水） | `GET /probes/certs/{certId}/download` |
| 作废凭证（不删原文） | `POST /probes/certs/void` |
| 车载探头校准凭证关联（requestId 幂等） | `POST /probes/bind` |
| 车辆当前绑定 / 替换历史 | `GET /probes/vehicles/{vehicleId}/current` / `.../history` |
| 管理页面 | `GET /ui/cold-chain/probes`（HTTP Basic） |

示例（建档→上传证书→幂等关联）：

```bash
curl -u dispatcher:dispatcher123 -H 'Content-Type: application/json' \
  -X POST http://localhost:8080/api/cold-chain/probes \
  -d '{"serialNo":"SN-T-0001","model":"TP-200","rangeMin":-40,"rangeMax":60,
       "calibrateOrg":"华东计量院","validUntil":"2027-09-30T00:00:00",
       "certNo":"CAL-2026-0001","calibrateDate":"2026-09-29T10:00:00",
       "certFileName":"CAL-2026-0001.txt","certFileContent":"校准证书原文内容"}'

curl -u dispatcher:dispatcher123 -H 'Content-Type: application/json' \
  -X POST http://localhost:8080/api/cold-chain/probes/bind \
  -d '{"requestId":"b3f1-uuid","vehicleId":1,"probeId":1}'
# 再用完全相同的 requestId 请求一次：replay=true，bind.id 不变，绑定表不产生第二条结果
```

### V3 验收

`ProbeCalibrationIntegrationTest` 以真实 HTTP 入口驱动并用 Mapper 直查物理表：
冷链药品运输先提交有效样本（建档→订单→派车），再两次重复请求“车载探头校准凭证关联”，
断言两次返回同一条绑定且物理表只有一行绑定/一行幂等登记/一条绑定流水；
覆盖发运前未绑定/证书过期/证书作废拦截、绑定过期设备拦截、替换链前后关系、
在途批次历史温度不回写（新批次归新探头）、SHA-256 摘要确定性、下载/作废逐次留痕、
同 requestId 换参数拒绝，以及管理页面过期探头禁选与待发运批次拦截提示。

## 冷链运输 V4：批次分仓交接

在 V1–V3 台账上增加“一车货到达中转仓后的分仓交接”。一个在途运输批次可按箱拆成多个交接单，
每单记录**箱数、封签号、交接温度、接收人**；封签逐单核验（事实只追加），未完成核验的箱子
不能完成交接、不能继续派送；交接完成后责任主体随单转移，原运输批次仍能追溯派车/发运/温度/
交接全部节点。冷链药品运输权限可一键收紧，收紧后交接停止推进，已经确认的封签核验事实保留。

### 核心规则

- **派车登记总箱数**：`POST /batches/dispatch` 新增可选 `totalBoxes`，派车时固化为分仓拆单的
  箱数平衡基准；不做分仓交接的既有批次不受影响。
- **一车多单拆分，强校验后才落库**：拆单一次性提交多个交接单，整单事务，任一不满足全部拒绝，
  并在 message 中给出**具体原因**：
  - **箱数不平**：各单箱数合计必须恰好等于批次总箱数，提示合计/总箱数/差额（超出或不足）；
  - **封签重复**：封签号在本批次内唯一（数据库唯一索引兜底），提示重复的封签号；
  - **温度缺失/接收人缺失**：每单交接温度、接收人必填；
  - 同一批次只允许拆一次（防止重复拆单导致重箱），未发运/已签收/已撤回批次不可拆单。
- **封签核验只追加**：`t_cc_handover_seal_verify` 每核验一次新增一行（MATCH 相符 / MISMATCH
  不符），记录封签号、确认箱数、核验人与时间，应用层只有 INSERT；勾选相符但核验箱数与声明
  箱数不一致时按 MISMATCH 留痕并注明差额。卡口以“最近一次核验结论”为准，历史核验行全部保留。
- **未核验不放行**：交接单须最近一次封签核验 MATCH 才能完成交接；完成交接（责任随单转移）后
  才能放行下游派送，`deliver` 接口对“未核验/核验不符/未完成交接”再次卡口。
- **责任主体随单转移**：完成交接瞬间把接收仓与接收人快照到交接单 `handover_owner_org/name`
  与 `handover_time`；原承运责任止于交接，原运输批次、温度、异常与交接核验记录全部保留，
  `GET /batches/{id}/ledger` 聚合交接单与封签核验，全链路节点仍可追溯。
- **权限收紧停止推进、已确认事实保留**：`t_cc_policy` 单行策略（cold-chain-transport）
  ENABLED/RESTRICTED。收紧后拆单、封签核验、完成交接、放行派送一律拒绝并回带**收紧原因**；
  查询不受影响，交接单与已确认（MATCH）的核验行原样保留，不回滚、不删除。放开后可凭收紧前
  已确认的封签事实直接继续推进。
- **撤回连带处理**：批次撤回时，尚未完成交接（责任未转移）的交接单置 `ABANDONED`；
  已 COMPLETED 的责任主体已随单转移，状态不动；任何封签核验事实都不删除。
- 拆单/封签核验/交接完成/放行派送/连带放弃/权限收紧与放开均写 `t_cc_op_log`（实体类型
  `HANDOVER` / `POLICY`），按批次可串联。

### 表结构增量（详见 schema.sql，版本标记 `cold-chain-4`）

- 新增 `t_cc_handover_order`（分仓交接单，含箱数/封签/交接温度/接收人/责任转移主体/放行标记）、
  `t_cc_handover_seal_verify`（封签核验事实，只追加）、`t_cc_policy`（冷链权限单行开关，
  种入默认 ENABLED 行）、序列 `seq_cc_handover_no`（交接单号 `JJ+8位`）。
- `t_cc_transport_batch` 增 `total_boxes`（派车固化的总箱数）。

### 接口增量（仍在 `/api/cold-chain` 下，HTTP Basic）

| 动作 | 方法与路径 |
| --- | --- |
| 分仓拆单（一车多单，强校验具体原因） | `POST /handovers/split` |
| 封签核验（MATCH/MISMATCH 事实只追加） | `POST /handovers/seal-verifications` |
| 完成交接（责任主体随单转移） | `POST /handovers/{id}/complete` |
| 放行下游派送（未核验不放行） | `POST /handovers/{id}/deliver` |
| 交接单详情（含全部核验事实） | `GET /handovers/{id}` |
| 按批次查交接单 / 按单查核验流水 | `GET /handovers/batch/{batchId}` / `GET /handovers/{id}/seal-verifications` |
| 查看冷链运输权限 | `GET /policy` |
| 收紧 / 放开冷链运输权限 | `POST /policy/restrict` / `POST /policy/enable`（body：`{"reason":"..."}`） |

示例（50 箱拆 30+20 两单）：

```bash
curl -u dispatcher:dispatcher123 -H 'Content-Type: application/json' \
  -X POST http://localhost:8080/api/cold-chain/handovers/split \
  -d '{"batchId":1,"warehouseName":"杭州中转仓",
       "items":[
         {"boxQty":30,"sealNo":"SEAL-0001","handoverTemperature":5.10,"receiverName":"王仓管"},
         {"boxQty":20,"sealNo":"SEAL-0002","handoverTemperature":4.80,"receiverName":"李分拣"}]}'

curl -u dispatcher:dispatcher123 -H 'Content-Type: application/json' \
  -X POST http://localhost:8080/api/cold-chain/handovers/seal-verifications \
  -d '{"handoverId":1,"match":true}'

curl -u dispatcher:dispatcher123 -X POST http://localhost:8080/api/cold-chain/policy/restrict \
  -H 'Content-Type: application/json' -d '{"reason":"监管飞检整改，暂停冷链交接"}'
```

### V4 验收

`HandoverSplitIntegrationTest` 以真实 HTTP 入口（TestRestTemplate + Basic 认证）驱动，
再用 Mapper 直查物理表，覆盖：一车两单拆分落库且箱数平衡、箱数不平（含差额方向）/封签重复/
温度与接收人缺失返回具体原因且整单回滚无新增行、未核验不能交接与派送、封签核验只追加
（不符→箱数不符→相符多行并存）、交接完成责任主体随单转移且批次台账聚合全部交接节点、
权限收紧后拆单/核验/交接/放行全部停止并回带原因而已确认事实保留、放开后凭已确认事实继续、
批次撤回连带放弃未交接单但已交接单与核验事实不动。
