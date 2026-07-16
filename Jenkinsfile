pipeline {
    agent any
    parameters {
        choice(name: 'DEPLOY_ENV', choices: ['test', 'prod'], description: '部署环境')
    }

    environment {
        // 钉钉这里不再存webhook，全局配置机器人，env删掉DING_WEBHOOK
        HARBOR_ADDR = "192.168.120.11"
        TEST_HARBOR_REPO = "java/java-demo"
        PROD_HARBOR_REPO = "pipeline/pipeline-demo"
        PROD_SSH_SERVER = "master02"
        TEST_SSH_SERVER = "master01"
        PROD_CONTAINER_NAME = "pipeline-app"
        TEST_CONTAINER_NAME = "test-app"
        NAMESPACE = "k8s.io"
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
- 环境：${DEPLOY_ENV}
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
- 环境：${DEPLOY_ENV}
- 构建号：${BUILD_NUMBER}
"""],
                    atAll: false
                )
                withCredentials([usernamePassword(credentialsId: 'harbor', passwordVariable: 'HARBOR_PWD', usernameVariable: 'HARBOR_USER')]) {
                    sh '''
                        cd demo
                        echo ${HARBOR_PWD} | docker login ${HARBOR_ADDR} -u ${HARBOR_USER} --password-stdin
                        docker build -t ${FULL_IMG} .
                        docker push ${FULL_IMG}
                        docker logout ${HARBOR_ADDR}
                    '''
                }
            }
        }

        stage('3. 生产发布确认') {
            when {
                environment name: 'DEPLOY_ENV', value: 'prod'
            }
            steps {
                dingtalk(
                    robot: "jenkins",
                    type: 'MARKDOWN',
                    title: "流水线进度：发布待确认",
                    text: ["""
### 发布待确认
- 环境：${DEPLOY_ENV}
- 构建号：${BUILD_NUMBER}
"""],
                    atAll: false
                )
                input message: "确认发布到【${DEPLOY_ENV}】环境？", ok: '确认发布'
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
- 环境：${DEPLOY_ENV}
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
NAMESPACE=k8s.io
CONTAINER_NAME=${CONTAINER_NAME}
FULL_IMG=${FULL_IMG}

echo "保存历史版本镜像"

ctr -n k8s.io c list | grep \${FULL_REPO} | awk '{print \$2}' > /root/image_list.txt

echo "image_list.txt内容："
cat /root/image_list.txt
# 空文件直接报错阻断
if [ ! -s /root/image_list.txt ];then
    echo "错误：未抓取到旧镜像，文件为空"
    exit 1
fi

echo "开始拉取镜像 \${FULL_IMG}"
crictl pull \${FULL_IMG}

echo "清理旧容器"
while ctr -n \${NAMESPACE} c list | grep -q \${CONTAINER_NAME}; do
  ctr -n \${NAMESPACE} tasks kill \${CONTAINER_NAME} 2>/dev/null
  sleep 0.5
  ctr -n \${NAMESPACE} c delete \${CONTAINER_NAME} 2>/dev/null
done
ctr -n \${NAMESPACE} snapshot rm \${CONTAINER_NAME} 2>/dev/null || true

echo "启动业务容器"
ctr -n \${NAMESPACE} run -d --env TZ=Asia/Shanghai --net-host \${FULL_IMG} \${CONTAINER_NAME}
sleep 60
curl -s http://127.0.0.1:8080 > /dev/null || exit 1

echo "【${DEPLOY_ENV}】环境部署完成"
"""
                    }
                }
            }
        }
        stage('5. 是否回滚'){
            steps {
                dingtalk(
                    robot: "jenkins",
                    type: 'MARKDOWN',
                    title: "流水线进度：远程 containerd 部署完成，是否回滚",
                    text: ["""
### 是否回滚
- 环境：${DEPLOY_ENV}
- 构建号：${BUILD_NUMBER}
"""],
                    atAll: false
                )
                script {
                    catchError(buildResult: 'ABORTED', stageResult: 'ABORTED') {
                        input message: "是否回滚？", ok: "确认回滚"
                    }
                    // script内部才能写if判断
                    if (currentBuild.result == 'ABORTED') {
                        echo "用户取消回滚"
                        error "终止流程：用户取消回滚"
                    }
                }
            }
        }
        stage('6. 回滚上个版本') {
            steps {
                dingtalk(
                    robot: "jenkins",
                    type: 'MARKDOWN',
                    title: "流水线进度：回滚上个版本",
                    text: ["""
### 正在执行回滚上个版本
- 环境：${DEPLOY_ENV}
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
NAMESPACE=k8s.io
CONTAINER_NAME=\${CONTAINER_NAME}
FULL_IMG=\$(cat /root/image_list.txt)

echo "开始拉取镜像 \${FULL_IMG}"
crictl pull \${FULL_IMG}

echo "清理旧容器"
while ctr -n \${NAMESPACE} c list | grep -q \${CONTAINER_NAME}; do
  ctr -n \${NAMESPACE} tasks kill \${CONTAINER_NAME} 2>/dev/null
  sleep 0.5
  ctr -n \${NAMESPACE} c delete \${CONTAINER_NAME} 2>/dev/null
done
ctr -n \${NAMESPACE} snapshot rm \${CONTAINER_NAME} 2>/dev/null || true

echo "启动业务容器"
ctr -n \${NAMESPACE} run -d --env TZ=Asia/Shanghai --net-host \${FULL_IMG} \${CONTAINER_NAME}
sleep 60
curl -s http://127.0.0.1:8080 > /dev/null || exit 1
echo "【${DEPLOY_ENV}】回滚完成"
"""
                    }
                }
            }
        }
    }

    post {
        always {
            // ========== 钉钉插件标准正确写法 ==========
            dingtalk(
                // 替换成你全局钉钉配置里生成的机器人UUID
                robot: "jenkins",
                type: 'MARKDOWN',
                title: "${currentBuild.currentResult} - ${DEPLOY_ENV}环境发布通知",
                text: ["""
### ${currentBuild.currentResult} 发布结果
> 部署环境：${DEPLOY_ENV}

- 构建编号：${BUILD_NUMBER}
- Git提交号：${env.GIT_COMMIT}
- 镜像地址：${env.FULL_IMG}
- 目标服务器：${REMOTE_HOST}
- 构建耗时：${currentBuild.durationString}
- 构建日志：[点击查看](${env.BUILD_URL})
"""],
                atAll: currentBuild.currentResult != 'SUCCESS'
            )
        }
    }
}
