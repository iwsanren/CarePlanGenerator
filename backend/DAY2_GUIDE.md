# 🎓 Day 2 学习指南

## 📝 学习目标

今天你将完成一个**最小可行产品（MVP）**，体验：
1. 前端 + 后端 + 数据库 + LLM 的完整流程
2. **同步调用的缺点**（这是重点！）
3. 为什么后续需要异步处理

## 🏃 运行项目

### 方式一：使用 Docker Compose（推荐）

```bash
# 1. 配置 API Key
cp .env.example .env
# 编辑 .env，填入你的 OpenAI API Key

# 2. 启动所有服务
docker-compose up --build

# 3. 访问 http://localhost:8080
```

### 方式二：本地运行（用于调试）

```bash
# 1. 启动 PostgreSQL
docker run -d --name careplan-postgres \
  -e POSTGRES_DB=careplan \
  -e POSTGRES_USER=postgres \
  -e POSTGRES_PASSWORD=postgres \
  -p 5432:5432 \
  postgres:15-alpine

# 2. 配置 API Key
cp .env.example .env
# 编辑 .env

# 3. 运行 Spring Boot
./mvnw spring-boot:run
```

Windows用户可以直接运行：
```bash
run-local.bat
```

## 🧪 测试步骤

### 1. 打开浏览器

访问：http://localhost:8080

### 2. 填写表单

使用示例数据：

**Patient Information**
- First Name: `John`
- Last Name: `Doe`
- MRN: `123456` （必须6位数）
- Date of Birth: `1980-01-01`

**Provider Information**
- Provider Name: `Dr. Smith`
- NPI: `1234567890` （必须10位数）

**Clinical Information**
- Medication Name: `IVIG`
- Primary Diagnosis: `G70.00 - Myasthenia gravis`
- Patient Records:
```
Progressive proximal muscle weakness and ptosis over 2 weeks.
Positive AChR antibody test.
MGFA class IIb.
Neurology recommends IVIG for rapid symptomatic control.
```

### 3. 点击提交

点击 "Generate CarePlan" 按钮

### 4. 观察等待时间 ⏳

**重点来了！注意观察：**
- ⏳ 页面显示 "Generating care plan... This may take 10-30 seconds"
- ❌ 页面卡住了，不能做任何操作
- ❌ 不能提交第二个订单
- ❌ 不能查看其他页面

**这就是同步调用的缺点！**

### 5. 查看结果

等待结束后，你会看到：
- ✅ Order ID
- ✅ Status: COMPLETED
- ✅ 生成的 Care Plan 内容

## 🔍 深入理解

### 查看数据库

```bash
# 进入 PostgreSQL
docker exec -it careplan-postgres psql -U postgres -d careplan

# 查看所有表
\dt

# 查看患者数据
SELECT * FROM patients;

# 查看订单数据
SELECT * FROM orders;

# 查看 care plan 数据
SELECT id, status, created_at, updated_at FROM care_plans;

# 查看完整的 care plan 内容
SELECT content FROM care_plans WHERE id = 1;

# 退出
\q
```

### 查看日志

```bash
# 查看后端日志
docker-compose logs -f backend

# 只看最近50行
docker-compose logs --tail=50 backend
```

### 使用 Postman 测试 API

**创建订单：**
```
POST http://localhost:8080/api/orders
Content-Type: application/json

{
  "patientFirstName": "John",
  "patientLastName": "Doe",
  "patientMrn": "123456",
  "patientDateOfBirth": "1980-01-01",
  "providerName": "Dr. Smith",
  "providerNpi": "1234567890",
  "medicationName": "IVIG",
  "primaryDiagnosis": "G70.00",
  "patientRecords": "Progressive muscle weakness..."
}
```

**查询订单：**
```
GET http://localhost:8080/api/orders/1
```

**查询所有订单：**
```
GET http://localhost:8080/api/orders
```

## 🐛 调试技巧

### 在 IntelliJ IDEA 中调试

1. 打开 `OrderController.java`
2. 在 `createOrder` 方法的第一行设置断点
3. 右键点击 `BackendApplication.java` → Debug
4. 提交表单，观察代码执行流程

### 断点位置建议

```java
// OrderController.java
@PostMapping
public ResponseEntity<OrderResponse> createOrder(@RequestBody CreateOrderRequest request) {
    // 断点1: 检查接收到的数据
    Patient patient = patientRepository.findByMrn(request.getPatientMrn())
    
    // 断点2: 检查是否找到患者
    order = orderRepository.save(order);
    
    // 断点3: LLM 调用前
    String carePlanContent = llmService.generateCarePlan(patientInfo);
    
    // 断点4: LLM 调用后，查看返回内容
    carePlan.setContent(carePlanContent);
    
    // 断点5: 返回前，检查最终数据
    return ResponseEntity.ok(toResponse(order, carePlan));
}
```

## 📊 性能测试

### 测试同步调用的问题

1. 打开两个浏览器窗口
2. 同时在两个窗口提交订单
3. 观察：第二个请求会等待第一个完成

### 使用 curl 测试

```bash
# 发送请求并计时
time curl -X POST http://localhost:8080/api/orders \
  -H "Content-Type: application/json" \
  -d '{
    "patientFirstName": "Jane",
    "patientLastName": "Smith",
    "patientMrn": "654321",
    "patientDateOfBirth": "1985-05-15",
    "providerName": "Dr. Johnson",
    "providerNpi": "0987654321",
    "medicationName": "Medication XYZ",
    "primaryDiagnosis": "E11.9"
  }'
```

观察响应时间（通常 10-30 秒）

## 💡 思考问题

运行完这个 MVP 后，思考以下问题：

### 1. 用户体验问题
- ❓ 如果用户在等待时关闭了浏览器，会发生什么？
- ❓ 如果有 10 个用户同时提交，系统会怎么样？
- ❓ 如果 LLM 调用失败，用户体验如何？

### 2. 技术问题
- ❓ 为什么不能连续提交多个订单？
- ❓ 如果 LLM 调用需要 1 分钟，用户会等待吗？
- ❓ 服务器会不会因为等待 LLM 而无法处理其他请求？

### 3. 业务问题
- ❓ CVS 的药剂师每天要处理 50+ 个患者，这个系统能用吗？
- ❓ 如果系统崩溃了，正在生成的 care plan 会丢失吗？

## ✅ 完成标志

完成 Day 2 后，你应该：

- [x] 成功运行了整个系统
- [x] 提交了至少 3 个订单
- [x] 查看了数据库中的数据
- [x] **体验到了同步调用的缺点**（等待时间长）
- [x] 理解了为什么需要异步处理
- [x] 能回答上面的思考问题

## 🎯 下一步

**Day 3: 数据库设计**
- 优化数据库表结构
- 添加索引
- 理解外键关系

**Day 4: 引入消息队列**
- 使用 Redis 作为消息队列
- API 快速返回，不再阻塞
- 体验异步处理的好处

## 🆘 遇到问题？

### 常见错误

1. **端口被占用**
   ```
   Error: Bind for 0.0.0.0:8080 failed: port is already allocated
   ```
   解决：修改 `docker-compose.yml` 中的端口

2. **API Key 无效**
   ```
   Failed to generate care plan: 401 Unauthorized
   ```
   解决：检查 `.env` 文件中的 API Key

3. **数据库连接失败**
   ```
   Could not open JPA EntityManager for transaction
   ```
   解决：等待 PostgreSQL 完全启动（约 10 秒）

4. **Maven 依赖下载慢**
   ```
   Downloading: ...
   ```
   解决：第一次需要下载依赖，耐心等待

### 获取帮助

1. 查看日志：`docker-compose logs -f`
2. 检查数据库：`docker exec -it careplan-postgres psql -U postgres -d careplan`
3. 重启服务：`docker-compose restart`
4. 完全重置：`docker-compose down -v && docker-compose up --build`

---

**记住：今天的重点不是写出完美的代码，而是体验同步调用的缺点！** 🎯

