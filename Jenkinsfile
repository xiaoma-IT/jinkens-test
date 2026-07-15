pipeline {
    // 执行节点，企业一般用agent标签区分构建机
    agent any
    // 全局环境变量，自动读取加密凭据
    environment {
        // 从凭据拉取Harbor账号密码，无明文
        HARBOR_CREDS = credentials('harbor')
        HARBOR_USER = sh(script: "echo ${HARBOR_CREDS} | cut -d: -f1", returnStdout: true).trim()
        HARBOR_PWD = sh(script: "echo ${HARBOR_CREDS} | cut -d: -f2", returnStdout: true).trim()
        DING_WEBHOOK = credentials('dingding-webhook')

        // 仓库固定信息
        HARBOR_ADDR = "192.168.120.11"
        HARBOR_REPO = "pipeline/pipeline-demo"
        // 镜像tag：构建号+git短commit，方便追溯回滚
        GIT_COMMIT = sh(script: "git rev-parse --short HEAD", returnStdout: true).trim()
        IMAGE_TAG = "v${BUILD_NUMBER}-${GIT_COMMIT}"
        FULL_IMG = "${HARBOR_ADDR}/${HARBOR_REPO}:${IMAGE_TAG}"

        // 部署目标机器
        SSH_SERVER = "k8s-master01"
        CONTAINER_NAME = "pipeline-app"
        NAMESPACE = "k8s.io"
    }

    // 流水线参数，支持手动输入切换环境
    parameters {
        choice(name: 'DEPLOY_ENV', choices: ['test', 'prod'], description: '部署环境')
    }

    // 全局选项：超时、丢弃旧构建、禁用并行
    options {
        timestamps() // 日志打印时间戳
        timeout(time: 15, unit: 'MINUTES') // 15分钟超时失败
        buildDiscarder(logRotator(numToKeepStr: '20')) // 保留20条构建记录
    }

    // 分阶段流程
    stages {
        stage('1. 代码校验 & Maven打包') {
            steps {
                sh '''
                    cd demo
                    mvn clean package -DskipTests
                '''
            }
        }

        stage('2. 构建镜像 & 推送Harbor') {
            steps {
                // 安全登录，无明文密码打印日志
                withCredentials([string(credentialsId: 'harbor', variable: 'harborSecret')]) {
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
        stage('3. 生产部署确认') {
            steps {
                input message: '确认发布到生产环境？', ok: '确认发布'
            }
        }

        stage('4. 远程服务器部署 containerd') {
            steps {
                // 调用全局配置好的SSH服务器，不用手写ssh -i
                sshPublisher(publishers: [sshPublisherDesc(
                    configName: SSH_SERVER,
                    transfers: [sshTransfer(
                        postTransfers: [sshCommand(command: '''
                            # 拉取镜像
                            crictl pull --auth ${HARBOR_USER}:${HARBOR_PWD} ${FULL_IMG}

                            # 循环清理运行中容器，彻底解决删除报错
                            while ctr -n ${NAMESPACE} c list | grep -q ${CONTAINER_NAME}; do
                                ctr -n ${NAMESPACE} tasks kill ${CONTAINER_NAME} 2>/dev/null
                                sleep 3
                                ctr -n ${NAMESPACE} c delete ${CONTAINER_NAME} 2>/dev/null
                            done
                            # 清理快照
                            ctr -n ${NAMESPACE} snapshot rm ${CONTAINER_NAME} 2>/dev/null || true

                            # 启动容器，时区环境变量规避挂载报错
                            ctr -n ${NAMESPACE} run -d \
                                --env TZ=Asia/Shanghai \
                                --net-host \
                                ${FULL_IMG} \
                                ${CONTAINER_NAME}

                            # 健康检查
                            sleep 6
                            curl -s http://127.0.0.1:8080 > /dev/null || exit 1
                            echo "部署完成"
                        ''')]
                    )]
                )])
            }
        }
    }

    // 后置统一处理：无论成功/失败/取消都会执行，企业核心告警能力
    post {
        always {
            // 钉钉统一推送Markdown通知
            dingTalk robot: [
                webhook: "${DING_WEBHOOK}"
            ],
            title: "${currentBuild.currentResult} 项目构建通知",
            text: """
### ${currentBuild.currentResult} - ${env.JOB_NAME} 发布结果
> 执行人：${env.EXECUTOR_NAME}
> 部署环境：${params.DEPLOY_ENV}

- 构建编号：${BUILD_NUMBER}
- Git提交号：${GIT_COMMIT}
- 镜像版本：${IMAGE_TAG}
- 目标服务器：${SSH_SERVER}
- 构建耗时：${currentBuild.durationString}
- 构建日志：[点击查看](${env.BUILD_URL})
            """,
            atAll: currentBuild.currentResult != 'SUCCESS' // 失败@所有人，成功不@
        }
    }
}
