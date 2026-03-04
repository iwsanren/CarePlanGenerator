@echo off
docker logs careplan-backend --tail 100 > temp_logs.txt 2>&1
type temp_logs.txt
del temp_logs.txt
pause

