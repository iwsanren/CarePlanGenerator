# CarePlan Generator - Day 2-3 MVP

这是一个最小可行版本（MVP），用于体验同步调用LLM生成care plan的流程。

## 🎯 Day 2 学习目标

- 体验前端 + 后端 + PostgreSQL + LLM 的完整流程
- 感受**同步调用的缺点**：提交表单后需要等待 10-30 秒
- 理解为什么后续需要引入异步处理

## 🎯 Day 3 学习目标

- 理解数据库表之间的关系（Patient → Order → CarePlan）
- 学习使用 TablePlus 查看数据库
- 体验 Mock Data 的导入（自动 + 手动两种方式）
- 理解外键（Foreign Key）的作用

## 🏗️ 架构

```
前端 (HTML) → 后端 (Spring Boot) → PostgreSQL 数据库
                    ↓
                 LLM API (OpenAI)
```

**流程：**
1. 用户填写表单提交
2. 后端接收请求
3. 保存 Patient、Provider、Order 到数据库
4. 创建 CarePlan（status = PENDING）
5. **同步调用 LLM**（这里会阻塞 10-30 秒）⏳
6. 更新 CarePlan（status = COMPLETED，保存生成的内容）
7. 返回结果给前端

## 📋 前置条件

- Docker Desktop 已安装并运行
- OpenAI API Key（或 Claude API Key）

## 🚀 快速开始

### 1. 配置 API Key

复制 `.env.example` 为 `.env`：
```bash
cp .env.example .env
```

编辑 `.env` 文件，填入你的 OpenAI API Key：
```
LLM_API_KEY=sk-your-actual-api-key-here
```

### 2. 启动服务

```bash
docker-compose up --build
```

等待服务启动完成（大约 1-2 分钟）。

**Day 3 新增功能**：应用启动时会自动导入 Mock Data（测试数据）。
你会在日志中看到：
```
开始初始化 Mock Data...
Mock Data 初始化完成！
创建了 3 个 Providers
创建了 5 个 Patients
创建了 7 个 Orders
创建了 7 个 Care Plans
```

### 3. 访问应用

打开浏览器访问：**http://localhost:8080**

### 4. 测试流程

1. 填写表单（所有带 * 的字段都是必填）
2. 点击 "Generate CarePlan"
3. **等待 10-30 秒**（这就是同步调用的缺点！）
4. 看到结果

## 📝 示例数据

**Patient Information:**
- First Name: John
- Last Name: Doe
- MRN: 123456
- DOB: 1980-01-01

**Provider Information:**
- Provider Name: Dr. Smith
- NPI: 1234567890

**Clinical Information:**
- Medication Name: IVIG
- Primary Diagnosis: G70.00 (Myasthenia gravis)
- Patient Records: 
```
Progressive muscle weakness over 2 weeks.
Positive AChR antibody test.
MGFA class IIb.
```

## 📊 Day 3: 查看 Mock Data（使用 TablePlus）

启动应用后，数据库已经有测试数据了。你可以用 TablePlus 查看：

**连接信息：**
- Host: `localhost`
- Port: `5432`
- User: `careplan_user`
- Password: `careplan_password`
- Database: `careplan`

**查看已有数据：**
- **3 个医生**（李医生、王医生、张医生）
- **5 个病人**（张三、李四、王五、赵六、陈七）
- **7 个订单**（包含不同状态的 Care Plans）
  - ✅ COMPLETED (3个) - 已完成
  - ⏳ PROCESSING (1个) - 处理中
  - 🕐 PENDING (2个) - 等待中
  - ❌ FAILED (1个) - 失败

**试试这些 SQL 查询：**

```sql
-- 查看所有订单
SELECT 
    p.first_name || ' ' || p.last_name as patient_name,
    pr.name as provider_name,
    o.medication_name,
    cp.status
FROM orders o
JOIN patients p ON o.patient_id = p.id
JOIN providers pr ON o.provider_id = pr.id
LEFT JOIN care_plans cp ON cp.order_id = o.id;

-- 查看张三的所有订单（他有3个订单）
SELECT 
    o.medication_name,
    cp.status
FROM orders o
JOIN patients p ON o.patient_id = p.id
LEFT JOIN care_plans cp ON cp.order_id = o.id
WHERE p.first_name = '张' AND p.last_name = '三';
```

**详细说明：** 查看 `backend/notes/DAY3/QUICKSTART.md`

## 🔍 体验痛点

提交表单后，你会发现：
- ❌ 页面卡住了，什么都做不了
- ❌ 不能提交第二个订单
- ❌ 如果 LLM 调用失败，用户白等了
- ❌ 用户体验很差

**这就是为什么 Day 4 要引入消息队列！**

## 🛠️ 技术栈

- **后端**: Java 17, Spring Boot 3.4, Spring Data JPA
- **数据库**: PostgreSQL 15
- **前端**: 纯 HTML/CSS/JavaScript
- **容器化**: Docker, Docker Compose
- **LLM**: OpenAI GPT-3.5-turbo

## 📊 数据库结构

```
patients (病人表)
├── id
├── first_name
├── last_name
├── mrn (唯一)
└── date_of_birth

providers (医生表)
├── id
├── name
└── npi (唯一)

orders (订单表)
├── id
├── patient_id (外键)
├── provider_id (外键)
├── medication_name
├── primary_diagnosis
├── additional_diagnosis
├── medication_history
├── patient_records
└── created_at

care_plans (护理计划表)
├── id
├── order_id (外键)
├── status (PENDING/PROCESSING/COMPLETED/FAILED)
├── content
├── created_at
└── updated_at
```

## 🐛 调试

### 查看日志
```bash
docker-compose logs -f backend
```

### 查看数据库
```bash
docker exec -it careplan-postgres psql -U postgres -d careplan
```

常用 SQL：
```sql
-- 查看所有表
\dt

-- 查看订单
SELECT * FROM orders;

-- 查看 care plans
SELECT * FROM care_plans;

-- 查看患者
SELECT * FROM patients;
```

## 🛑 停止服务

```bash
docker-compose down
```

保留数据：
```bash
docker-compose down
```

删除所有数据（包括数据库）：
```bash
docker-compose down -v
```

## ⚠️ 注意事项

1. **API Key 安全**: 不要把 API Key 提交到 Git！`.env` 文件已经在 `.gitignore` 中
2. **API 费用**: 每次调用 LLM 都会产生费用（大约 $0.002-0.01）
3. **同步调用**: 这个版本是故意做成同步的，让你体验缺点

## 📚 下一步学习

- **Day 3**: 数据库设计优化
- **Day 4**: 引入消息队列（Redis）实现异步
- **Day 5**: Celery Worker 处理任务
- **Day 6**: 前端实时更新（Polling/WebSocket）

## 🤔 思考问题

运行这个 MVP 后，思考：
1. 如果 10 个用户同时提交，会发生什么？
2. 如果 LLM API 调用失败，用户体验如何？
3. 如果一个 care plan 需要 1 分钟生成，用户会怎么样？

这些问题的答案，就是后续引入异步架构的原因！

