# 🚀 快速启动指南

## 第一次运行（5分钟设置）

### 1️⃣ 准备 API Key

1. 访问 https://platform.openai.com/api-keys
2. 创建一个新的 API key
3. 复制 API key

### 2️⃣ 配置环境变量

Windows PowerShell:
```powershell
cp .env.example .env
notepad .env
```

Linux/Mac:
```bash
cp .env.example .env
nano .env
```

在 `.env` 文件中填入你的 API key：
```
LLM_API_KEY=sk-proj-xxxxxxxxxxxxx
```

### 3️⃣ 启动服务

```bash
docker-compose up --build
```

看到这个提示就说明启动成功了：
```
backend  | Started BackendApplication in X.XXX seconds
```

### 4️⃣ 打开浏览器

访问：http://localhost:8080

---

## 测试流程（1分钟）

### 填写表单：

**Patient**
- First Name: `John`
- Last Name: `Doe`
- MRN: `123456`
- DOB: `1980-01-01`

**Provider**
- Provider Name: `Dr. Smith`
- NPI: `1234567890`

**Clinical**
- Medication: `IVIG`
- Primary Diagnosis: `G70.00`
- Patient Records: `Progressive muscle weakness over 2 weeks`

点击 "Generate CarePlan"

⏳ **等待 10-30 秒**（这就是同步调用的缺点！）

✅ 看到生成的 care plan

---

## 常见问题

### ❌ 端口被占用
```
Error: port 8080 is already in use
```

**解决方案：**
```bash
# Windows
netstat -ano | findstr :8080
taskkill /PID <PID> /F

# Linux/Mac
lsof -ti:8080 | xargs kill -9
```

或者修改 `docker-compose.yml`：
```yaml
ports:
  - "8081:8080"  # 改成 8081
```

### ❌ Docker 没启动
```
Error: Cannot connect to the Docker daemon
```

**解决方案：** 启动 Docker Desktop

### ❌ API Key 无效
```
Error: Incorrect API key provided
```

**解决方案：** 检查 `.env` 文件中的 API key 是否正确

---

## 停止服务

```bash
# Ctrl + C 停止
# 然后运行：
docker-compose down
```

---

## 下次启动

下次只需要：
```bash
docker-compose up
```

不需要 `--build` 了！

---

## 🎯 体验重点

运行后，注意这些**缺点**：

1. ⏳ 提交后页面卡住 10-30 秒
2. ❌ 等待期间不能做任何事
3. ❌ 不能连续提交多个订单
4. ❌ 如果失败了，用户白等了

**这就是为什么后续要用异步处理！**

