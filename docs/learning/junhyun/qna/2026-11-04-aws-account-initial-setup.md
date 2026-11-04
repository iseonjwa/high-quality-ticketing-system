---
title: 배포용 AWS 계정을 만든 뒤 무엇을 어떤 순서로 설정하나
date: 2026-11-04
updated: 2026-11-04
asker: Junhyun An
level: 기초
tags: [aws, builder-id, iam, mfa, budget, ec2, security-group, key-pair, free-tier, region]
---

# 배포용 AWS 계정을 만든 뒤 무엇을 어떤 순서로 설정하나

## 질문
M3 배포(ISE-174·175)를 위해 AWS 계정을 만들었다. 콘솔에서 직접 하나씩 하려면 무엇을 어떤 순서로 설정하나.

이어서 물은 것: IAM 사용자를 만들 때 비밀번호 단계가 없었고, 사용자 화면의 보안 자격 증명 탭에도 콘솔 로그인 칸이 없다. 어떻게 하나. 키 페어와 보안 그룹까지 만든 뒤에는, 이렇게 하면 팀원은 어떻게 접속하나.

## 짧은 답
먼저 계정이 어느 가입 방식으로 만들어졌는지 확인한다. 두 방식은 설정 순서가 다르다.

- **새 방식(Sign up for AWS (new))**: Google·GitHub 같은 로그인이나 AWS Builder ID로 가입하고, 자원이 "프로젝트" 단위로 묶인다. 루트 사용자가 없고, IAM 사용자는 콘솔에 로그인할 수 없다. 리전도 연락처 국가로 정해진다. 그래서 할 일은 다음 넷이다.
  1. 요금제와 프로젝트 리전을 확인한다.
  2. Builder ID(또는 가입에 쓴 Google·GitHub 계정)에 MFA를 건다.
  3. 비용 예산 알림을 건다.
  4. SSH 키 페어와 보안 그룹을 만든다.
- **고급 방식(Sign up for AWS (advanced))**: 이메일과 루트 비밀번호로 가입하는 예전 방식이다. 루트에 MFA를 걸고, 콘솔용 IAM 관리자 사용자를 만들고, 리전을 고른 뒤 위 3·4를 한다.

EC2 자체는 크기를 정한 뒤(ISE-176) 만든다. 켜 두는 동안 시간당 과금되기 때문이다.

## 자세히

### 1. 가입 방식 구분
새 방식 계정은 이렇게 보인다.
- 콘솔 오른쪽 위에 자동으로 지어진 프로젝트 이름이 보이고, 리전 선택기가 없다.
- IAM 왼쪽 메뉴가 짧다(역할, 정책, IAM 사용자, 루트 액세스 관리).
- IAM 사용자 화면의 요약에 "콘솔 액세스"가 없고, 보안 자격 증명 탭이 액세스 키부터 시작하며 콘솔 로그인·MFA 칸이 없다.
- 프로젝트·팀·결제는 콘솔이 아니라 AWS Settings(https://settings.aws.com)에서 관리한다.

### 2. 새 방식에서 달라지는 것
- **루트 사용자가 없다.** Builder ID가 프로젝트 계정을 소유한다. 그래서 "루트에 MFA 걸기"는 "Builder ID에 MFA 걸기"로 바뀐다.
- **IAM 사용자는 콘솔용으로 못 쓴다.** AWS 문서는 새 방식에서 콘솔 접근이 있는 IAM 사용자를 만들 수 없다고 적는다. 실제로 계정에 걸린 관리형 SCP(조직이 계정에 거는 권한 상한)가 `iam:*LoginProfile*`(콘솔 비밀번호 만들기·바꾸기)을 거부한다. 콘솔에서도, CLI의 `create-login-profile`로도 비밀번호를 붙일 수 없다. IAM 사용자와 역할은 프로그램 접근(액세스 키)용으로만 만들 수 있다.
- **사람은 팀원 초대로 들인다.** AWS Settings → Team → Invite new team member에서 이메일로 초대한다. 팀원은 Builder ID를 만들어 들어오고, 프로젝트 관리자 권한을 받는다. 결제 정보 보기, 요금제 바꾸기, 팀원 초대는 못 한다. 서버에 SSH만 하면 되는 팀원은 초대할 필요가 없다.
- **리전이 고정된다.** 연락처 국가로 정해지고 바꿀 수 없다. 한국은 아시아 태평양(시드니) `ap-southeast-2`다. 서울 같은 다른 리전을 쓰려면 Paid plan으로 올린 뒤 "고급 기능 활성화"를 해야 한다. 이 활성화는 되돌릴 수 없고, 지출 한도(spend limit)가 사라지며, 루트 이메일·비밀번호를 새로 정하게 된다.
- **서비스 제한**: EC2, EBS, VPC, Elastic Load Balancing, Budgets, CloudShell, CloudWatch는 Free Tier에서도 쓸 수 있다. 스팟 인스턴스 요청, EC2 Fleet은 거부된다.

### 3. MFA
- AWS Settings → **Manage security and sign-in** → Builder ID 프로필의 **Security** → **Register device** → **Authenticator app**(또는 Touch ID 같은 보안 키)이다.
- Google·GitHub·Apple·Amazon 로그인으로 가입했다면 그 계정의 2단계 인증을 켠다. AWS 쪽에 따로 걸 곳이 없다.
- MFA 기기를 모두 잃으면 계정을 되찾을 수 없다. 기기를 둘 등록하거나, 인증 앱의 백업을 켠다.

### 4. 요금제와 비용 알림
- 2025-07-15 이후 만든 계정은 Free plan과 Paid plan 중 하나다. Free plan은 가입 크레딧(최대 200달러)을 쓰며, 6개월 또는 크레딧을 다 쓸 때까지 유지된다. 그동안 요금은 카드가 아니라 크레딧에서 빠진다.
- Billing and Cost Management → Budgets에서 월 비용 예산을 만들고, 금액의 일정 비율에 이메일 알림을 건다. 새 방식의 Paid plan이면 AWS Settings에서 지출 한도도 걸 수 있다. 한도를 넘으면 프로젝트가 멈춘다.
- 주된 비용은 켜 둔 EC2, 붙어 있는 공인 IPv4 주소(인스턴스에 붙어 있어도 과금), EBS 볼륨이다. 시연·측정이 없으면 인스턴스를 멈춘다. 멈춰도 EBS와 Elastic IP는 과금된다.
- Free plan에서 고를 수 있는 인스턴스 유형은 프리 티어 대상(t3.micro·t3.small·t4g.micro·t4g.small·c7i-flex.large·m7i-flex.large)뿐이라고 알려져 있다. m7i-flex.large가 8GB급이다. 커뮤니티 글 기준이라 EC2를 만들 때 콘솔에서 다시 확인한다.

### 5. 키 페어
- 키 페어는 리전마다 따로다. 새 방식이면 프로젝트 리전에 만들어진다.
- ED25519, `.pem`으로 만들고 `~/.ssh/`에 `chmod 400`으로 둔다. 개인 키는 저장소·메신저에 올리지 않는다. 팀원은 각자 공개 키를 서버의 `authorized_keys`에 더하는 방식으로 접속한다.

### 6. 보안 그룹
`docs/design/infra/deployment.md` 3절 표를 따른다.

| 보안 그룹 | 인바운드 |
|---|---|
| Jenkins EC2용 | 22: 팀원 IP |
| 배포 EC2용 | 22: 팀원 IP와 Jenkins 보안 그룹, 8080: 전체(부하 측정 때는 발생기 보안 그룹으로 좁힘) |

- 관리 UI(Grafana·Prometheus 등)는 열지 않고 SSH 터널로 본다.
- "내 IP"는 집·카페마다 바뀐다. 바뀌면 SSH가 막히니 규칙의 IP를 새로 고친다.

### 7. 팀원은 어떻게 접속하나
접속은 세 층으로 나뉘고, 층마다 필요한 것이 다르다.

| 무엇을 하나 | 필요한 것 | 누가 준비하나 |
|---|---|---|
| 서비스 쓰기(브라우저·API, 8080) | 없음. 공개 포트다 | - |
| 서버 접속(SSH, Grafana·Jenkins를 SSH 터널로 보기, `docker logs`) | 팀원 각자의 SSH 키와, 보안 그룹에 등록된 팀원 IP | 팀원이 공개 키와 IP를 보내고, 계정 주인이 서버와 보안 그룹에 등록한다 |
| AWS 콘솔(EC2 켜고 끄기, 보안 그룹 고치기, 부하 발생기 띄우기) | AWS Settings 팀원 초대와 팀원의 Builder ID | 계정 주인이 초대하고 팀원이 14일 안에 받는다 |

- SSH 키는 사람마다 따로 둔다. 팀원은 `ssh-keygen -t ed25519`로 키를 만들고 공개 키(`.pub`)만 보낸다. 공개 키는 비밀값이 아니다. 계정 주인은 그 줄을 서버의 `~/.ssh/authorized_keys`에 더한다. 키 페어 `.pem` 하나를 돌려 쓰지 않는다. 한 명이 빠지거나 키가 새면 그 줄만 지우면 되고, 누가 접속했는지도 가릴 수 있기 때문이다.
- 팀원 IP는 집·카페·교육장마다 바뀐다. 바뀌면 보안 그룹 규칙을 고쳐야 SSH가 열린다. 콘솔 초대를 받은 팀원은 자기 IP를 직접 고칠 수 있다.
- 콘솔 초대를 받은 팀원은 프로젝트 안의 모든 AWS 자원을 다룰 수 있다(인스턴스 삭제, 큰 인스턴스 생성 포함). 결제·크레딧 보기, 요금제 바꾸기, 다른 사람 초대는 못 하고, 비용 알림도 계정 주인에게만 간다. 그래서 실제로 인프라를 다룰 사람만 초대한다.
- IP 관리가 번거로우면 22번을 열지 않고 Systems Manager Session Manager로 접속하는 방법도 있다. 다만 팀원마다 AWS 접근(콘솔 초대와 `aws login`)이 필요하다.

### 8. 고급 방식 계정이라면
- 루트 계정은 결제·계정 설정에만 쓴다. MFA를 걸고, 루트 액세스 키는 만들지 않는다.
- IAM → 사용자 생성 첫 화면에서 "Provide user access to the AWS Management Console"을 체크해야 비밀번호 단계가 나온다. 빠뜨렸으면 사용자 → 보안 자격 증명 → 콘솔 로그인의 **콘솔 액세스 활성화**에서 붙인다. 권한은 `AdministratorAccess`를 주고, 이 사용자에도 MFA를 건다.
- 리전은 오른쪽 위 선택기에서 서울 `ap-northeast-2`로 고른다.

## 이 프로젝트에서는
- 2026-11-04 사용자가 만든 계정은 새 방식이다. 콘솔 오른쪽 위에 자동으로 지어진 프로젝트 이름이 보이고, IAM 사용자를 만들 때 비밀번호 단계가 없었으며, 사용자 화면에 콘솔 로그인 칸이 없었다. 처음 안내는 고급 방식 기준(루트 MFA, 콘솔용 IAM 관리자)이라 맞지 않았다.
- 그때 만든 IAM 사용자 `iseonjwa-admin`은 자격 증명이 없어 쓸 곳이 없으므로 지운다. Jenkins도 SSH로 배포하므로 AWS 액세스 키가 필요 없다.
- 리전은 연락처가 한국이면 시드니다(AWS Settings → Project → Overview → Additional Info에서 확인). ADR 0046과 `load-test.md`는 "같은 리전"만 정했고 특정 리전을 정하지 않았다. 그래서 배포 EC2, Jenkins EC2, 부하 발생기를 모두 시드니에 두면 설계와 어긋나지 않는다. 한국에서 시연 화면을 열 때만 서울보다 응답이 늦다. 서울이 필요하면 Paid plan 전환과 고급 기능 활성화(되돌릴 수 없음)를 먼저 정해야 한다.
- 배포 EC2 크기는 ISE-176(로컬 부하 중 메모리 측정) 뒤 8GB급(m7i-flex.large)과 16GB급 중 고른다. 16GB급이면 요금제를 바꿔야 할 수 있다.
- Jenkins EC2(t3.small급 `[가정]`)는 ISE-175를 시작할 때 만든다.

## 면접 포인트
- 사람이 장기 비밀번호를 가진 IAM 사용자로 일하지 않게 하는 이유: 장기 자격 증명은 새면 막을 때까지 계속 쓰인다. AWS는 사람에게 페더레이션(외부 로그인)과 임시 자격 증명을 권하고, 새 가입 방식은 이것을 기본값으로 강제한다.
- 루트 계정을 일상 작업에 쓰지 않는 이유: 루트는 권한을 제한할 수 없고, 탈취되면 계정 전체를 잃는다.
- 보안 그룹을 다른 보안 그룹으로 참조하는 이유: Jenkins EC2의 IP가 바뀌어도 규칙을 고치지 않고 "Jenkins에서 오는 SSH만"을 표현할 수 있다.

## 참고
- [Compare sign-up options (AWS)](https://docs.aws.amazon.com/accounts/latest/reference/sign-up-for-aws.html)
- [IAM users (AWS)](https://docs.aws.amazon.com/IAM/latest/UserGuide/id_users.html)
- [AWS Regions for your projects (AWS)](https://docs.aws.amazon.com/accounts/latest/reference/project-regions.html)
- [Supported AWS services for Sign up for AWS (new) (AWS)](https://docs.aws.amazon.com/accounts/latest/reference/supported-services-sign-up-new.html)
- [Managed policies for your organization (AWS)](https://docs.aws.amazon.com/accounts/latest/reference/scps-and-rcps-for-projects.html)
- [Activate advanced AWS features (AWS)](https://docs.aws.amazon.com/accounts/latest/reference/activate-advanced-features.html)
- [Manage multi-factor authentication (MFA) in AWS Settings (AWS)](https://docs.aws.amazon.com/accounts/latest/reference/register-mfa-devices.html)
- [Invite team members to collaborate in AWS Settings (AWS)](https://docs.aws.amazon.com/accounts/latest/reference/invite-team-members.html)
- [Manage passwords for IAM users (AWS)](https://docs.aws.amazon.com/IAM/latest/UserGuide/id_credentials_passwords_admin-change-user.html)
- [AWS Free Tier now offers $200 in credits and 6-month free plan (AWS)](https://aws.amazon.com/about-aws/whats-new/2025/07/aws-free-tier-credits-month-free-plan/)
- [AWS Free Tier FAQs](https://aws.amazon.com/free/faqs/)
- [I tried the new AWS free plan updated in July 2025 (Classmethod)](https://dev.classmethod.jp/en/articles/try-new-aws-free-tier-2025/)
- 위 문서는 2026-11-04에 확인했다. Free plan에서 고를 수 있는 인스턴스 유형은 커뮤니티 글 기준이라 콘솔에서 다시 확인한다.
