#!/bin/bash
# 构建脚本

echo "=========================================="
echo "MongoDB to Kingbase Sync Service 构建"
echo "=========================================="

# 检查Java环境
if ! command -v java &> /dev/null; then
    echo "错误: 未找到Java环境"
    exit 1
fi

# 检查Maven环境
if ! command -v mvn &> /dev/null; then
    echo "错误: 未找到Maven环境"
    exit 1
fi

# 清理旧的构建
echo "1. 清理旧的构建..."
mvn clean

# 编译打包
echo "2. 编译打包..."
mvn package -DskipTests

# 检查构建结果
if [ -f "target/mongo-to-kingbase-1.0.0.jar" ]; then
    echo "3. 构建成功！"
    echo "   JAR包位置: target/mongo-to-kingbase-1.0.0.jar"
    echo "   文件大小: $(ls -lh target/mongo-to-kingbase-1.0.0.jar | awk '{print $5}')"
else
    echo "3. 构建失败"
    exit 1
fi

echo ""
echo "=========================================="
echo "构建完成"
echo "=========================================="
echo ""
echo "下一步："
echo "1. 将JAR包上传到服务器"
echo "2. 参考 deploy/DEPLOY.md 进行部署"
echo "3. 运行 ./deploy/test-connection.sh 测试连接"
