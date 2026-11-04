// main의 새 커밋을 배포 서버에 배포하는 파이프라인(ISE-175). 설계: docs/design/infra/deployment.md "Jenkins"
// 흐름: 커밋 정하기 → GHCR에 그 SHA 이미지 8개가 올라올 때까지 기다리기 → 배포 서버에 SSH로 deploy.sh → 슬랙에 결과.
// 폴링은 잡 정의(infra/jenkins/casc.yaml)에 있다. 되돌리기·재배포는 매개변수 TAG에 40자 커밋 SHA를 넣고 실행한다.
pipeline {
    agent any

    options {
        disableConcurrentBuilds()
        timestamps()
        timeout(time: 40, unit: 'MINUTES')
    }

    parameters {
        string(name: 'TAG', defaultValue: '', description: '비우면 main의 최신 커밋을 배포한다. 40자 커밋 SHA를 넣으면 그 커밋으로 배포한다(되돌리기·재배포)')
    }

    environment {
        // 서비스 이미지 8개(ghcr.io/iseonjwa/<이름>:<커밋 SHA>)
        IMAGES = 'config-server eureka gateway user-service reservation-service payment-service mock-pg frontend'
    }

    stages {
        stage('커밋 정하기') {
            steps {
                script {
                    def tag = params.TAG?.trim()
                    if (tag) {
                        if (!(tag ==~ /[0-9a-f]{40}/)) {
                            env.RESULT = 'error'
                            error('TAG는 40자 16진 커밋 SHA여야 합니다')
                        }
                        env.SHA = tag
                    } else {
                        env.SHA = sh(script: 'git rev-parse HEAD', returnStdout: true).trim()
                    }
                    env.SHORT_SHA = env.SHA.take(7)
                    env.COMMIT_TITLE = sh(returnStdout: true,
                            script: 'git log -1 --format=%s "$SHA" 2>/dev/null || echo "(제목을 확인할 수 없음)"').trim()
                    currentBuild.displayName = "#${env.BUILD_NUMBER} ${env.SHORT_SHA}"
                    echo "배포할 커밋: ${env.SHORT_SHA} ${env.COMMIT_TITLE}"
                }
            }
        }

        stage('이미지 기다리기') {
            steps {
                script {
                    // GHCR의 공개 이미지는 익명 토큰으로 매니페스트를 확인한다. 30초마다, 최대 20분 기다린다.
                    def rc = sh(returnStatus: true, script: '''#!/usr/bin/env bash
                        accept='application/vnd.oci.image.index.v1+json, application/vnd.oci.image.manifest.v1+json, application/vnd.docker.distribution.manifest.list.v2+json, application/vnd.docker.distribution.manifest.v2+json'
                        exists() {
                          local token code
                          token=$(curl -fsS --max-time 15 "https://ghcr.io/token?scope=repository:iseonjwa/$1:pull" | jq -r '.token // empty') || return 1
                          [ -n "$token" ] || return 1
                          code=$(curl -s --max-time 15 -o /dev/null -w '%{http_code}' -I -H "Authorization: Bearer $token" -H "Accept: $accept" \
                            "https://ghcr.io/v2/iseonjwa/$1/manifests/$SHA")
                          [ "$code" = 200 ]
                        }
                        deadline=$(( $(date +%s) + 1200 ))
                        while :; do
                          missing=""
                          for name in $IMAGES; do exists "$name" || missing="$missing $name"; done
                          if [ -z "$missing" ]; then echo "이미지 8개가 모두 있습니다: $SHA"; exit 0; fi
                          if [ "$(date +%s)" -ge "$deadline" ]; then echo "20분 안에 올라오지 않은 이미지:$missing"; exit 10; fi
                          echo "기다리는 이미지:$missing"
                          sleep 30
                        done
                    ''')
                    if (rc != 0) {
                        env.RESULT = 'aborted'
                        error('이미지가 20분 안에 GHCR에 올라오지 않았습니다')
                    }
                }
            }
        }

        stage('배포') {
            steps {
                script {
                    if (!env.DEPLOY_HOST?.trim()) {
                        env.RESULT = 'error'
                        error('DEPLOY_HOST(배포 EC2의 사설 IP)가 비어 있습니다')
                    }
                    // Note: DEPLOY_HOST는 사설 IP여야 한다. 배포 서버 보안 그룹은 Jenkins 보안 그룹에서 오는 22만 허용하는데,
                    // 이 규칙은 VPC 안 사설 IP로 온 연결에만 맞는다. 공인 IP로 붙으면 막힌다.
                    def rc = 255
                    sshagent(['deploy-ssh']) {
                        rc = sh(returnStatus: true, script: '''#!/usr/bin/env bash
                            ssh -o StrictHostKeyChecking=accept-new -o BatchMode=yes "ubuntu@${DEPLOY_HOST}" \
                              "cd ~/iseonjwa && git fetch -q origin && git checkout -q --detach ${SHA} && script/deploy/deploy.sh ${SHA}" 2>&1 | tee deploy.log
                            code=${PIPESTATUS[0]}
                            # deploy.sh가 "== 되돌림 성공: <SHA>"를 남긴다. 짧은 SHA만 뽑아 둔다.
                            awk '/^== 되돌림 성공: /{print substr($4,1,7)}' deploy.log | tail -1 > rollback.txt
                            exit "$code"
                        ''')
                    }
                    if (rc == 0) {
                        env.RESULT = 'success'
                    } else if (rc == 1) {
                        env.RESULT = 'rolledback'
                        env.ROLLBACK_SHA = readFile('rollback.txt').trim()
                        error('배포에 실패해 직전 버전으로 되돌렸습니다')
                    } else if (rc == 3) {
                        env.RESULT = 'rollbackfailed'
                        error('배포와 되돌림이 모두 실패했습니다')
                    } else {
                        env.RESULT = 'error'
                        error("배포 스크립트가 예상하지 못한 종료 코드로 끝났습니다: ${rc}")
                    }
                }
            }
        }
    }

    post {
        always {
            script {
                def commit = env.SHA ? "${env.SHORT_SHA} ${env.COMMIT_TITLE}" : '(커밋을 정하기 전)'
                def build = "(#${env.BUILD_NUMBER})"
                def message
                switch (env.RESULT) {
                    case 'success':
                        message = "[배포 성공] ${commit} ${build}"
                        break
                    case 'rolledback':
                        def previous = env.ROLLBACK_SHA ? "(${env.ROLLBACK_SHA})" : ''
                        message = "[배포 실패] ${commit} ${build} 직전 버전${previous}으로 되돌렸습니다"
                        break
                    case 'rollbackfailed':
                        message = "[배포 실패] ${commit} ${build} 되돌림도 실패했습니다. 서버 확인이 필요합니다"
                        break
                    case 'aborted':
                        message = "[배포 중단] ${commit} ${build} 이미지가 20분 안에 GHCR에 올라오지 않았습니다"
                        break
                    default:
                        message = "[배포 오류] ${commit} ${build} 파이프라인이 실패했습니다. Jenkins 로그를 확인해 주세요"
                }
                // 웹훅 주소는 환경 변수로만 다룬다(Groovy 문자열에 넣지 않고 셸 추적도 끈다). 전송 실패가 빌드 결과를 바꾸지 않는다.
                withCredentials([string(credentialsId: 'slack-webhook-deploy', variable: 'SLACK_URL')]) {
                    withEnv(["MESSAGE=${message}"]) {
                        sh '''#!/usr/bin/env bash
                            set +x
                            jq -n --arg t "$MESSAGE" '{text: $t}' \
                              | curl -sS --max-time 10 -X POST -H 'Content-Type: application/json' --data-binary @- "$SLACK_URL" > /dev/null \
                              || echo "슬랙 전송에 실패했습니다"
                        '''
                    }
                }
            }
        }
    }
}
