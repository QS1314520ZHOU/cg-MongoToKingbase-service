#!/bin/bash
# MongoDB to Kingbase Sync Service 启动脚本

# 应用名称
APP_NAME="mongo-to-kingbase"
APP_VERSION="1.0.0"
JAR_FILE="${APP_NAME}-${APP_VERSION}.jar"

# 部署目录
DEPLOY_DIR="/opt/mongo-to-kingbase"
LOG_DIR="${DEPLOY_DIR}/logs"

# Java参数
JAVA_OPTS="-Xms512m -Xmx1024m -Dfile.encoding=UTF-8"

# 检查Java环境
check_java() {
    if ! command -v java &> /dev/null; then
        echo "错误: 未找到Java环境，请先安装Java 8+"
        exit 1
    fi

    JAVA_VERSION=$(java -version 2>&1 | head -n 1 | awk -F '"' '{print $2}')
    echo "Java版本: ${JAVA_VERSION}"
}

# 创建日志目录
create_log_dir() {
    if [ ! -d "${LOG_DIR}" ]; then
        mkdir -p "${LOG_DIR}"
        echo "创建日志目录: ${LOG_DIR}"
    fi
}

# 停止服务
stop() {
    echo "正在停止服务..."
    PID=$(ps -ef | grep "${JAR_FILE}" | grep -v grep | awk '{print $2}')

    if [ -z "${PID}" ]; then
        echo "服务未运行"
        return 0
    fi

    echo "停止进程: ${PID}"
    kill -15 ${PID}

    # 等待进程停止
    for i in {1..30}; do
        if ! ps -p ${PID} > /dev/null 2>&1; then
            echo "服务已停止"
            return 0
        fi
        sleep 1
    done

    echo "服务停止超时，强制终止"
    kill -9 ${PID}
}

# 启动服务
start() {
    echo "正在启动 ${APP_NAME}..."

    # 检查JAR文件
    if [ ! -f "${DEPLOY_DIR}/${JAR_FILE}" ]; then
        echo "错误: 未找到JAR文件 ${DEPLOY_DIR}/${JAR_FILE}"
        exit 1
    fi

    # 检查是否已运行
    PID=$(ps -ef | grep "${JAR_FILE}" | grep -v grep | awk '{print $2}')
    if [ -n "${PID}" ]; then
        echo "服务已在运行 (PID: ${PID})"
        exit 0
    fi

    # 创建日志目录
    create_log_dir

    # 启动服务
    echo "启动命令: java ${JAVA_OPTS} -jar ${DEPLOY_DIR}/${JAR_FILE}"
    cd ${DEPLOY_DIR}
    nohup java ${JAVA_OPTS} -jar ${JAR_FILE} > ${LOG_DIR}/nohup.log 2>&1 &

    # 等待启动
    sleep 3

    # 检查是否启动成功
    PID=$(ps -ef | grep "${JAR_FILE}" | grep -v grep | awk '{print $2}')
    if [ -n "${PID}" ]; then
        echo "服务启动成功 (PID: ${PID})"
        echo "日志文件: ${LOG_DIR}/nohup.log"
    else
        echo "服务启动失败，请查看日志"
        tail -20 ${LOG_DIR}/nohup.log
        exit 1
    fi
}

# 查看状态
status() {
    PID=$(ps -ef | grep "${JAR_FILE}" | grep -v grep | awk '{print $2}')

    if [ -z "${PID}" ]; then
        echo "服务未运行"
        return 1
    else
        echo "服务正在运行 (PID: ${PID})"
        return 0
    fi
}

# 查看日志
logs() {
    if [ -f "${LOG_DIR}/nohup.log" ]; then
        tail -f ${LOG_DIR}/nohup.log
    else
        echo "日志文件不存在"
    fi
}

# 使用方法
usage() {
    echo "使用方法: $0 {start|stop|restart|status|logs}"
    echo ""
    echo "命令:"
    echo "  start   - 启动服务"
    echo "  stop    - 停止服务"
    echo "  restart - 重启服务"
    echo "  status  - 查看状态"
    echo "  logs    - 查看日志"
}

# 主函数
case "$1" in
    start)
        check_java
        start
        ;;
    stop)
        stop
        ;;
    restart)
        check_java
        stop
        sleep 2
        start
        ;;
    status)
        status
        ;;
    logs)
        logs
        ;;
    *)
        usage
        exit 1
        ;;
esac

exit 0
