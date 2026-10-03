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

在 V1~V3 台账上增加“一车货到中转仓后拆分交接”的全流程：一车货可拆成多个交接单，
每单记录箱数、逐箱封签号、交接温度与接收人；封签逐条核验通过、全批箱数平衡后提交，
责任主体随单转移；未完成核验的箱子不能继续派送。冷链药品运输权限被收紧时，
分仓交接停止推进，但已经确认的封签核验事实原样保留、仍可追溯。

### 核心规则

- **先到仓登记再拆单**：批次发运在途后先办理“到仓登记”，记录到仓总箱数，作为分箱平衡基准；
  到仓事实只登记一次，不可重复登记/修改。
- **一车多单拆箱**：一批货可连续拆成多张交接单，每单记录中转仓、箱数、交接温度、接收人及
  接收责任主体；逐箱封签，**封签数必须等于箱数**。
- **箱数必须平衡**：非作废交接单箱数合计在建单时不得超过到仓总箱数（超出给出超几箱并拒绝）；
  任一单提交时，全批有效交接单箱数合计必须**正好等于**到仓总箱数，差额未交接齐则禁止提交。
- **封签号批次内唯一**：同一运输批次范围内（含历史/已作废交接单）封签号不得重复，
  重复时指明与哪张交接单冲突；单内重复同样拒绝。
- **交接温度/接收人不得缺失**：建单即必须给出交接温度与接收人/接收责任主体，缺失直接拒绝。
- **封签逐条核验，事实只进不退**：封签状态 `PENDING -> CONFIRMED` 单向流转，记录核验说明、
  核验人与核验时间；已确认事实不能重复核验或改写。单内封签全部核验才能提交。
- **提交即责任主体随单转移**：提交置 `SUBMITTED` 并冻结箱数/封签/温度/接收人，
  单上责任主体由原承运批次（承运商/驾驶员）转移为接收机构/接收人，批次当前责任主体同步更新；
  原运输批次仍通过 `batch_id` 串联到仓、每张交接单、每枚封签核验与责任转移的全部节点。
- **未核验箱子不能继续派送**：只有当全部到仓箱数都已分到交接单、所在单均 `SUBMITTED`
  （即每箱封签都已核验）时，批次签收闭环/继续派送才放行；否则给出“还差几箱/几签未核验”。
- **作废不删事实**：仅 `DRAFT` 可作废为 `VOIDED`（已提交的责任转移不可撤销），作废释放箱数占用，
  但封签/核验明细与操作日志原样保留，封签号仍占用、不可复用。
- **权限收紧冻结推进、保留已确认事实**：`COLD_CHAIN_HANDOVER` 权限一旦收紧（`permitted=FALSE`），
  到仓/建单/封签核验/提交/作废/签收等一切推进动作停止并返回明确停点；查询不受影响，
  已确认的封签核验事实原样保留，恢复放行后无需重新核验即可继续。
- 到仓/建单/核验/提交/作废/权限收紧与恢复均写 `t_cc_op_log`（实体类型
  `HANDOVER` / `SEAL` / `PERMISSION`，并带 `batch_id` 串联）。

### 表结构增量（详见 schema.sql，版本标记 `cold-chain-4`）

- 新增 `t_cc_handover_order`（交接单：箱数/封签数/交接温度/接收人/责任主体快照/提交与作废）、
  `t_cc_handover_seal`（逐封签核验，批次内封签号唯一）、`t_cc_permission_setting`（权限开关），
  序列 `seq_cc_handover_no`（交接单号 `JJ+8位`）。
- `t_cc_transport_batch` 增 `arrived_box_qty`（到仓总箱数）、
  `responsible_org` / `responsible_party`（当前责任主体，发运为承运方、交接提交后随单转移）。

### 接口增量（仍在 `/api/cold-chain` 下，HTTP Basic）

| 动作 | 方法与路径 |
| --- | --- |
| 一车货到仓登记（到仓总箱数） | `POST /handovers/arrive` |
| 拆交接单（箱数/封签/温度/接收人） | `POST /handovers` |
| 逐封签核验 | `POST /handovers/seals/verify` |
| 提交（责任主体随单转移） | `POST /handovers/{id}/submit` |
| 作废草稿单（事实保留） | `POST /handovers/void` |
| 交接单详情（含封签核验） | `GET /handovers/{id}` |
| 批次交接单列表 | `GET /handovers/batch/{batchId}` |
| 批次交接进度/能否继续派送 | `GET /handovers/batch/{batchId}/progress` |
| 收紧/恢复冷链运输权限 | `POST /permissions` |
| 查询冷链交接权限状态 | `GET /permissions/cold-chain-handover` |
| 批次完整台账（含全部分仓节点） | `GET /batches/{id}/ledger` |
| 分仓交接管理页面 | `GET /ui/cold-chain/handovers`（HTTP Basic） |

示例（到仓 100 箱后拆一单 60 箱，逐箱封签）：

```bash
curl -u dispatcher:dispatcher123 -H 'Content-Type: application/json' \
  -X POST http://localhost:8080/api/cold-chain/handovers/arrive \
  -d '{"batchId":1,"arrivedBoxQty":100,"warehouseName":"华东中转仓"}'

curl -u dispatcher:dispatcher123 -H 'Content-Type: application/json' \
  -X POST http://localhost:8080/api/cold-chain/handovers \
  -d '{"batchId":1,"warehouseName":"华东中转仓","boxQty":60,"handoverTemp":5.20,
       "receiverName":"张仓","receiverOrg":"华东中转仓收货组",
       "sealNos":["SF001","SF002"]}'

# 逐封签核验（全部核验且箱数平衡后才能 /handovers/{id}/submit）
curl -u dispatcher:dispatcher123 -H 'Content-Type: application/json' \
  -X POST http://localhost:8080/api/cold-chain/handovers/seals/verify \
  -d '{"sealId":1,"verifyNote":"封签完好、箱内温度达标"}'

# 收紧冷链药品运输权限：分仓交接停止推进，已确认封签事实保留
curl -u dispatcher:dispatcher123 -H 'Content-Type: application/json' \
  -X POST http://localhost:8080/api/cold-chain/permissions \
  -d '{"permitted":false,"reason":"监管现场整改，暂停冷链交接"}'
```

### V4 验收

`HandoverTransferIntegrationTest` 以真实 HTTP 入口驱动并用 Mapper 直查物理表，覆盖：
一车货拆多单（6+4）、逐封签核验、提交责任主体随单转移、批次台账追溯到仓/交接单/封签全节点、
未核验箱子拦截继续派送与签收（补齐后放行）；箱数不平（箱签数不符/超分/未交齐）、封签重复
（单内与跨单且指明冲突单）、交接温度/接收人缺失三类停点均返回具体原因并禁止提交；
权限收紧后建单/核验/提交/签收全部停止、页面显示收紧横幅但已确认封签事实保留、恢复后无需重核；
作废释放箱数占用但封签/核验事实保留、封签号不可复用。

