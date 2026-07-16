pipeline {
    agent any
    parameters {
        choice(name: 'DEPLOY_ENV', choices: ['test', 'prod'], description: '部署环境')
    }

    environment {
        HARBOR_ADDR = "192.168.120.11"
        TEST_HARBOR_REPO = "java/java-demo"
        PROD_HARBOR_REPO = "pipeline/pipeline-demo"
        PROD_SSH_SERVER = "master02"
        TEST_SSH_SERVER = "master01"
        PROD_CONTAINER_NAME = "pipeline-app"
        TEST_CONTAINER_NAME = "test-app"
        NAMESPACE = "k8s.io"
        // 健康检查路径，根据你的应用实际情况修改
        APP_PORT = "8080"
    }

    options {
        timestamps()
        timeout(time: 15, unit: 'MINUTES')
        buildDiscarder(logRotator(numToKeepStr: '20'))
    }

    stages {
        stage('0. 初始化环境变量') {
            steps {
                script {
                    env.DEPLOY_ENV = params.DEPLOY_ENV
                    env.FULL_REPO = env.DEPLOY_ENV == 'prod' ? env.PROD_HARBOR_REPO : env.TEST_HARBOR_REPO
                    env.REMOTE_HOST = env.DEPLOY_ENV == 'prod' ? env.PROD_SSH_SERVER : env.TEST_SSH_SERVER
                    env.CONTAINER_NAME = env.DEPLOY_ENV == 'prod' ? env.PROD_CONTAINER_NAME : env.TEST_CONTAINER_NAME

                    env.GIT_COMMIT = sh(script: "git rev-parse --short HEAD", returnStdout: true).trim()
                    env.IMAGE_TAG = "v${BUILD_NUMBER}"
                    env.FULL_IMG = "${env.HARBOR_ADDR}/${env.FULL_REPO}:${env.IMAGE_TAG}"
                    env.HEALTH_URL = "http://127.0.0.1:${env.APP_PORT}"
                }
            }
        }

        stage('1. Maven 编译打包') {
            steps {
                dingtalk(
                    robot: "jenkins",
                    type: 'MARKDOWN',
                    title: "流水线进度：开始Maven 编译打包",
                    text: ["""
### 正在执行Maven 编译打包
- 环境：${env.DEPLOY_ENV}
- 构建号：${BUILD_NUMBER}
"""],
                    atAll: false
                )
                sh '''
                    cd demo
                    mvn clean package -DskipTests
                '''
            }
        }

        stage('2. 构建镜像 & 推送Harbor') {
            steps {
                dingtalk(
                    robot: "jenkins",
                    type: 'MARKDOWN',
                    title: "流水线进度：开始构建镜像 & 推送Harbor",
                    text: ["""
### 正在执行构建镜像 & 推送Harbor
- 环境：${env.DEPLOY_ENV}
- 构建号：${BUILD_NUMBER}
"""],
                    atAll: false
                )
                withCredentials([usernamePassword(credentialsId: 'harbor', passwordVariable: 'HARBOR_PWD', usernameVariable: 'HARBOR_USER')]) {
                    sh '''
                        cd demo
                        echo "${HARBOR_PWD}" | docker login ${HARBOR_ADDR} -u ${HARBOR_USER} --password-stdin
                        docker build -t ${FULL_IMG} .
                        docker push ${FULL_IMG}
                        docker logout ${HARBOR_ADDR}
                    '''
                }
            }
        }

        stage('3. 生产发布确认') {
            when {
                expression { params.DEPLOY_ENV == 'prod' }
            }
            steps {
                dingtalk(
                    robot: "jenkins",
                    type: 'MARKDOWN',
                    title: "流水线进度：发布待确认",
                    text: ["""
### 发布待确认
- 环境：${env.DEPLOY_ENV}
- 构建号：${BUILD_NUMBER}
"""],
                    atAll: false
                )
                input message: "确认发布到【${env.DEPLOY_ENV}】环境？", ok: '确认发布'
            }
        }

        stage('4. 远程 containerd 部署') {
            steps {
                dingtalk(
                    robot: "jenkins",
                    type: 'MARKDOWN',
                    title: "流水线进度：远程 containerd 部署",
                    text: ["""
### 正在执行远程 containerd 部署
- 环境：${env.DEPLOY_ENV}
- 构建号：${BUILD_NUMBER}
"""],
                    atAll: false
                )
                withCredentials([
                    sshUserPrivateKey(credentialsId: 'jenkins-ssh-remove', keyFileVariable: 'SSH_KEY', usernameVariable: 'SSH_USER')
                ]) {
                    script {
                        def remote = [:]
                        remote.name = env.REMOTE_HOST
                        remote.host = env.REMOTE_HOST
                        remote.user = SSH_USER
                        remote.identityFile = SSH_KEY
                        remote.allowAnyHosts = true

                        // 关键：所有Jenkins变量用Groovy插值直接传入远程命令，不再依赖远程环境变量
                        sshCommand remote: remote, command: """
set -e
NAMESPACE="${env.NAMESPACE}"
CONTAINER_NAME="${env.CONTAINER_NAME}"
FULL_IMG="${env.FULL_IMG}"
HEALTH_URL="${env.HEALTH_URL}"
BACKUP_FILE="/root/${env.CONTAINER_NAME}_backup_image.txt"

# 1. 备份当前运行的镜像（不存在则跳过，不阻断部署）
ctr -n k8s.io c list|grep \${CONTAINER_NAME}|awk '{print\$2}' > \${BACKUP_FILE}
echo "查看上个版本：\${BACKUP_FILE}"

# 2. 拉取新镜像
echo "开始拉取新镜像: \${FULL_IMG}"
crictl pull \${FULL_IMG}

# 3. 清理旧容器
echo "清理旧容器: \${CONTAINER_NAME}"
if ctr -n \${NAMESPACE} c list | grep -q \${CONTAINER_NAME}; then
    ctr -n \${NAMESPACE} task kill \${CONTAINER_NAME} 2>/dev/null || true
    sleep 1
    ctr -n \${NAMESPACE} c delete \${CONTAINER_NAME} 2>/dev/null || true
fi

# 4. 启动新容器（增加重启策略、内存限制，可按需调整）
echo "启动新容器"
ctr -n \${NAMESPACE} run -d --env TZ=Asia/Shanghai --net-host \${FULL_IMG} \${CONTAINER_NAME}

# 5. 健康检查（带重试机制，最多等待120秒）
echo "等待应用启动，进行健康检查: \${HEALTH_URL}"
for i in {1..12}; do
    if curl -s --connect-timeout 3 \${HEALTH_URL} > /dev/null; then
        echo "应用启动成功，健康检查通过"
        exit 0
    fi
    echo "等待10秒后重试... 第\${i}次"
    sleep 10
done

echo "错误：健康检查失败，应用未正常启动"
exit 1
"""
                    }
                }
            }
        }

        stage('5. 发布结果确认 & 回滚选择') {
            steps {
                dingtalk(
                    robot: "jenkins",
                    type: 'MARKDOWN',
                    title: "流水线进度：部署完成，是否回滚",
                    text: ["""
### 部署完成，是否回滚
- 环境：${env.DEPLOY_ENV}
- 构建号：${BUILD_NUMBER}
- 镜像：${env.FULL_IMG}
"""],
                    atAll: false
                )
                script {
                    // 用变量标记是否回滚，替代catchError的错误写法
                    env.SHOULD_ROLLBACK = 'false'
                    try {
                        input message: "部署完成，确认是否回滚？", ok: "确认回滚"
                        env.SHOULD_ROLLBACK = 'true'
                    } catch (e) {
                        echo "用户选择不回滚，流水线结束"
                    }
                }
            }
        }

        stage('6. 回滚上个版本') {
            when {
                expression { env.SHOULD_ROLLBACK == 'true' }
            }
            steps {
                dingtalk(
                    robot: "jenkins",
                    type: 'MARKDOWN',
                    title: "流水线进度：执行回滚",
                    text: ["""
### 正在执行回滚上个版本
- 环境：${env.DEPLOY_ENV}
- 构建号：${BUILD_NUMBER}
"""],
                    atAll: false
                )
                withCredentials([
                    sshUserPrivateKey(credentialsId: 'jenkins-ssh-remove', keyFileVariable: 'SSH_KEY', usernameVariable: 'SSH_USER')
                ]) {
                    script {
                        def remote = [:]
                        remote.name = env.REMOTE_HOST
                        remote.host = env.REMOTE_HOST
                        remote.user = SSH_USER
                        remote.identityFile = SSH_KEY
                        remote.allowAnyHosts = true

                        sshCommand remote: remote, command: """
set -e
NAMESPACE="${env.NAMESPACE}"
CONTAINER_NAME="${env.CONTAINER_NAME}"
BACKUP_FILE="/root/${env.CONTAINER_NAME}_backup_image.txt"

# 读取备份的旧镜像
if [ ! -s \${BACKUP_FILE} ]; then
    echo "错误：无备份镜像，无法回滚"
    exit 1
fi
ROLLBACK_IMG=\$(cat \${BACKUP_FILE})
echo "回滚镜像: \${ROLLBACK_IMG}"

# 确保镜像存在，不存在则拉取
if ! ctr -n \${NAMESPACE} i list | grep -q \${ROLLBACK_IMG}; then
    echo "本地无镜像，开始拉取"
    crictl pull \${ROLLBACK_IMG}
fi

# 清理当前容器
echo "清理当前容器"
if ctr -n \${NAMESPACE} c list | grep -q \${CONTAINER_NAME}; then
    ctr -n \${NAMESPACE} task kill \${CONTAINER_NAME} 2>/dev/null || true
    sleep 1
    ctr -n \${NAMESPACE} c delete \${CONTAINER_NAME} 2>/dev/null || true
fi

# 启动旧版本容器
echo "启动回滚容器"
ctr -n \${NAMESPACE} run -d --env TZ=Asia/Shanghai --net-host \${ROLLBACK_IMG} \${CONTAINER_NAME}

# 健康检查
HEALTH_URL="${env.HEALTH_URL}"
echo "等待回滚应用启动"
for i in {1..12}; do
    if curl -s --connect-timeout 3 \${HEALTH_URL} > /dev/null; then
        echo "回滚成功，健康检查通过"
        exit 0
    fi
    echo "等待10秒后重试... 第\${i}次"
    sleep 10
done

echo "错误：回滚后健康检查失败"
exit 1
"""
                    }
                }
            }
        }
    }

    post {
        always {
            script {
                // 防御式取值，避免变量不存在导致通知失败
                def gitCommit = env.GIT_COMMIT ?: '未获取'
                def fullImg = env.FULL_IMG ?: '未获取'
                def remoteHost = env.REMOTE_HOST ?: '未获取'
                def deployEnv = params.DEPLOY_ENV ?: '未知'

                dingtalk(
                    robot: "jenkins",
                    type: 'MARKDOWN',
                    title: "${currentBuild.currentResult} - ${deployEnv}环境发布通知",
                    text: ["""
### ${currentBuild.currentResult} 发布结果
> 部署环境：${deployEnv}

- 构建编号：${BUILD_NUMBER}
- Git提交号：${gitCommit}
- 镜像地址：${fullImg}
- 目标服务器：${remoteHost}
- 构建耗时：${currentBuild.durationString}
- 构建日志：[点击查看](${env.BUILD_URL})
"""],
                    atAll: currentBuild.currentResult != 'SUCCESS'
                )
            }
        }
    }
}
