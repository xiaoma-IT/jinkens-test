pipeline {
    agent any
    parameters {
        choice(name: 'DEPLOY_ENV', choices: ['test', 'prod'], description: '部署环境')
    }

    // environment 只放固定字符串、credentials，禁止写三元/params直接赋值
    environment {
        DING_WEBHOOK = credentials('dingding-webhook')
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
                    // 所有参数、三元判断全部放这里
                    env.DEPLOY_ENV = params.DEPLOY_ENV
                    env.FULL_REPO = env.DEPLOY_ENV == 'prod' ? env.PROD_HARBOR_REPO : env.TEST_HARBOR_REPO
                    env.REMOTE_HOST = env.DEPLOY_ENV == 'prod' ? env.PROD_SSH_SERVER : env.TEST_SSH_SERVER
                    env.CONTAINER_NAME = env.DEPLOY_ENV == 'prod' ? env.PROD_CONTAINER_NAME : env.TEST_CONTAINER_NAME

                    // Git 短commit、镜像tag
                    env.GIT_COMMIT = sh(script: "git rev-parse --short HEAD", returnStdout: true).trim()
                    env.IMAGE_TAG = "v${BUILD_NUMBER}-${env.GIT_COMMIT}"
                    env.FULL_IMG = "${env.HARBOR_ADDR}/${env.FULL_REPO}:${env.IMAGE_TAG}"
                }
            }
        }

        stage('1. Maven 编译打包') {
            steps {
                sh '''
                    cd demo
                    mvn clean package -DskipTests
                '''
            }
        }

        stage('2. 构建镜像 & 推送Harbor') {
            steps {
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
                input message: "确认发布到【${DEPLOY_ENV}】环境？", ok: '确认发布'
            }
        }

        stage('4. 远程 containerd 部署') {
            steps {
                withCredentials([
                    usernamePassword(credentialsId: 'harbor', usernameVariable: 'HARBOR_USER', passwordVariable: 'HARBOR_PWD')
                ]) {
                    withEnv([
                        "HARBOR_USER=${HARBOR_USER}",
                        "HARBOR_PWD=${HARBOR_PWD}",
                        "FULL_IMG=${FULL_IMG}",
                        "CONTAINER_NAME=${CONTAINER_NAME}",
                        "NAMESPACE=${NAMESPACE}",
                        "DEPLOY_ENV=${DEPLOY_ENV}"
                    ]) {
                        sshPublisher(publishers: [sshPublisherDesc(
                            configName: "${REMOTE_HOST}",
                            transfers: [sshTransfer(
                                postTransfers: [sshCommand(command: '''
crictl pull --auth ${HARBOR_USER}:${HARBOR_PWD} ${FULL_IMG}

while ctr -n ${NAMESPACE} c list | grep -q ${CONTAINER_NAME}; do
    ctr -n ${NAMESPACE} tasks kill ${CONTAINER_NAME} 2>/dev/null
    sleep 0.5
    ctr -n ${NAMESPACE} c delete ${CONTAINER_NAME} 2>/dev/null
done
ctr -n ${NAMESPACE} snapshot rm ${CONTAINER_NAME} 2>/dev/null || true

ctr -n ${NAMESPACE} run -d \
    --env TZ=Asia/Shanghai \
    --net-host \
    ${FULL_IMG} \
    ${CONTAINER_NAME}

sleep 6
curl -s http://127.0.0.1:8080 > /dev/null || exit 1
echo "【${DEPLOY_ENV}】环境部署完成"
''')]
                            )]
                        )])
                    }
                }
            }
        }
    }

    post {
        always {
            dingTalk(
                robot: [webhook: "${DING_WEBHOOK}"],
                title: "${currentBuild.currentResult} - ${DEPLOY_ENV}环境发布通知",
                text: """
### ${currentBuild.currentResult} 发布结果
> 执行人：${env.EXECUTOR_NAME}
> 部署环境：${DEPLOY_ENV}

- 构建编号：${BUILD_NUMBER}
- Git提交号：${env.GIT_COMMIT}
- 镜像地址：${env.FULL_IMG}
- 目标服务器：${REMOTE_IP}
- 构建耗时：${currentBuild.durationString}
- 构建日志：[点击查看](${env.BUILD_URL})
""",
                atAll: currentBuild.currentResult != 'SUCCESS'
            )
        }
    }
}
