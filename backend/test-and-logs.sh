#!/bin/bash
echo "发送测试请求..."
curl -X POST http://localhost:8080/api/orders \
  -H "Content-Type: application/json" \
  -d '{
    "patientFirstName": "Test",
    "patientLastName": "User",
    "patientMrn": "999999",
    "patientDateOfBirth": "1990-01-01",
    "providerName": "Dr. Test",
    "providerNpi": "9999999999",
    "medicationName": "Test Med",
    "primaryDiagnosis": "A00.0",
    "additionalDiagnosis": "B00.0",
    "medicationHistory": "None",
    "patientRecords": "Test records"
  }'

echo ""
echo "等待3秒..."
sleep 3

echo "查看日志..."
docker logs careplan-backend --tail 50

