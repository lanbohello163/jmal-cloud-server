#!/bin/bash
set -e

# 获取传入的 UID 和 GID 环境变量
USER_UID=${UID:-$(id -u)}
USER_GID=${GID:-$(id -g)}

# 检查是否使用 root 用户
if [ "$USER_UID" -eq 0 ] && [ "$USER_GID" -eq 0 ]; then
    echo "Running as root"
    exec "$@"
else
    # 创建用户组和用户，并将其 UID 和 GID 设置为环境变量指定的值
    if ! getent group appgroup > /dev/null 2>&1; then
        groupadd -g $USER_GID appgroup || true
    fi

    if ! id -u appuser > /dev/null 2>&1; then
        useradd -m -u $USER_UID -g appgroup appuser || true
    fi

    # 更改挂载路径的所有者
    chown -R appuser:appgroup /jmalcloud

    # 切换到新用户并执行应用程序
    exec gosu appuser "$@"
fi

# 启动应用程序
exec java -Dfile.encoding=UTF-8 -Dloader.path=/usr/local/clouddisk-lib -jar ${JVM_OPTS} /usr/local/clouddisk-${VERSION}.jar --spring.profiles.active=${RUN_ENVIRONMENT} --spring.data.mongodb.uri=${MONGODB_URI} --tess4j.data-path=${TESS4J_DATA_PATH} --file.monitor=${FILE_MONITOR} --file.rootDir=${FILE_ROOT_DIR} --logging.level.root=${LOG_LEVEL} --file.ip2region-db-path=/jmalcloud/ip2region.xdb
