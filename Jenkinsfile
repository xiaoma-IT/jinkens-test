pipeline {
    agent any
    // 手动构建下拉选择环境
    parameters {
        choice(name: 'DEPLOY_ENV', choices: ['test', 'prod'], description: '部署环境')
    }
    
    environment {
        DEPLOY_ENV = params.DEPLOY_ENV
        DING_WEBHOOK = credentials('dingding-webhook')

        // Harbor基础地址
        HARBOR_ADDR = "192.168.120.11"
        TEST_HARBOR_REPO = "java/java-demo"
        PROD_HARBOR_REPO = "pipeline/pipeline-demo"

        // Publish Over SSH 全局配置名称
        PROD_SSH_SERVER = "master01"
        TEST_SSH_SERVER = "master02"

        // 容器名称环境隔离
        PROD_CONTAINER_NAME = "pipeline-app"
        TEST_CONTAINER_NAME = "test-app"
        
        NAMESPACE = "k8s.io"

        // 三元表达式自动切换环境配置
        FULL_REPO = DEPLOY_ENV == 'prod' ? PROD_HARBOR_REPO : TEST_HARBOR_REPO
        REMOTE_HOST = DEPLOY_ENV == 'prod' ? PROD_SSH_SERVER : TEST_SSH_SERVER
        CONTAINER_NAME = DEPLOY_ENV == 'prod' ? PROD_CONTAINER_NAME : TEST_CONTAINER_NAME

        // 先占位，镜像tag在打包阶段动态赋值
        GIT_COMMIT = ""
        IMAGE_TAG = ""
        FULL_IMG = ""
    }

    options {
        timestamps()
        timeout(time: 15, unit: 'MINUTES')
        buildDiscarder(logRotator(numToKeepStr: '20'))
    }

    stages {
        stage('1. Maven 编译打包') {
            steps {
                sh '''
                    cd demo
                    mvn clean package -DskipTests
                '''
                // 移到stage内执行git命令，兼容所有Jenkins版本
                script {
                    env.GIT_COMMIT = sh(script: "git rev-parse --short HEAD", returnStdout: true).trim()
                    env.IMAGE_TAG = "v${BUILD_NUMBER}-${env.GIT_COMMIT}"
                    env.FULL_IMG = "${HARBOR_ADDR}/${FULL_REPO}:${env.IMAGE_TAG}"
                }
            }
        }

        stage('2. 构建镜像 & 推送Harbor') {
            steps {
                // 读取Harbor账号密码凭据，无明文打印
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

        // 仅prod环境弹出确认框，test直接跳过
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
                        "NAMESPACE=${NAMESPACE}"
                    ]) {
                        sshPublisher(publishers: [sshPublisherDesc(
                            configName: "${REMOTE_HOST}",
                            transfers: [sshTransfer(
                                postTransfers: [sshCommand(command: '''
                                    crictl pull --auth ${HARBOR_USER}:${HARBOR_PWD} ${FULL_IMG}
                                    
                                    # 循环清理运行容器，kill后等待再删除
                                    while ctr -n ${NAMESPACE} c list | grep -q ${CONTAINER_NAME}; do
                                        ctr -n ${NAMESPACE} tasks kill ${CONTAINER_NAME} 2>/dev/null
                                        sleep 0.5
                                        ctr -n ${NAMESPACE} c delete ${CONTAINER_NAME} 2>/dev/null
                                    done
                                    # 清理残留快照，解决snapshot already exists
                                    ctr -n ${NAMESPACE} snapshot rm ${CONTAINER_NAME} 2>/dev/null || true
                                    
                                    # 启动容器，TZ环境变量替代挂载localtime，规避mount报错
                                    ctr -n ${NAMESPACE} run -d \
                                        --env TZ=Asia/Shanghai \
                                        --net-host \
                                        ${FULL_IMG} \
                                        ${CONTAINER_NAME}
                                    
                                    # 业务健康检查
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

    // 后置统一钉钉通知，always无论成功失败都执行
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
- Git短提交：${env.GIT_COMMIT}
- 镜像地址：${env.FULL_IMG}
- 目标服务器：${REMOTE_HOST}
- 构建耗时：${currentBuild.durationString}
- 构建日志：[点击查看](${env.BUILD_URL})
""",
                atAll: currentBuild.currentResult != 'SUCCESS'
            )
        }
    }
}
