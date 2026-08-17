#!/bin/bash
# 测试数据库连接脚本

echo "=========================================="
echo "MongoDB to Kingbase 连接测试"
echo "=========================================="

# 测试MongoDB连接
echo ""
echo "1. 测试MongoDB连接..."
echo "   主机: 10.35.4.12"
echo "   端口: 32121"
echo "   数据库: SmartCare"

# 使用mongosh测试（如果可用）
if command -v mongosh &> /dev/null; then
    echo "   使用mongosh测试连接..."
    mongosh --host 10.35.4.12 --port 32121 --username admin --password "Dxm99*" --authenticationDatabase admin --eval "db.stats()" SmartCare
elif command -v mongo &> /dev/null; then
    echo "   使用mongo测试连接..."
    mongo --host 10.35.4.12 --port 32121 -u admin -p "Dxm99*" --authenticationDatabase admin SmartCare --eval "db.stats()"
else
    echo "   警告: 未找到mongo/mongosh客户端"
    echo "   请手动测试连接或安装MongoDB客户端"
fi

# 测试Kingbase连接
echo ""
echo "2. 测试Kingbase连接..."
echo "   主机: 10.35.4.12"
echo "   端口: 54321"
echo "   数据库: kingbase"

# 使用psql测试（如果可用）
if command -v psql &> /dev/null; then
    echo "   使用psql测试连接..."
    PGPASSWORD="chc3Xz3PS4N^x" psql -h 10.35.4.12 -p 54321 -U system -d kingbase -c "SELECT version();"
else
    echo "   警告: 未找到psql客户端"
    echo "   请手动测试连接或安装PostgreSQL客户端"
fi

echo ""
echo "=========================================="
echo "测试完成"
echo "=========================================="
