# AgentX Tracer

> **让 Agent 从黑盒变白盒** —— 智能体运行时可观测与 Token 计量平台

基于自研 [spring-ai-agentx](https://github.com/bigchuidw3/spring-ai-agentx) 框架构建，把每一次 Agent 调用的**推理过程、工具执行、Token 消耗、成本与质量**，以可交互的仪表盘、调用追踪与 Span 轨迹完整呈现。

---

## 为什么需要 AgentX Tracer

LLM 应用正从「单次问答」演进为「多轮推理 + 工具调用 + 子代理协作」的 Agent 形态，而 Agent 内部是一个**黑盒**：跑了多少轮？调了哪些工具？每一步消耗多少 Token、花多少钱？在哪一步失败、为什么失败？

AgentX Tracer 把 Agent 的每一次执行拆解为可观测的 **Span 轨迹**，向上聚合为**指标看板**与**成本账单**，向下穿透到**每一轮推理、每一次工具调用的完整原文**——让 Agent 可诊断、可度量、可治理。

---

## 核心功能

| 模块 | 说明 |
|---|---|
| **智能体对话** | SSE 流式输出（思考 / 正文 / 工具过程）、多工具并发、流式断点续传、任务中断与恢复、L1–L6 上下文压缩 |
| **调用追踪** | Span 执行序列（LLM / TOOL / COMPACT）、工具度量（次数 / 失败率 / P50 / P95 / Max）、每轮 Prompt 折线、入参出参与思考内容全文 |
| **仪表盘** | 调用量 / Token / 成本 / 成功率 KPI，小时 / 7 天 / 30 天趋势联动，分布与 TOP 榜下钻 |
| **Token 计量** | 按当前模型单价实时折算成本（prompt / completion 分别计价），月度预算与 80% / 100% 两级告警 |
| **Opik 接入** | OTLP 标准协议一键导出，用户级开关、连通性测试，噪声过滤只保留 Agent 调用链 |
| **安全沙箱** | 兄弟容器模式（非 DinD），用户级隔离，工作区快照 / 恢复 |
| **工作区** | 文件预览 / 下载 / 搜索 / 批量操作，全路径安全校验防目录穿越 |
| **多模型 / Skills** | 每用户独立模型配置（OpenAI 兼容协议）、技能包管理（内置只读 + 个人可增删改） |
| **短信登录** | 验证码注册即登录、手机号改绑、密码重置 |
| **数据隔离** | 全链路 `user_id` 隔离：会话 / Trace / 消息 / 统计 / 成本 |

> 各功能的完整说明与操作步骤见[功能使用指南](docs/AgentX%20Tracer%20功能使用指南.docx)。

---

## 界面预览

### 仪表盘

KPI 指标 + 调用趋势 + Top 榜，Token / 成本 / 质量一屏总览。

![仪表盘](docs/images/dashboard.png)

### 调用追踪

**统计页签**：基本信息、Token 累计、上下文窗口占用与消息计数。

![统计页签](docs/images/trace-stats.png)

**轨迹页签**：统一 Span 执行序列，工具度量与每轮交互入参出参可视化。

![轨迹页签](docs/images/trace-timeline.png)

### 工作区

文件预览、搜索、批量下载与删除。

![工作区](docs/images/workspace.png)

---

## 技术亮点

1. **自研框架背书**：构建于从 0 到 1 的 `spring-ai-agentx`（GitHub 近 200 Star），技术深度与工程成熟度有真实生产验证。
2. **可观测闭环**：底层 Span 轨迹 → 中层指标统计 → 上层成本账单，三层打通，而非孤立的功能堆砌。
3. **全链路多用户隔离**：所有数据按 `user_id` 隔离，天然支持多用户形态，数据安全从存储层保证。
4. **安全执行边界**：工具执行落在兄弟容器沙箱内，网络可禁、资源可限、快照可回滚。
5. **标准协议接入**：基于 OTel / OTLP 标准协议对接 Opik，零厂商锁定。
6. **生产级交互细节**：SSE 断点续传、中断恢复、上下文压缩、消息链缓存秒开，对标成熟产品的体验打磨。

---

## 技术架构

```
┌────────────────────────────────────────────────────────────┐
│                        前端（Vue 3 + ECharts）                 │
│   对话 / 调用追踪 / 仪表盘 / 模型管理 / Skills / 工作区         │
└──────────────────────────────┬─────────────────────────────┘
                               │ SSE / REST（Sa-Token 认证）
┌──────────────────────────────▼─────────────────────────────┐
│                     AgentX Tracer（Spring Boot 3.5）         │
│   AgentService · ObservService · DashboardService            │
│   CostService · WorkspaceService · SandboxConfigService      │
└──────────────────────────────┬─────────────────────────────┘
                               │
┌──────────────────────────────▼─────────────────────────────┐
│              spring-ai-agentx（Agent 运行时框架）             │
│   ReactAgent · AgentLoopExecutor · TraceStore/TraceManager   │
│   SandboxManager · ContextCompactor · LongTermMemory         │
└──────────────┬───────────────────────────┬──────────────────┘
               │                           │
    ┌──────────▼──────────┐     ┌──────────▼──────────┐
    │   MySQL / Redis /   │     │  Opik（OTel/OTLP）   │
    │      MinIO          │     │  Docker 沙箱（兄弟容器）│
    └─────────────────────┘     └─────────────────────┘
```

**技术栈**：Spring Boot 3.5.6 · Java 21 · Spring AI 1.1.0 · MyBatis-Plus 3.5.5 · Sa-Token · Vue 3 · ECharts · MySQL · Redis · MinIO · OpenTelemetry

---

## 快速开始

```bash
# 1. 打包
mvn -o package -DskipTests

# 2. 准备本地配置
cp src/main/resources/application-example.yml src/main/resources/application.yml

# 3. 部署并访问
# http://<服务器IP>:8090
```

> 完整的安装与部署步骤（基础镜像、初始化数据库、install / update 脚本）见[安装部署指南](docs/AgentX%20Tracer%20安装部署指南.docx)。
> 本地配置不入库，`application-example.yml` 为占位符模板；部署包 `deploy/` 含真实配置，单独提供、不进仓库。

---

## 目录结构

```
agentx-tracer/
├── src/main/java/com/agentx/tracer/
│   ├── auth/           # 认证（Sa-Token + 短信登录）
│   ├── chat/           # 多模型动态接入
│   ├── controller/     # 对话 / 仪表盘 / 可观测 / 工作区 / 文件
│   ├── observability/  # Opik / OTel 集成
│   ├── service/        # 核心业务（统计 / 成本 / 沙箱 / 工作区）
│   ├── tools/          # Agent 工具（文件 / 诊断）
│   └── hook/           # 预算告警 Hook
├── src/main/resources/static/   # 前端（Vue 3 本地化，内网离线可用）
├── docs/               # 功能使用指南 / 安装部署指南 / 截图
├── skills/             # 内置 + 用户技能
└── deploy/             # 部署脚本 + Dockerfile + init.sql（不入库）
```
