# Owl 클라우드는 적게 저장하고 잘게 허락해야 한다

Owl 클라우드와 커넥터를 여러 사용자에게 배포하려면 보안 기능을 몇 개 더하는 것으로는 부족하다. **제품 원칙을 뒤집는 결정**이 먼저 필요하다. PRD N8("기기와 내 폰에만 존재. 외부 전송 없음. 예외는 A13뿐")과 9장 비목표("여러 사용자, 계정, 클라우드 동기화", "화자 구분")를 다시 쓰지 않으면 시작할 수 없다. 앞선 MCP 조사는 개인·오프라인 전제였고, 그때 공개 원격 구조는 "PRD가 로컬 전용을 버리기 전까지 만들지 않는다"고 결론 냈다. 이번 범위는 바로 그 구조를 다수 사용자 플랫폼으로 정식 채택하는 일이다. 권장 설계는 세 줄로 요약된다. 첫째, 클라우드에는 폰이 **"사용자 본인이 참여한 대화"로 판정한 구간의 텍스트, 북마크, 장소 이름만** 올린다. 음성, 정밀 좌표, 앱 사용 기록은 올리지 않는다. 둘째, 서버가 읽을 수 있는 데이터는 **사용자별·데이터 종류별 키로 봉투 암호화**하고, 데이터베이스 행 수준 보안으로 한 번 더 격리한다. 셋째, REST 앱과 MCP 클라이언트(Claude, ChatGPT)를 **인가 서버 하나**가 대상(audience)이 분리된 토큰으로 처리한다. scope는 기본·민감·제한 세 등급으로 나누고, 전사 원문은 심사를 통과한 앱과 허용 목록에 있는 AI 클라이언트에게만 사용자가 고른 기간과 만료 조건으로 준다.

공개된 사고들을 보면 피해는 암호가 깨져서가 아니라 권한을 가진 누군가에게서 나왔다. 객체 권한 검사 누락(Tea), 코드에 커밋된 비밀(Omi, Rabbit), 연동사가 쌓아 둔 리프레시 토큰 탈취(Salesloft Drift, Context.ai→Vercel), 동의 피싱이 그 예다. 그래서 출시 전에 반드시 있어야 하는 것은 **클라이언트별 일괄 폐기 스위치**, **사용자가 보는 연결 알림과 접근 기록과 "모두 끊기"**, 그리고 **스토킹에 대비한 대기 시간**이다. 한국법상 커넥터 연결 한 번은 동의 세 건으로 보는 것이 보수적이다. 개인정보보호법 제17조 제3자 제공 동의, 받는 자가 해외 AI라면 제28조의8 국외이전 별도 동의, 장소가 포함되면 위치정보법 제19조 동의와 통보다. 가장 큰 미해결 위험은 운영자 서버가 비당사자 대화를 받아 다른 곳으로 넘기는 행위가 통신비밀보호법상 어떻게 평가되느냐다. 출시 순서는 다음과 같다. PRD 결정, 법률 의견, 온디바이스 당사자 판정, 커넥터 없는 동기화, 허용 AI 클라이언트 대상 MCP 폐쇄 베타, 일반 개발자 개방 순이다.

## 위협 모델: 피해는 암호 해독이 아니라 권한을 가진 쪽에서 나온다

앞선 조사에서는 공격면이 사용자 한 명의 PC와 LLM 하나였다. 클라우드가 생기면 Owl은 **N명의 하루 대화를 한곳에 모은 허브**가 된다. 위협 모델은 지킬 대상(자산), 공격자, 경로를 표로 정리한 것이다. 자산부터 보자. 전사에는 사용자의 건강, 연애, 직장 갈등이 담기고, 무엇보다 **동의한 적 없는 주변인의 말**이 담긴다. 장소 기록은 집, 직장, 병원, 종교시설을 드러낸다. 이런 성격 때문에 Owl 데이터는 Gmail보다 민감하다고 봐야 한다. Facebook의 "친구 데이터" 권한으로 앱 설치자 약 27만 명에게서 최대 약 8,700만 명의 정보가 빠져나간 사건이 있었다([Facebook newsroom](https://about.fb.com/news/2018/04/restricting-data-access/)). 이것이 바로 "한 사람의 동의로 다른 사람의 데이터가 넘어가는" 문제이고, Owl 전사는 그 문제의 음성판이다.

| 공격자 | 실제 선례 | 노리는 것 | 1차 방어 |
|---|---|---|---|
| API 결함을 찾는 외부 공격자 | Tea: 일반 사용자 API 키로 DM 약 110만 건 열람([Security.org](https://www.security.org/identity-theft/breach/tea-app/)). Omi: 인증 우회와 공개 저장소에 커밋된 운영 암호화 비밀([연구자 공개](https://kasparovabi.github.io/security-research/2026-04-omi-disclosure.html)). Rabbit r1: 코드에 하드코딩된 TTS 키로 전체 응답 이력 열람 가능 주장([Android Authority](https://www.androidauthority.com/rabbit-r1-security-flaw-3455555/)) | 모든 사용자 데이터 | 토큰에서만 user_id 도출, RLS, 비밀 관리, 침투 시험 |
| 토큰 창고가 된 연동사 | Salesloft Drift 토큰으로 다수 Salesforce 조직 대량 반출([GTIG](https://cloud.google.com/blog/topics/threat-intelligence/data-theft-salesforce-instances-via-salesloft-drift?hl=en)). Context.ai 침해 후 소비자 OAuth 토큰으로 Vercel 내부 진입([TechCrunch](https://techcrunch.com/2026/04/20/app-host-vercel-confirms-security-incident-says-customer-data-was-stolen-via-breach-at-context-ai/)) | 다수 사용자의 리프레시 토큰 | 짧은 토큰, 회전과 재사용 탐지, 클라이언트별 일괄 폐기 |
| 동의 피싱 | "Google Docs"라는 이름의 악성 OAuth 앱 웜, 약 100만 명([GovInfoSecurity](https://www.govinfosecurity.com/attackers-unleash-oauth-worm-via-google-docs-app-a-9888)). 위조로 얻은 Microsoft 인증 배지([Proofpoint](https://www.proofpoint.com/us/blog/cloud-security/dangerous-consequences-threat-actors-abusing-microsofts-verified-publisher)). 전화로 연결 승인을 유도한 UNC6040([GTIG](https://cloud.google.com/blog/topics/threat-intelligence/voice-phishing-data-extortion)) | 사용자의 동의 클릭 | 표시 이름 불신, 미검증 앱 상한, 코드 입력 흐름 금지 |
| 과수집·재판매하는 "정상" 앱 | Gmail 연동사 직원이 메일 약 8,000통 열람([Engadget](https://www.engadget.com/2018-07-03-third-party-app-developers-reading-gmail.html)). 위치 브로커 Gravy·Venntel 판매 금지 명령([FTC](https://www.ftc.gov/news-events/news/press-releases/2025/01/ftc-finalizes-order-prohibiting-gravy-analytics-venntel-selling-sensitive-location-data)) | 전사·위치의 2차 이용 | Limited Use 약관, 심사, 감사, 파기 웹훅 |
| 친밀한 관계의 감시자 | 이중 용도 앱이 "스파이웨어만큼 강력"하고 백신 탐지율은 최대 13%([Chatterjee et al. 2018](https://www.cs.cornell.edu/~rahul/papers/IPV_Spyware.pdf)) | 피해자 계정에 자기 앱 연결 | 대기 시간, 다채널 알림, 안전 점검 |
| 주변 음성을 통한 프롬프트 인젝션 | 사용자 말에 겹친 오디오 주입이 Gemini 3 Pro에서 평균 69.10% 성공([arXiv 2607.28165](https://arxiv.org/abs/2607.28165)) | AI 클라이언트를 통한 유출 | 응답량 제한, 출력 무력화, 읽기 전용 도구 |
| 운영자 내부자와 법적 강제 | 카카오는 2024년 상반기 압수수색 영장으로 5,715,759건 제공([SBS Biz](https://biz.sbs.co.kr/amp/article/20000193881)) | 서버의 평문 전체 | 최소 저장, 짧은 보관, 임시 접근 승인, 투명성 보고 |
| 인수·폐업 | Limitless는 Meta 인수 뒤 약 14일 안에 내보내지 않은 데이터 삭제 예고([9to5Mac](https://9to5mac.com/2025/12/05/rewind-limitless-meta-acquisition/)) | 정책으로만 한 약속 | 암호학적 보장, 90–180일 유예 |

이 표를 하나로 꿰는 틀이 Simon Willison의 **"치명적 삼중주"**다. 에이전트가 사적 데이터, 신뢰할 수 없는 콘텐츠, 외부 통신 능력을 동시에 가지면 프롬프트 인젝션으로 데이터가 샌다는 것이다. 프롬프트 인젝션은 데이터 속 문장이 명령처럼 모델을 조종하는 공격이다([Willison](https://simonwillison.net/2025/Nov/2/new-prompt-injection-papers/)). Owl 전사는 **사적 데이터이면서 동시에 신뢰할 수 없는 콘텐츠**다. 그래서 Owl 클라우드는 연결된 모든 AI 클라이언트에 삼중주의 두 다리를 한꺼번에 공급한다. 클라우드가 바꾸는 것은 규모다. 개인용 구조에서 광고 방송 한 편은 착용자 한 명의 기록에 들어갔다. 이제는 같은 문장이 **수많은 사용자의 클라우드 전사에 동시에 저장되고**, 각자의 AI 클라이언트에서 따로 터진다. 반대로 방어에도 기회가 생긴다. 서버는 여러 사용자 전사에 같은 명령형 문장이 한꺼번에 나타나는 패턴을 볼 수 있는 유일한 위치다. 이 탐지 아이디어는 선례가 없는 추론이다.

## 저장: 서버가 읽을 수 있는 데이터를 줄이고, 읽을 수 있는 것은 사용자별 키로 묶는다

### "운영자도 못 본다"와 "제3자 앱이 읽는다"는 동시에 약속할 수 없다

암호화 모델은 운영자에게 얼마나 강하게 버티느냐에 따라 네 가지로 나뉜다. 기본은 **봉투 암호화(envelope encryption)**다. 클라우드 KMS(키 관리 서비스)의 마스터 키가 데이터 키(DEK)를 감싸고, 데이터 키가 실제 레코드를 암호화한다. KMS 암호화 호출 한 번은 4,096바이트로 제한된다. 그래서 대량 데이터는 데이터 키로 암호화하는 것이 표준이다([AWS KMS](https://docs.aws.eu/kms/latest/developerguide/kms-cryptography.html)). 사용자마다 KMS 키를 따로 두면 키 하나당 월 요금이 붙어 비싸다. 그래서 보통은 KMS 키 하나로 사용자별 데이터 키를 감싸 DB에 저장한다([AWS Architecture Blog](https://aws.amazon.com/blogs/architecture/simplify-multi-tenant-encryption-with-a-cost-conscious-aws-kms-key-strategy)). 이 구조의 장점은 **크립토 시레딩(crypto-shredding)**이다. 키를 지우면 그 키로 암호화된 데이터가 전부 읽을 수 없게 되므로, 지운다는 약속이 진짜가 된다. 단, 이 방법은 키가 사용자마다 따로 있을 때만 통한다. 백업도 같은 키로 암호화돼 있어야 "모든 사본"이 지워진다([WorkOS](https://workos.com/blog/cryptographic-key-isolation-multi-tenant-saas)). KMS 키 삭제에는 7–30일의 의무 대기 기간이 있다. 이것이 실제 파기까지 걸리는 시간이다([AWS KMS 문서](https://docs.aws.amazon.com/kms/latest/developerguide/deleting-keys.html)).

| 모델 | 운영자 열람 | 제3자·MCP 상시 응답 | 비용·난이도 | Owl 판단 |
|---|---|---|---|---|
| 1. 서버 측 암호화 + 사용자별 데이터 키 | 가능 | 쉬움 | 낮음 | 최소선 |
| 2. 1 + 데이터 종류별 키 분리, 복호화마다 (user_id, data_type) 기록 | 가능하나 전부 기록됨 | 쉬움 | 중간 | **출시 권장** |
| 3. 폰 중계: 서버는 암호문만, 질의는 폰이 복호화해 응답 | 불가 | 폰이 온라인일 때만 | 중간, Android 백그라운드 제약 | 고급 옵션 후보 |
| 4. 원격 증명 TEE: 공개된 코드에만 키 방출 | 불가(증명 신뢰 시) | 가능 | 매우 높음 | 장기 |

현재 이 긴장을 어떻게 다루는지 선례가 분명하다. Apple은 Health와 키체인을 기본 종단간 암호화(E2EE)로 보호하고, Advanced Data Protection을 켜면 보호 범위가 더 넓어진다. 그러나 **캘린더, 연락처, 메일은 다른 서버·프로토콜과 상호운용해야 하므로 E2EE 밖에 둔다**([Apple](https://support.apple.com/102651)). Owl의 OAuth/MCP API도 같은 처지다. 제3자가 읽어야 하는 데이터는 서버가 읽을 수 있어야 한다. "운영자도 못 본다"와 "상시 서버 측 처리"를 함께 약속하는 제품도 있지만, 모두 원격 증명 기반의 기밀 컴퓨팅(TEE)을 쓴다. TEE는 하드웨어가 격리한 실행 환경이다. 원격 증명은 그 환경이 공개된 특정 코드를 돌리고 있음을 기기가 확인하는 절차다. Amazon Bee는 기기가 만든 키와 Nitro 기반 격리 환경을 쓰고 Trail of Bits 검토를 받았다고 밝혔다([Bee](https://bee.computer/blog/private-ai)). Apple Private Cloud Compute는 운영 이미지를 공개 투명성 로그에 올린다([Apple Security Research](https://security.apple.com/blog/expanding-pcc)). 다만 PCC는 요청을 처리하고 지우는 무상태 구조이고 저장소가 아니다. 소규모 팀이 이 수준의 검증 가능성을 갖추기는 어렵다.

따라서 권장은 이렇다. **출시 때는 모델 2를 쓰고, 마케팅과 PRD에 "운영자는 기술적으로 읽을 수 있으며 모든 접근이 기록된다"고 정직하게 적는다.** 데이터 종류마다 사용자가 보호 수준을 고르게 한다. 음성처럼 커넥터가 읽을 필요가 없는 데이터는 폰이 직접 암호화해 서버가 복호화할 수 없는 "보관 전용" 구역에만 둔다. 이는 Android 백업 E2EE와 같은 방식이다. 이 방식은 사용자의 잠금화면 PIN으로 키를 감싸고, 추측 횟수를 제한하는 HSM에 맡겨 "Google도 접근할 수 없게" 했다([Google Security Blog](https://security.googleblog.com/2018/10/google-and-android-have-your-back-by.html)). 키 계층은 나중에 모델 3이나 4로 옮길 수 있게 설계해 둔다. 이렇게 고르는 핵심 이유는 Limitless 사례에 있다. 인수 뒤 "데이터는 안전하다"는 말은 정책 약속에 불과했고, 소유자가 바뀌자 그 약속은 살아남지 못했다([9to5Mac](https://9to5mac.com/2025/12/05/rewind-limitless-meta-acquisition/)). 소유자가 바뀌어도 남는 것은 암호학적 보장이나 아예 저장하지 않은 데이터뿐이다.

### 사용자 간 분리는 코드, DB, 키의 세 겹으로 한다

OWASP API 보안 1위는 **BOLA(객체 수준 권한 검사 누락)**다. 요청의 객체 ID만 바꿔 남의 데이터를 가져가는 공격이다. OWASP는 "객체 ID를 받는 모든 엔드포인트가 객체 수준 권한을 검사해야 한다"고 하고, 추측할 수 없는 ID 사용과 권한 검사 테스트를 권한다([OWASP API1:2023](https://owasp.org/API-Security/editions/2023/en/0xa1-broken-object-level-authorization/)). Tea의 DM 유출이 정확히 이 유형이었다. Firebase 저장소를 보안 규칙 없이 열어 둔 것도 같은 사건이었다([Security.org](https://www.security.org/identity-theft/breach/tea-app/)). Owl의 최소 격리 구성은 여섯 가지다. 첫째, 모든 경로에서 `user_id`는 경로나 본문이 아니라 **토큰에서만** 꺼낸다. 둘째, Postgres 행 수준 보안(RLS)을 켜고 `FORCE ROW LEVEL SECURITY`를 건다. RLS는 DB가 모든 쿼리에 "이 사용자 행만" 조건을 강제하는 기능이다. 테이블 소유자와 슈퍼유저는 RLS를 우회하므로, 앱은 소유자가 아닌 역할로 접속하고 트랜잭션마다 `app.user_id`를 설정한다([PostgreSQL](https://www.postgresql.org/docs/current/ddl-rowsecurity.html)). 셋째, 객체 ID는 추측 불가능한 UUID로 한다. 넷째, KMS 암호화 컨텍스트에 `user_id`를 묶는다. 그러면 검사 하나가 빠져도 다른 사용자의 행은 현재 요청이 풀 수 없는 암호문으로만 나온다. 다섯째, CI에서 "사용자 A의 토큰으로 B의 객체 ID를 요청하면 실패"를 자동으로 검사한다. 여섯째, 비밀은 비밀 관리자에 두고 저장소를 스캔한다. 이 여섯째 항목은 Omi와 Rabbit 사례가 남긴 교훈이다. Omi에서는 커밋된 `ENCRYPTION_SECRET` 때문에 기본 배포가 "사실상 암호화 없음" 상태였고, 개발용 우회 경로도 있었다. 수정은 보고 후 103일이 지나서야 외부 기여자 손으로 들어갔다([연구자 공개](https://kasparovabi.github.io/security-research/2026-04-omi-disclosure.html)). 이 주장은 공식 권고문으로 확인되지 않은 자체 공개다. 그래도 Owl처럼 모노레포를 공개할 가능성이 있는 프로젝트에는 직접적인 경고다.

제3자 토큰이 생기면 축이 하나 더 늘어난다. 사용자만 검사해서는 안 되고 **(사용자, 클라이언트, scope)**를 함께 검사해야 한다. 그렇지 않으면 위치 권한만 받은 앱이 전사 엔드포인트를 부르는 BFLA(기능 수준 권한 누락)가 생긴다. "공유 링크" 기능은 출시하지 않는다. Omi의 보고 중에는 인증 없이 공유 대화와 화자 이름이 열리는 결함이 있었다(같은 출처).

### 운영자 접근과 수사기관 요구는 저장량이 결정한다

카카오 사례는 영장 노출이 서버에 무엇을 저장하느냐에 달려 있음을 보여준다. 카카오의 영장 요청은 2015년 3,145건에서 2018년 15,153건으로 늘었다([뉴스핌](https://newspim.com/news/view/20191008000576)). 2024년 상반기에는 영장 요청 문서 19,418건에 대해 570만 건 넘게 제공했다([SBS Biz](https://biz.sbs.co.kr/amp/article/20000193881)). 카카오의 대응은 정책이 아니라 기술이었다. 종단간 암호화 "비밀채팅"을 도입하고 서버 보관 기간을 줄였다([카카오](https://www.kakaocorp.com/page/detail/7616)). 법 절차도 알아 둬야 한다. 전사나 위치 이력 같은 내용은 압수수색 영장이 필요하다. 이름이나 ID 같은 통신이용자정보는 영장 없이 요청될 수 있다. 다만 2024년 1월부터는 정보를 받은 수사기관이 30일 안에 이용자에게 통지해야 한다([주간경향](https://weekly.khan.co.kr/article/202408190600021)). Owl이 이 의무의 대상인 전기통신사업자에 해당하는지는 확인되지 않았다.

그래서 운영 통제는 다섯 가지로 정한다. 첫째, 상시 운영 데이터 접근을 없앤다. 꼭 필요하면 티켓, 두 번째 승인자, 시간 제한이 있는 임시 접근만 허용한다. 둘째, KMS 복호화 기록을 사용자별 "내 데이터에 접근한 주체" 화면에 보여준다. 여기에는 운영자, 제3자 앱, 법적 요구가 모두 포함된다. 셋째, 반기마다 투명성 보고서를 낸다. 넷째, 금지 명령이 없는 한 이용자에게 통지한다. 다섯째, 영장에 적힌 기간과 데이터 종류만 제출한다. 이 모든 것보다 효과가 큰 것은 구조다. 보관 기간이 짧고 저장 범위가 좁으면 내줄 것도 적다.

### 기본값은 "아카이브"가 아니라 "캐시"다

Tea에서 노출된 이미지 약 7만 2천 장은 정책상 지웠어야 할 2024년 2월 이전 가입자의 "레거시" 데이터였다([Security.org](https://www.security.org/identity-theft/breach/tea-app/)). 보관 실패가 피해 범위를 키운 것이다. 경쟁 제품들도 이미 최소화를 내세운다. Bee는 음성을 "메모리에서만 처리하고 즉시 삭제"한다고 밝힌다([Bee](https://bee.computer/blog/private-ai)). Plaud는 Cloud Sync를 켜지 않으면 클라우드에 아무것도 남기지 않는다고 밝힌다([Plaud Trust Center](https://www.plaud.ai/pages/trust)). Owl의 권장 기본값은 다음과 같다. 서버 보관은 이동 창(예: 90일)으로 하고 "영구"는 명시적으로 골라야 한다. 백업 보관은 35일 이하로 두고, "삭제한 데이터는 N일 안에 백업에서도 사라진다"고 문서화한다. 동기화 범위는 데이터 종류별로 켜고 끄며, 이 분류를 OAuth scope 분류와 일치시킨다. 이렇게 하면 저장 설계와 허가 설계가 같은 분류 체계를 쓴다.

가장 중요한 최소화는 **당사자 판정**이다. 폰은 사용자 본인 목소리를 등록해 두고, 그 목소리가 들어 있는 대화 구간만 클라우드에 올린다. 사용자가 참여하지 않은 주변 대화는 기기에서 음성과 전사를 모두 바로 폐기한다. 이 조치의 법적 근거는 아래 한국법 절에서 다룬다. 기술적 한계도 정직하게 적어야 한다. 사용자가 말없이 듣기만 한 대화도 법적으로는 당사자 대화일 수 있으므로, "본인 목소리 있음"은 보수적인 근사치다. 한국어 STT 구어체 환경에서 화자 확인 정확도는 측정된 적이 없다. 업로드 직전에는 주민등록번호, 전화번호, 카드번호 패턴을 마스킹한다. Presidio에 이미 `KR_RRN` 인식기가 있다([Presidio](https://microsoft.github.io/presidio/supported_entities)). 장소는 사용자가 붙인 이름과 구 단위 정도의 거친 위치만 올리고, 위경도 원값은 폰에 남긴다. 이는 결정 #32(네트워크 지오코딩 금지)의 취지와도 맞는다.

## 인가 서버: 서버 하나, scope 세 등급, 짧은 토큰

### 하나의 인가 서버가 REST와 MCP 두 리소스 서버를 맡는다

OAuth는 비밀번호를 넘기지 않고 특정 권한(scope)만 담은 토큰을 발급받아 쓰게 하는 위임 인가 표준이다. 토큰을 발급하는 쪽이 **인가 서버(AS)**이고, 데이터를 가진 쪽이 **리소스 서버**다. 액세스 토큰은 API 호출에 쓰는 짧은 출입증이고, 리프레시 토큰은 새 액세스 토큰을 받아 오는 긴 열쇠다. 주요 업체들은 MCP를 위해 인가 서버를 따로 세우지 않고 **기존 인가 서버 위에 MCP를 얹었다**. Atlassian은 auth.atlassian.com에서 DCR과 CIMD를 모두 지원한다([Scalekit](https://www.scalekit.com/blog/atlassian-mcp-server)). Linear는 OAuth 2.1 MCP 엔드포인트에서 기존 OAuth 토큰도 받는다([Linear](https://linear.app/docs/mcp)). Notion은 사용자 권한을 그대로 물려받는 사용자별 OAuth를 쓴다([Notion](https://www.notion.com/help/notion-mcp)). Owl도 `auth.owl…` 하나가 `api.owl…`(REST)과 `mcp.owl…`(MCP)에 대상(audience)이 다른 토큰을 발급하게 한다. 두 리소스 서버는 각자 보호 자원 메타데이터(PRM, 토큰을 어디서 받아 올지 알려 주는 `.well-known` 문서)를 게시한다. 리소스 서버는 자기 대상이 아닌 토큰을 "반드시 거부"해야 한다([RFC 9700](https://datatracker.ietf.org/doc/html/rfc9700)). 같은 허가(grant) 모델을 공유하므로 연결된 앱 화면, 폐기, 감사 기록이 REST와 MCP에 똑같이 적용된다.

모든 클라이언트에 적용할 기준선은 OAuth 보안 모범 사례인 RFC 9700이다. 내용은 이렇다. 공개 클라이언트는 PKCE를 필수로 쓴다. PKCE는 일회용 비밀의 해시를 먼저 보내고 코드 교환 때 원본을 내밀어 인가 코드 가로채기를 막는 장치다. 리다이렉트 URI는 정확한 문자열로만 일치시킨다. 암묵적 그랜트와 비밀번호 그랜트는 금지한다. 동의 화면은 CSP `frame-ancestors`로 클릭재킹을 막는다. 신뢰할 수 없는 클라이언트가 동의를 거절당하면 그 클라이언트로 리다이렉트하지 않는다(같은 출처). 여기에 RFC 9207 `iss` 응답 파라미터를 더한다. 이는 응답을 보낸 인가 서버가 요청한 그 서버인지 확인해 믹스업 공격을 막는 장치이고, ChatGPT와 2026-07-28 MCP 스펙이 요구한다([OpenAI Apps SDK](https://developers.openai.com/apps-sdk/build/auth); [MCP Authorization](https://modelcontextprotocol.io/specification/latest/basic/authorization)). FAPI 2.0, PAR, DPoP, RAR, CIMD를 소규모 팀이 직접 구현하는 것은 무리다. 인증된 기성 제품이나 오픈소스(Keycloak, node-oidc-provider, Ory 등)에서 고르되, 이 기능들을 실제로 지원하는지는 이번 조사에서 확인하지 못했으므로 선정 전에 검증해야 한다.

### scope는 데이터 종류, 기간 깊이, 위험 등급으로 나눈다

성숙한 플랫폼들은 같은 패턴으로 수렴했다. Google은 `gmail.readonly`를 **제한(restricted)** scope로 분류해 검증과 보안 평가를 요구한다. 반면 캘린더 scope는 **민감(sensitive)**으로 두어 검증만 요구한다([Nylas](https://developer.nylas.com/docs/dev-guide/provider-guides/google/google-verification-security-assessment-guide)). Fitbit의 후속인 Google Health API는 **모든 scope를 제한 등급**으로 둔다([Google Health API](https://developers.google.com/health/about)). Android Health Connect는 데이터 종류마다 읽기와 쓰기 권한을 나누고, 운동 경로 GPS를 별도 권한으로 떼어 낸다. 또 **30일보다 오래된 기록 읽기**(`READ_HEALTH_DATA_HISTORY`)와 백그라운드 읽기를 별도 권한으로 둔다([Health Connect](https://developer.android.com/health-and-fitness/guides/health-connect/plan/data-types)). Google은 사용자가 scope를 하나씩 체크 해제할 수 있게 하고, 앱이 "모든 scope를 받았다고 가정하면 안 된다"고 명시한다([Google granular permissions](https://developers.google.com/identity/protocols/oauth2/resources/granular-permissions)). 시간 제한 허가가 만료되면 리프레시 토큰도 죽는다는 선례도 있다([Google OAuth](https://developers.google.com/identity/protocols/oauth2)). 이를 Owl에 옮긴 scope 목록이 아래 표다. 표에서 "보류"는 정의만 해 두고 출시 때 발급하지 않는다는 뜻이다. 정밀 좌표나 음성처럼 클라우드에 아예 없는 데이터는 어떤 토큰으로도 꺼낼 수 없다. **데이터의 물리적 부재가 가장 강한 접근 통제**다.

| scope | 등급 | 내용 | 받을 수 있는 클라이언트 | 기본 기간 깊이 | 법적 꼬리표 |
|---|---|---|---|---|---|
| `openid`, `profile` | 기본 | 계정 식별자, 표시 이름 | 모두 | – | 제3자 제공(최소 항목) |
| `owl.device.status` | 기본 | 배터리, 동기화 상태. 내용 없음 | 모두 | – | – |
| `owl.bookmarks.read` | 민감 | 북마크 시각, 라벨 | 검증 앱, 허용 AI, 미검증 앱(사용자 상한) | 허가 이후 + 최근 7일 | 제3자 제공 |
| `owl.timeline.read` | 민감 | 기록 시작·끝·길이. 텍스트 없음 | 위와 같음 | 같음 | 제3자 제공 |
| `owl.places.coarse.read` | 민감 | 사용자가 붙인 장소 이름, 구 단위 위치 | 검증 앱, 허용 AI | 같음 | **위치정보법 제19조 동의·통보** |
| `owl.summaries.read` | 민감 | 하루 요약, 장소·대화 시간 집계(A12, A19 수준) | 검증 앱, 허용 AI | 같음 | Owl이 만든 AI 요약이면 AI 기본법 고지 |
| `owl.transcripts.read` | 제한 | 당사자 판정을 통과한 대화 전사. 상대 발언은 가명 처리가 기본 | 심사 통과 앱, 허용 AI(아래 대기 규칙 적용) | 같음 | 제3자 제공, 국외이전, 통비법 위험 |
| `owl.history` | 제한(수식어) | 최근 7일보다 오래된 기록 | 심사 통과 앱 | 사용자가 고른 범위 | 위와 같음 |
| `owl.bookmarks.write` | 민감(쓰기) | 북마크 추가 | 검증 앱 | – | – |
| `owl.places.precise.read` | 보류 | 위경도 원값 | 발급 안 함(클라우드에 없음) | – | 위치정보사업 등록 쟁점 |
| `owl.audio.read` | 보류 | 음성 | 발급 안 함(보관 전용 구역은 서버가 복호화 불가) | – | 생체정보 |
| `owl.app_usage.read`, 대량 내보내기, 모든 삭제 | 보류 | A18 앱 사용 기록, 전체 덤프, 삭제 | 발급 안 함 | – | – |

세밀한 조건은 scope가 아니라 허가 메타데이터에 담는다. **RAR(RFC 9396, Rich Authorization Requests)**은 `authorization_details`라는 JSON 배열로 "어떤 종류, 어떤 동작, 어떤 범위"를 구조적으로 요청하는 표준이다. 사용자는 그중 일부만 허락할 수 있고, 인가 서버는 실제로 허락된 내용을 토큰 응답에 돌려준다([RFC 9396](https://datatracker.ietf.org/doc/html/rfc9396)). 심사 통과 앱은 이렇게 요청할 수 있다. `{"type":"owl_transcripts","actions":["read"],"time_range":{...}}`. 다만 Claude와 ChatGPT 커넥터는 RAR을 보내지 않는다. 그래서 AI 클라이언트의 기간 창은 사용자가 Owl 동의 화면에서 고르고 서버가 허가 메타데이터로 저장한다. 동의 화면에는 만료 선택지(1시간, 7일, 30일, 해제할 때까지)를 두되, 제한 등급은 최대 90일로 제한한다. 시간 창을 허가 단위로 주는 웨어러블 업체는 찾지 못했다. Owl이 처음 시도하는 것이므로, 검증된 관행이 아니라 설계 추론이다.

### 클라이언트 종류에 따라 보안 등급을 다르게 한다

고위험 데이터용 OAuth 프로필인 **FAPI 2.0**은 오픈뱅킹과 보건 분야에서 쓰는 강화 규격이다. 요구사항은 다음과 같다. PAR 필수, 발신자 제약 토큰만 발급, 기밀 클라이언트만 허용, 인가 코드 최대 60초, 리프레시 토큰 회전 지양([FAPI 2.0](https://openid.net/specs/fapi-security-profile-2_0-final.html)). **PAR(RFC 9126)**은 인가 요청 파라미터를 브라우저 주소창 대신 서버 간 통신으로 먼저 보내고 짧은 `request_uri`만 받는 방식이다. 그래서 요청 내용을 위조하거나 브라우저 기록으로 유출하기 어렵다([RFC 9126](https://datatracker.ietf.org/doc/html/rfc9126)). **DPoP(RFC 9449)**는 클라이언트가 요청마다 자기 개인키로 서명한 증명을 붙이는 방식이다. 토큰이 그 키에 묶이므로 토큰만 훔쳐서는 쓸 수 없다([RFC 9449](https://datatracker.ietf.org/doc/html/rfc9449)). 문제는 Claude와 ChatGPT의 문서화된 흐름에 PAR과 DPoP가 없다는 점이다. ChatGPT는 PKCE 공개 클라이언트나 `private_key_jwt`로 인증하고, OpenAI가 관리하는 mTLS 클라이언트 인증서를 제시한다([OpenAI Apps SDK](https://developers.openai.com/apps-sdk/build/auth)). 그래서 등급을 클라이언트 종류별로 나눈다.

| 클라이언트 | 등록 | 클라이언트 인증 | PAR | 토큰 바인딩 | 제한 scope |
|---|---|---|---|---|---|
| Owl 자체 Android 앱 | 사전 등록 | 공개 + PKCE | 선택 | DPoP 필수 | 예 |
| 심사 통과 서버 앱 | 심사 후 사전 등록 | `private_key_jwt` | **필수** | DPoP 또는 mTLS **필수** | 예(FAPI 2.0식) |
| 허용 목록 AI(Claude, ChatGPT) | CIMD, DCR은 대체 수단 | `none`+PKCE 또는 `private_key_jwt` | 불가 | 없음. 대신 짧은 토큰, 회전, 매 호출 서버 확인 | 예. 단 기간 창, 호출 상한, 대기 규칙 적용 |
| 알 수 없는 CIMD/DCR 클라이언트 | CIMD/DCR | `none`+PKCE | 없음 | 없음 | **아니오**(민감 등급까지, 사용자 상한) |

### 토큰 수명 기본값

RFC 9700은 공개 클라이언트의 리프레시 토큰이 "발신자 제약이거나 회전돼야 한다(MUST)"고 정한다. 회전 중에 재사용이 탐지되면 그 허가의 활성 토큰을 폐기하라고 하고, 일정 기간 쓰이지 않은 토큰은 만료하라(SHOULD)고 한다([RFC 9700](https://datatracker.ietf.org/doc/html/rfc9700)). Google의 리프레시 토큰은 6개월 동안 쓰이지 않으면 죽고, 테스트 모드에서는 7일 만에 죽는다. 계정당·클라이언트당 100개를 넘으면 가장 오래된 것이 조용히 무효화된다([Google OAuth](https://developers.google.com/identity/protocols/oauth2)). WHOOP은 리프레시 토큰을 회전시킨다. 동시에 갱신 요청이 들어오면 "먼저 도착한 요청만 성공"한다([WHOOP](https://developer.whoop.com/docs/developing/oauth)). Owl 기본값은 이 선례에 데이터 민감도를 반영해 더 짧게 잡는다.

| 항목 | 기본값 | 근거 |
|---|---|---|
| 인가 코드 | 최대 60초, 1회용 | FAPI 2.0 |
| 액세스 토큰 | 제한 등급 10–15분, 민감 등급 최대 60분. 대상(api/mcp)에 묶음 | RFC 9700, RFC 8707 |
| 토큰 검증 | 매 호출마다 "허가 폐기됨" 캐시 확인(또는 불투명 토큰 + RFC 7662 토큰 조회) | Notion: "기존 토큰은 폐기할 수 없어" 서버가 호출을 막음([Notion](https://www.notion.com/help/notion-mcp)) |
| 리프레시 토큰 | 사용할 때마다 회전. 재사용이 탐지되면 그 허가 계열 전체 폐기. 동시 갱신용 30초 유예 | RFC 9700 §4.14, FAPI Note 1, WHOOP |
| 비활성 만료 | 제한 등급 30일, 민감 등급 90일 | Google 6개월보다 짧게 |
| 절대 만료 | 사용자가 고른 허가 기간. 제한 등급은 최대 90일 후 재동의 | Google 시간 제한 허가 |
| 허가 개수 상한 | (사용자, 클라이언트)당 활성 허가 5개 | Google 100개 상한의 축소판. CIMD/DCR 재연결 누적 방지 |
| 개발 모드 | 연결 사용자 최대 25명, 빨간 "미검증" 배너, 리프레시 토큰 7일 | Google 미검증 앱 100명 상한([Google Cloud Help](https://support.google.com/cloud/answer/7454865)) |
| 코드 입력(device code) 흐름 | 제한 scope에는 금지 | Salesforce가 피싱 때문에 차단([Salesforce Admins](https://admin.salesforce.com/blog/2025/get-ready-for-changes-to-connected-app-usage-restrictions)) |

### 앱 심사는 Google의 구조를 작게 복제한다

Google의 심사는 여러 층으로 되어 있다. 브랜드 검증에 2–3 영업일이 걸린다. 제한 scope 검증은 몇 주가 걸린다. 그리고 OWASP ASVS 기반의 **CASA 제3자 보안 평가를 최소 12개월마다** 받아야 한다. 새 제한 scope를 추가하면 재평가가 필요할 수 있다([Google 제한 scope 검증](https://developers.google.com/identity/protocols/oauth2/production-readiness/restricted-scope-verification); [CASA](https://support.google.com/cloud/answer/13465431)). 업체 주장에 따르면 비용은 Tier 2가 약 540달러, Tier 3가 약 3,600–4,500달러다([Switch Labs](https://www.switchlabs.dev/post/casa-tier-2-tier-3-security-review-providers-pricing-and-the-cheapest-option)). 이 수치는 2차 출처라 확인이 필요하다. Owl은 이 구조를 축소해 쓴다. 민감 등급은 도메인과 소유권 확인, 처리방침, scope별 사유서, 시연 영상으로 심사한다. 제한 등급은 여기에 ASVS L2 질의서를 더한다. 질의서 대신 기존 CASA 확인서, SOC 2 Type II, ISO 27001을 인정할 수 있고, 매년 재확인한다. 음성은 출시 단계에서 Owl 자체 앱에만 허용한다. 좁은 scope만 쓰는 앱은 평가를 면제한다. Google도 좁은 scope 앱을 면제해 "적게 요청할 유인"을 만들었다([Project Strobe](https://blog.google/technology/safety-security/project-strobe/)).

개발자 약관의 핵심은 **Limited Use(용도 제한)** 조항이다. Google 일반 정책의 내용은 이렇다. 사용자에게 보이는 기능에만 쓴다. 광고, 판매, 데이터 브로커 전달, 신용 판단을 금지한다. 사람이 데이터를 읽는 것은 사용자의 구체적 동의나 보안·법적 사유가 있을 때만 허용한다([Google User Data Policy](https://developers.google.com/terms/api-services-user-data-policy)). Workspace 정책은 AI 시대 조항을 더한다. 해당 사용자 개인화 모델을 넘어서는 AI 학습을 금지하고, 학습용 DB 구축을 금지하며, **프롬프트 인젝션 방어를 요구**한다([Google Workspace 정책](https://developers.google.com/workspace/workspace-api-user-data-developer-policy)). Apple 5.1.2(i)는 "제3자 AI를 포함해" 공유 대상을 분명히 알리고 명시적 허락을 받으라고 한다([Apple App Review Guidelines](https://developer.apple.com/app-store/review/guidelines/)). Owl 약관에는 이 조항들에 몇 가지를 더한다. 연결 해제(`grant.revoked`)나 탈퇴(`user.deleted`) 이벤트를 받으면 원본과 파생 데이터를 7일 안에 삭제하고 API로 확인 응답을 보낸다. 캐시 보관은 최대 30일로 한다. 주변인 발언의 재식별과 인물 프로파일링을 금지한다. 사고가 나면 24시간 안에 Owl에 통지한다. 제한 scope를 쓰는 개발자에게는 배상책임보험을 요구한다. 폐기 신호는 Google RISC 방식으로 보낸다. 즉 서명된 보안 이벤트 토큰(SET)을 웹훅으로 보내고 `jti`로 중복을 제거한다([Google RISC](https://developers.google.com/identity/protocols/risc)). 이벤트 종류에는 `data.deleted`를 꼭 넣는다. 라이프로그 사용자는 부끄러운 구간을 지우고 그 구간이 모든 곳에서 사라지기를 기대하기 때문이다.

## MCP 엔드포인트: 같은 인가 서버 위의 두 번째 리소스 서버

MCP 엔드포인트는 별도 보안 체계가 아니라 같은 허가를 쓰는 또 하나의 리소스 서버다. 현행 MCP 스펙(2026-07-28)의 요구는 다음과 같다. 원격 서버는 OAuth 리소스 서버가 되어 PRM을 게시한다. 받은 토큰의 대상이 자기인지 매번 검증한다. 다른 곳에서 발급된 토큰은 받지도 넘기지도 않는다. 클라이언트 등록은 사전 등록, CIMD, DCR 순으로 권장되고, DCR은 폐지 예정이다([MCP Authorization](https://modelcontextprotocol.io/specification/latest/basic/authorization); [Client Registration](https://modelcontextprotocol.io/specification/2026-07-28/basic/authorization/client-registration)). **CIMD**는 클라이언트가 자기 정보를 HTTPS URL에 JSON으로 게시하고 그 URL을 `client_id`로 쓰는 방식이다. 등록 단계 없이도 "이 클라이언트가 누구인지"를 도메인으로 확인할 수 있다. ChatGPT는 CIMD를 선호하고 `client_id`로 `https://chatgpt.com/oauth/client.json`을 쓴다. 리다이렉트 URI는 `https://chatgpt.com/connector_platform_oauth_redirect`이고, `iss`는 메타데이터의 `issuer`와 "정규화 없이" 정확히 같아야 한다. 서버는 "매 호출마다 토큰을 검증"해야 한다([OpenAI Apps SDK](https://developers.openai.com/apps-sdk/build/auth)). Claude의 콜백은 `https://claude.ai/api/mcp/auth_callback`으로 알려져 있다. 이는 검색 요약에서 확인한 내용이라 원문 대조가 필요하다. 모든 원격 MCP 연결은 Anthropic 클라우드에서 출발한다([Claude Help](https://support.claude.com/en/articles/11503834-building-custom-connectors-via-remote-mcp-servers)). Anthropic의 외부 요청 IP 대역은 `160.79.104.0/21`과 `153.61.208.0/20`이다([Claude Platform](https://platform.claude.com/docs/en/api/ip-addresses)).

이를 정책으로 옮기면 다음과 같다. **알 수 없는 CIMD/DCR 클라이언트는 기본적으로 "미검증"으로 분류하고 민감 등급까지만 허용한다.** `owl.transcripts.read`는 CIMD URL 호스트나 정확한 리다이렉트 URI로 식별되는 작은 허용 목록에만 준다. 이는 Atlassian이 "지원 AI 도메인 기본 허용"을 두고 Notion이 "승인된 MCP 클라이언트만" 옵션을 둔 것과 같은 구조다([Atlassian](https://support.atlassian.com/security-and-access-policies/docs/control-atlassian-rovo-mcp-server-settings/); [Notion](https://www.notion.com/help/notion-mcp)). 출발 IP와 ChatGPT mTLS 인증서는 보조 신호로만 쓴다. 인증 수단으로 쓰지 않는다. MCP 스펙은 `tools/list`가 제시된 인가에 따라 달라도 된다고 허용한다. 그래서 받은 scope로 쓸 수 없는 도구는 목록에서 아예 빼 버린다. 도구는 **읽기 전용만 등록하고 쓰기와 삭제 도구는 등록하지 않는다.** Linear는 `/mcp/readonly` 엔드포인트를 따로 두고, Atlassian은 파괴적 도구를 기본으로 꺼 둔다([Linear](https://linear.app/docs/mcp)). 반면 Omi 공식 MCP는 `create_memory`와 `delete_memory`를 노출한다([Omi docs](https://docs.omi.me/doc/developer/MCP)). 인젝션에 노출된 서버에서 쓰기 도구는 데이터를 되써넣는 통로가 된다. Supabase 사례에서 RLS를 우회하는 `service_role` 키를 쥔 에이전트가 지원 티켓 속 지시에 따라 토큰을 읽고 그 값을 티켓에 다시 써 넣은 것이 그 예다([Willison](https://simonwillison.net/2025/Jul/6/supabase-mcp-lethal-trifecta/)). Anthropic 디렉터리에 등재하려면 OAuth 2.0, 처리방침, 모든 도구 주석, 테스트 계정, 대화 데이터 최소 수집이 필요하고, 중대한 변경이 있으면 재심사를 받는다([Anthropic Directory Policy](https://support.claude.com/en/articles/13145358-anthropic-software-directory-policy)).

### 인젝션은 막을 수 없으니, 한 번에 샐 수 있는 양을 줄인다

모델 쪽 방어는 보안 경계가 될 수 없다는 근거가 충분하다. OpenAI·Anthropic·Google DeepMind 연구자들의 "The Attacker Moves Second"에서 적응형 공격은 발표된 방어 대부분을 90% 넘게 뚫었다([Willison 정리](https://simonwillison.net/2025/Nov/2/new-prompt-injection-papers/)). NIST CAISI 대회에서는 25만 건 넘는 시도 끝에 모든 대상 모델이 최소 한 번은 뚫렸다([NIST](https://www.nist.gov/blogs/caisi-research-blog/insights-ai-agent-security-large-scale-red-teaming-competition)). Anthropic이 공개한 환경당 100회 시도 기준으로, Opus 4.5의 공격 성공률은 안전장치를 더한 뒤에도 1.4%였다([Anthropic](https://www.anthropic.com/research/prompt-injection-defenses)). Owl에서는 매일 들어오는 모든 발화와 모든 방송이 새로운 시도다. 그러니 1%도 높은 확률이다. 설계 목표는 "탐지"가 아니라 **"인젝션이 성공해도 몇 분 분량만 새고, 쓰기나 송신 통로는 없다"**로 잡는다. 도구는 원문 대신 서버가 계산한 답을 돌려주는 맥락 최소화 패턴을 따른다([arXiv 2506.08837](https://arxiv.org/abs/2506.08837)).

| 도구 | 필요한 scope | 돌려주는 것 | 서버가 강제하는 한도 |
|---|---|---|---|
| `count_mentions` | `owl.timeline.read` | 날짜별 일치 건수, 기록 ID | 원문 없음 |
| `place_stats` | `owl.places.coarse.read` | 장소 이름별 머문 시간, 방문 수 | 좌표 없음 |
| `list_bookmarks` | `owl.bookmarks.read` | 북마크 시각, 장소 이름, 짧은 발췌 | 건수·글자 수 상한 |
| `search_transcripts` | `owl.transcripts.read` | 일치 지점 전후 몇 초 발췌 | 응답당 글자 수, 조회 기간 상한(7일) |
| `read_transcript_excerpt` | `owl.transcripts.read` | 한 기록의 제한된 원문 | 호출당 60초, 허가당 일일 바이트 예산 |

클라우드에 서버를 두면 개인용 구조에는 없던 통제 수단이 생긴다. 바이트 예산과 속도 제한을 세션이 아니라 **허가 단위로 매일 서버가 집계**할 수 있다. 사용자 이력 전체를 훑는 접근이나 기록 ID를 순서대로 열거하는 "내보내기형" 패턴을 서버가 탐지해 멈출 수 있다. 원하는 사용자에게는 "전사 원문을 읽을 때마다 Owl 앱에서 승인" 옵션도 줄 수 있다. 이는 조사한 어떤 업체보다 강한 통제다. 다만 MCP 엘리시테이션(서버가 사용자에게 확인을 요청하는 기능)은 클라이언트가 그리는 화면이다. 그래서 승인은 반드시 Owl 앱의 푸시로 받는다. 출력 처리도 정한다. 응답은 `source: "ambient_audio_asr"` 필드를 둔 구조화 JSON으로 하고, 본문 `text`에서는 마크다운 이미지와 링크, HTML, URL 형태 문자열을 무력화한다. 마크다운 이미지 URL에 데이터를 실어 내보내는 수법이 AgentFlayer 같은 제로클릭 유출의 경로였다([Zenity Labs](https://labs.zenity.io/p/agentflayer-chatgpt-connectors-0click-attack-5b41)). STT 출력에는 원래 그런 문법이 나올 일이 없으므로 비용도 거의 없다.

AI 클라이언트에는 Owl이 손댈 수 없는 부분이 남는다. 도구 결과는 Anthropic이나 OpenAI의 대화 저장소로 들어간다. Anthropic 소비자 플랜은 학습을 허용하면 5년, 거부하면 30일 동안 대화를 보관한다([Anthropic](https://www.anthropic.com/news/updates-to-our-consumer-terms)). API 무보관(ZDR) 계약도 연결된 제3자 도구에는 적용되지 않는다([Claude Docs](https://platform.claude.com/docs/en/manage-claude/api-and-data-retention)). Owl은 이 회사들에게 개발자 약관이나 삭제 확인을 강제할 수 없다. 그러니 AI 클라이언트용 동의 화면에는 받는 법인명, 국가, 그 회사 약관상 보유 기간을 표시한다. 그리고 호출 상한과 기간 창을 주된 통제 수단으로 삼는다.

## 생태계 악용: 토큰 창고, 동의 피싱, 재판매, 스토킹

### 2025년 이후 주된 경로는 연동사의 토큰 창고다

예전 위협이 개인을 노린 피싱이었다면, 지금 주된 패턴은 **연동사 침해 한 건이 N개 고객을 동시에 노출시키는 것**이다. Drift 사건에서 공격자는 SOQL로 데이터를 대량 반출해 레코드 속 AWS 키와 비밀번호를 수확했다. 그러자 Google은 Drift Email 토큰까지 폐기했고, Salesforce는 Drift를 AppExchange에서 내렸다([GTIG](https://cloud.google.com/blog/topics/threat-intelligence/data-theft-salesforce-instances-via-salesloft-drift?hl=en)). Context.ai 사건에서는 직원 한 명이 소비자용 AI 도구에 회사 Workspace 계정을 "모두 허용"으로 연결했다. 그 도구가 침해되자 토큰 하나로 Vercel 내부까지 뚫렸다. Vercel은 악성 OAuth **클라이언트 ID를 침해 지표(IOC)로 공개**해 모든 관리자가 스스로 점검할 수 있게 했다([TechCrunch](https://techcrunch.com/2026/04/20/app-host-vercel-confirms-security-incident-says-customer-data-was-stolen-via-breach-at-context-ai/)). 이 사례가 Owl에 가장 가깝다. Owl 토큰을 많이 쥔 AI 도구 하나가 모든 사용자의 단일 실패점이 된다. 이전 사건들도 같은 각본으로 수습됐다. 2022년 Heroku·Travis CI 토큰 탈취([GitHub](https://github.blog/news-insights/company-news/security-alert-stolen-oauth-user-tokens/)), 2023년 Okta 지원 시스템에 올라온 HAR 파일 속 세션 토큰([BleepingComputer](https://www.bleepingcomputer.com/news/security/okta-breach-134-customers-exposed-in-october-support-system-hack/)) 모두 클라이언트별 일괄 폐기, 고객 통지, 침해 지표 공개, 전면 재발급으로 대응했다.

그래서 Owl이 가장 먼저 만들어야 할 운영 기능은 **킬스위치**다. 관리자가 한 번 조작하면 특정 클라이언트 ID의 모든 허가와 토큰을 폐기하고, 디렉터리에서 내리고, 해당 사용자에게 "앱 X가 침해되어 연결을 끊었다"고 알려야 한다. Google은 2017년 Docs 웜을 "약 1시간 안에" 멈췄다([Tom's Hardware](https://www.tomshardware.com/news/google-responds-mass-phishing-attack,34341.html)). 클라이언트별 조회·내보내기 로그는 제3자가 지울 수 없게 보관한다. Drift 공격자는 쿼리 작업을 지웠지만 이벤트 로그는 남아 피해 범위 산정에 쓰였다. 지원 창구에서는 토큰이 든 HAR 파일과 디버그 덤프를 받지 않고, 받더라도 자동으로 지운다.

### 동의 화면은 공격자가 입력을 넣는 곳이다

Google Docs 웜이 가능했던 이유는 누구나 OAuth 앱 이름을 "Google Docs"로 지을 수 있었기 때문이다([Proofpoint](https://www.proofpoint.com/de/node/24991)). 2022년에는 Microsoft "인증 게시자" 배지조차 위조 파트너 계정으로 얻어 냈다([Proofpoint](https://www.proofpoint.com/us/blog/cloud-security/dangerous-consequences-threat-actors-abusing-microsofts-verified-publisher)). 그래서 Owl 동의 화면에서는 미검증 앱의 표시 이름을 크게 보여 주지 않는다. 대신 검증된 법인명, 도메인, 요청 scope를 앞세운다. 배지는 공격 비용을 높일 뿐 신뢰 경계가 아니다. UNC6040은 전화로 "IT 지원"을 사칭해 연결 승인과 코드 입력을 유도했다([GTIG](https://cloud.google.com/blog/topics/threat-intelligence/voice-phishing-data-extortion)). 그 뒤 Salesforce는 코드 입력(device) 흐름을 따로 막았다. Microsoft는 2025년 파일·사이트 scope에 대한 사용자 동의를 기본으로 꺼 버렸다([MC1097272](https://mc.merill.net/message/MC1097272/v/2025-06-18T06-14-42-383Z)). 소비자 제품인 Owl에는 "관리자 동의"가 없다. 그 대신 아래의 대기 시간과 알림이 그 역할을 맡는다.

### 재판매와 과수집은 약관, 감사, 기술 차단을 함께 쓴다

가장 큰 개인정보 피해는 침입자보다 권한을 정상적으로 받은 앱의 2차 이용에서 나왔다. Return Path와 Edison 직원들의 메일 열람은 "이용약관상 허용"됐었다([Engadget](https://www.engadget.com/2018-07-03-third-party-app-developers-reading-gmail.html)). Google은 그 뒤 제한 scope, 의무 심사, 연례 평가를 도입했다([Project Strobe](https://blog.google/technology/safety-security/project-strobe/)). FTC의 위치 브로커 명령들은 민감 장소 목록 관리와 그 장소 방문 정보의 공개 차단을 요구한다([FTC](https://www.ftc.gov/news-events/news/press-releases/2025/01/ftc-finalizes-order-prohibiting-gravy-analytics-venntel-selling-sensitive-location-data)). Kochava는 2026년 5월 명시적 동의 없는 정밀 위치 판매 금지에 합의했다([Proskauer](https://newmedialaw.proskauer.com/2026/06/03/ftc-remains-on-the-location-data-beat-agency-settles-long-running-kochava-enforcement-even-as-state-regulation-has-already-changed-the-landscape/)). Flo 사건에서는 API가 아니라 **앱에 심긴 SDK**가 유출 경로였고, 배심원은 Meta에 책임이 있다고 평결했다([National Law Review](https://natlawreview.com/article/federal-jury-finds-against-meta-collecting-data-flo-health)). Owl은 이 규제 패턴을 개발자 정책 틀로 그대로 가져온다. 병원, 종교시설 같은 민감 장소는 API로 전달하기 전에 지오펜스로 제외하고, 받는 앱 안에서 광고 SDK와 픽셀로 재전송하는 것을 금지한다. 비용이 가장 싼 감사 수단도 있다. Facebook은 정보 요청에 응답하지 않은 개발자 약 400곳의 앱 수만 개를 정지했다([Axios](https://www.axios.com/2019/09/20/facebook-suspend-thousands-apps-developer-probe)). 집계도 안전한 기본값이 아니다. Strava의 "익명" 히트맵은 군사 기지의 윤곽을 드러냈다([Help Net Security](https://www.helpnetsecurity.com/2018/01/29/strava-user-heatmap-reveals-patterns-of-life-in-western-military-bases/)). 그러므로 여러 사용자를 합친 집계 API는 만들지 않는다. 데이터가 하위 보유자에게 넘어가면 영장 없는 접근의 쉬운 표적이 된다. Fog Data Science는 앱에서 나온 위치 데이터를 영장 없이 경찰에 팔았다([EFF](https://www.eff.org/deeplinks/2022/08/fog-data-science-puts-our-fourth-amendment-rights-sale)). 그래서 제한 scope 약관에는 법적 요구를 받으면 이용자에게 통지하라는 의무도 넣는다.

### 스토커는 피해자의 폰을 잠깐 손에 쥔 사람이다

가장 현실적인 악용 시나리오는 이렇다. 연인이나 가족이 피해자의 잠금 해제된 폰을 몇 분 쥐고, 자기가 통제하는 앱에 피해자의 Owl을 연결한다. 그 뒤 몇 달 동안 피해자의 전사와 장소를 원격으로 읽는다. Chatterjee 등은 이런 용도로 쓸 수 있는 앱 61개를 앱스토어에서 찾았고, 접촉한 앱 지원팀 11곳 중 8곳이 파트너 감시를 사실상 용인하는 답을 했다([Chatterjee et al.](https://www.cs.cornell.edu/~rahul/papers/IPV_Spyware.pdf)). Kaspersky 집계로 2023년 스토커웨어 피해 모바일 사용자는 31,031명이었다. 이는 자사 고객만 센 하한선이다([Kaspersky](https://go.kaspersky.com/rs/802-IJN-240/images/The-State-of-Stalkerware-in-2023-final-5-03-24.pdf?version=0)). 참고할 선례는 두 가지다. Apple Safety Check는 사람과 앱에 준 접근을 한 번에 검토하고 끊는 기능으로, 가정폭력 대응 단체와 함께 설계됐다([Apple](https://support.apple.com/en-nz/guide/personal-safety/ips5f85e089f/web)). Apple–Google 원치 않는 추적기 경고는 소유자가 아니라 **추적당하는 사람에게** 알린다([Apple Newsroom](https://apple.com/newsroom/2024/05/apple-and-google-deliver-support-for-unwanted-tracking-alerts-in-ios-and-android)).

Owl용 설계는 이 선례들을 결합한 추론이다. 라이프로그 OAuth 악용을 직접 다룬 연구는 찾지 못했다. 설계는 다섯 가지다. 첫째, **제한 scope는 대기 시간을 둔다.** 기간 깊이가 최근 7일 이하이고 절대 만료가 7일 이하인 허가는 바로 활성화한다. 그보다 긴 허가와 `owl.history`는 24시간을 기다린 뒤 활성화하고, 그동안 여러 번 알린다. 폰을 쥔 사람은 생체 인증도 통과할 수 있으므로, 단계 상향 인증만으로는 막을 수 없고 시간이 필요하다. 둘째, **새 허가는 모든 채널로 알린다.** 푸시, 이메일, 그리고 펜던트 LED 패턴이다. 펜던트 LED를 쓰려면 BLE 프로토콜에 새 이벤트나 명령이 필요하다. 이 경우 `protocol/ble-protocol.md`, `tools/owlproto.py`와 테스트, 펌웨어, 앱을 한 커밋에서 함께 바꿔야 한다. 셋째, "앱 X가 30일째 전사에 접근 중" 같은 정기 알림과 90일 재동의를 둔다. 넷째, **"모두 끊기" 안전 점검**을 둔다. 한 번 누르면 모든 제3자 허가와 세션을 폐기하고 자격 증명을 갱신한다. 이 기능은 침해됐을 수 있는 폰이 아니어도 웹에서 실행할 수 있어야 한다. 다섯째, 다른 사람을 감시하는 용도를 개발자 정책에서 금지하고, 악용 신고 창구를 둔다.

## 한국법: 커넥터 연결 한 번은 동의 세 건이다

여러 사람에게 서비스를 제공하는 순간 Owl 운영자는 "업무를 목적으로" 개인정보를 처리하는 **개인정보처리자**가 된다. 무료라도 계속하고 반복할 의사가 있으면 업무로 본다([nepla, 개인정보위 Q&A](https://www.nepla.ai/wiki/it-정보-방송통신/개인정보-위치정보-신용정보/-일문일답-친구가-사적으로-주고-받은-메신저-내용을-내-동의-없이-다른-사람들에게-보여주고-다니는데-개인정보보호법-위반인가-jxm9lry0j9pg)). 커넥터에서 가장 중요한 판단은 이용자가 고른 앱에 데이터를 넘기는 행위의 성격이다. 받는 앱은 **자기 목적과 자기 책임으로** 데이터를 쓴다. 공공 안내자료는 위탁과 제공을 "누구의 이익을 위한 이전인가"로 가른다([서울시교육청 안내자료](https://sen.go.kr/resources/www/data/infoprotect_3_03.pdf)). 그러므로 이 행위는 처리위탁(제26조)이 아니라 **제3자 제공(제17조)**으로 보는 것이 맞아 보인다. 법제처는 제3자가 개인정보를 **열람하게만 해도** 제공에 해당한다고 해석했다([nepla, 법제처 해석례](https://www.nepla.ai/wiki/it-정보-방송통신/개인정보-위치정보-신용정보/-유권해석-개인정보-보호법-제18조-개인정보의-목적-외-이용-제공-제한/-법제처-유권해석-제3자에게-개인정보를-열람하게-하는-것이-제3자에의-개인정보-제공에-해당하는지-여부-등-개인정보-보호법-제18조제2항-등-관련-jxm9lr3729pg)). 이 해석에 따르면 API나 MCP로 조회만 허용해도 제공이다. 반대 해석도 있다. 이용자가 자기 Claude 계정에 Owl을 직접 붙이는 것은 "정보주체가 자기 정보를 가져가는 것"이라는 논리다. 그러나 이용자가 OAuth로 직접 승인한 연동에 대한 개인정보위 해석은 찾지 못했다. **이것이 가장 중요한 미확인 쟁점**이므로 보수적인 쪽을 기본값으로 삼는다.

| 법·조항 | Owl에 생기는 의무 | 설계 반영 | 확신도 |
|---|---|---|---|
| PIPA 제17조 제3자 제공 | 커넥터마다 받는 자, 목적, 항목, 보유 기간, 거부권을 고지하고 동의. 수집·이용 동의와 분리 | OAuth 동의 화면 = 법정 동의 화면. 받는 자 법인명은 개발자 등록 정보에서 | 해석 미확인([korea.legal](https://korea.legal/wiki/%ea%b0%9c%ec%9d%b8%ec%a0%95%eb%b3%b4-%eb%b3%b4%ed%98%b8%eb%b2%95-%ec%a0%9c17%ec%a1%b0/)) |
| PIPA 제28조의8 국외이전 | 해외 AI 커넥터는 "국외 제공"이므로 별도 동의. 고지 항목은 이전 항목, 국가·시기·방법, 받는 자, 목적·보유 기간, 거부 방법·효과. Owl 자체 클라우드와 LLM API는 국외 위탁·보관이므로 처리방침 공개로 처리 | 같은 화면에서 제17조 동의와 구분해 함께 받음. 미국은 동등성 인정 대상이 아님(EU는 2025-09 인정) | 조문 2차 DB 확인([IT위키](https://itwiki.kr/w/%EA%B0%9C%EC%9D%B8%EC%A0%95%EB%B3%B4_%EB%B3%B4%ED%98%B8%EB%B2%95_%EC%A0%9C28%EC%A1%B0%EC%9D%988); [법률신문](https://www.lawtimes.co.kr/news/articleViewAmp.html?idxno=211803)) |
| PIPA 제23조와 시행령 제18조 제3호 민감정보 | 화자 확인용 음성 특징정보는 민감정보이므로 별도 동의 | 본인 임베딩은 폰에만. 주변인 임베딩은 만들지 않음 | 가이드라인 2차([법률신문](https://www.lawtimes.co.kr/news/articleView.html?idxno=172836)) |
| 안전성 확보조치 기준 | 민감정보를 처리하는 시스템은 접속기록 **2년** 보관과 **월 1회** 점검. 권한 기록 3년. 외부 접속은 안전한 인증수단. 생체인식정보 저장 시 암호화 | API·MCP 접근 로그도 2년 보관하고 월 1회 이상징후 점검 | 고시 전문 2차([nepla](https://www.nepla.ai/wiki/it-정보-방송통신/개인정보-위치정보-신용정보/-고시-개인정보의-안전성-확보조치-기준-시행-2023-9-22-개인정보보호위원회-고시-제2023-6호-2023-9-22-일부개정-2w0ndr2py9j7)) |
| 유출 신고·통지 | 1천 명 이상, 민감정보, 외부 불법 접근이면 인원과 관계없이 72시간 안에 신고. 2026-09-11부터는 **유출 가능성만으로도** 72시간 안에 통지. 과징금 상한은 일정 사유에서 매출 10% | 토큰 탈취가 의심되면 영향 범위(사용자×scope)를 자동 산정. 개발자 통지 기한 24시간 | 2차([nepla](https://www.nepla.ai/wiki/it-정보-방송통신/개인정보-위치정보-신용정보/개인정보-유출-등의-통지-신고/개인정보-유출-등의-신고-의무-mo59ye45n1vj); [바이라인](https://byline.network/2026/09/10-705/)) |
| 위치정보법 제5조·제9조 | 폰 GPS를 서버에 모으면 개인위치정보사업 **등록(법인만)** 대상일 위험. 위치기반서비스는 신고. 소상공인·1인 창조기업은 개시 후 1개월 안 신고 특례 | 정밀 좌표를 올리지 않아도 장소 이름이 특정인의 위치면 여전히 개인위치정보 | 원문 DB([nepla 위치정보법](https://www.nepla.ai/law/위치정보의%20보호%20및%20이용%20등에%20관한%20법률)). 등록 여부 판단은 미확인 |
| 위치정보법 제19조·제16조·제24조 | 이용자가 지정한 제3자 제공은 받는 자와 목적을 고지하고 동의. **매회 즉시 통보**(동의하면 30일 이내 묶음 통보). 사실확인자료 자동 기록. 일시 중지 요구는 거부 불가 | 장소가 든 응답마다 제공 이벤트 기록, 30일 묶음 리포트, 커넥터별 일시 중지 스위치 | 원문 DB(같은 출처) |
| 통신비밀보호법 제3조·제16조 | 비당사자 녹음은 위법(2020도1007). 그 내용의 공개·누설도 별도 처벌 | **비당사자 구간은 기기에서 폐기하고 클라우드에 올리지 않음** | 판례 확인([법률신문](https://www.lawtimes.co.kr/news/articleView.html?idxno=182425)). 플랫폼 책임은 판례 없음 |
| 전기통신사업법 제22조 | 부가통신사업 신고 대상일 가능성 높음 | 법인 설립 시 함께 처리 | 2차([아이뉴스24](https://www.inews24.com/view/1313798)) |
| AI 기본법(2026-01-22 시행) | Owl이 직접 생성형 AI로 요약을 만들면 사전 고지와 결과물 표시. 시정명령 미이행 시 최대 3천만 원 | 커넥터 자체는 해당 가능성 낮음. A12 하루 요약에는 적용 | 2차([신김](https://shinkim.com/kor/media/newsletter/3114)) |
| ISMS·ISMS-P, CPO | 정보통신서비스 매출 100억 원 또는 일평균 100만 명이면 ISMS 의무([KISA](https://www.kisa.or.kr/1050602)). 민감정보 5만 명 이상이면 이사회 의결로 CPO 지정. 중요 개인정보처리자 ISMS-P 의무는 2027-07-01부터 | 출시 시에는 의무 아님. 로드맵 기준점으로 기록 | 2차([바이라인](https://byline.network/2026/09/10-705/)) |

표에서 가장 무거운 것은 통신비밀보호법이다. 대법원 2020도1007은 "가청거리 내"였다는 사정만으로 타인 간 대화를 녹음하는 것이 허용되지 않는다고 봤다. 그 사건 피고인은 녹음을 제3자에게 보내 유죄가 확정됐다([법률신문](https://www.lawtimes.co.kr/news/articleView.html?idxno=182425)). 제16조 제1항은 위법 녹음(제1호)과 그 내용의 공개·누설(제2호)을 따로 처벌하고, 제1호에는 벌금형이 없다([korea.legal 제16조](https://korea.legal/wiki/%ed%86%b5%ec%8b%a0%eb%b9%84%eb%b0%80%eb%b3%b4%ed%98%b8%eb%b2%95-%ec%a0%9c16%ec%a1%b0/)). 개인용 구조에서는 사용자 한 명의 행위가 문제였다. 클라우드에서는 **운영자 서버가 그 내용을 받아 저장하고 제3자 앱에 넘기는 행위자**가 된다. 그래서 방조나 제2호 누설의 주체로 평가될 여지가 생긴다. "주변 대화까지 자동으로"를 기능으로 광고하면 고의 판단에도 불리하다. 녹음기 제조사나 플랫폼의 책임을 다룬 판례는 찾지 못했다. AI 통화녹음 서비스에 대해서도 통비법 판단은 결론이 나지 않았다는 보도가 있다([다음/원문 기사](https://v.daum.net/v/20251208130138923)). 그러므로 당사자 판정과 비당사자 폐기는 선택 기능이 아니라 **클라우드 출시의 법적 전제 조건**이다. 형사 전문 변호사의 의견은 코드보다 먼저 받아야 한다. 당사자 대화 속 상대방 발언도 그 사람의 개인정보다. 주변인에게는 동의를 받을 방법이 없으므로, 제3자 앱에는 가명 처리와 요약 단위를 기본값으로 준다. 처리방침에는 비이용자를 위한 열람·삭제 요청 창구를 둔다.

몇 가지 부수 쟁점도 정리해 둔다. 개인정보 전송요구권(제35조의2)의 전송 의무는 시행령이 지정한 분야(보건의료, 통신, 에너지 등)의 정보전송자에게만 있다. 그래서 라이프로그 사업자인 Owl에는 법적 전송 의무가 없을 가능성이 높다([신김](https://www.shinkim.com/kor/media/newsletter/3377)). 그래도 기계가 읽을 수 있는 JSON 내보내기(A13)를 유지하면 열람과 사본 요구에 대응할 수 있다. 마이데이터 고시 제정안은 API 방식 권장, 안전한 암호 알고리즘, 전문기관의 10억 원 이상 보험을 요구한다([보안뉴스](https://m.boannews.com/html/detail.html?idx=133248)). 이는 Owl 개발자 심사 기준의 국내 벤치마크로 쓸 수 있다. 14세 미만은 개인정보보호법과 위치정보법 모두 법정대리인 동의가 필요하다. 상시 녹음 기기라는 특성상 가입 단계에서 연령으로 막는 것이 가장 단순하다. 마지막으로 CLAUDE.md의 앱 문구 규칙("설명 카드나 깨알 안내 금지")과의 관계다. 위 고지 항목들은 "행동에 필요한 권한·동의 문구"이므로 동의 화면에 들어간다. 반면 그 밖의 규칙과 한계는 `docs/prd.md`와 `docs/decisions.md`에 적는다.

## PRD와 decisions.md에서 바꿔야 할 곳

CLAUDE.md는 PRD를 기준으로 삼고, 문서와 코드가 어긋나면 같은 커밋에서 한쪽을 고치라고 한다. 클라우드 커넥터를 만든다면 아래 변경이 코드보다 먼저 들어가야 한다. 이 보고서는 해당 파일들을 수정하지 않았다. 앞선 MCP 조사는 새 기능 ID로 A20("LLM 연결")을 제안했지만 PRD에는 반영되지 않았다. 아래 번호는 그 제안이 반영되지 않았다는 전제로 붙였다.

| 문서·위치 | 현재 | 바꿀 내용 |
|---|---|---|
| PRD 1장 한 줄 정의, 2장 배경 | "개인용 기록 목걸이", "기록은 내 폰에 남는 것", 클라우드 구독 의존을 경쟁 제품의 문제로 지적 | 기본값은 여전히 "내 폰"이고 클라우드는 데이터 종류별 선택이라는 문장 추가. 차별점을 "통제 가능한 클라우드"로 다시 쓸지 결정 |
| PRD 3장 사용자 | 1차 사용자 제작자 1명. "다른 사람이 쓰는 제품은 v1 범위가 아니에요" | 다수 사용자를 대상으로 하는 버전(v2) 정의, 연령 제한(14세 미만 가입 불가) |
| **PRD N8** | 기기와 폰에만, 외부 전송 없음, 예외는 A13 | 예시 문구: "음성은 기기와 내 폰에만 둔다(클라우드에는 보관 전용 암호화 사본만, 사용자가 켠 경우). 텍스트·장소 이름·북마크는 사용자가 종류별로 켠 경우에만, 본인 참여로 판정된 구간만 Owl 클라우드에 암호화 저장한다. 외부 제공은 사용자가 받는 자·항목·기간을 보고 허락한 앱과 AI에 한하며 언제든 철회할 수 있다. 정밀 좌표와 앱 사용 기록(A18)은 폰 밖으로 내보내지 않는다." 스플래시와 설정 › 개인정보 처리 방침 문구도 함께 수정 |
| PRD 6장 비기능 | N1–N9 | N10 클라우드 보안(사용자별·종류별 키, RLS, 교차 사용자 테스트), N11 보관(서버 90일 이동 창, 백업 35일), N12 접근 기록(2년, 사용자에게 공개) 추가 |
| PRD 5.2 기능 | A1–A19 | A20 계정·로그인(패스키), A21 클라우드 동기화(종류별 켜기), A22 연결된 앱·안전 점검(접근 기록, 모두 끊기, 30일 알림, 위치 제공 통보), A23 MCP 커넥터(읽기 전용, 허용 AI), A24 개발자 API·포털, A25 본인 발화 판정(화자 확인, 비당사자 폐기). A13은 기계 판독 JSON을 유지하고 계정 삭제 시 전체 사본 제공 명시 |
| **PRD 9장 비목표** | "여러 사용자, 계정, 클라우드 동기화", "화자 구분(P2 이후 검토)" | 두 항목 삭제. 대신 "정밀 좌표·음성·앱 사용 기록의 제3자 제공", "공유 링크", "여러 사용자 집계 API", "제3자 쓰기·삭제 scope", "코드 입력 연결 흐름"을 비목표로 추가. 화자 구분은 비목표에서 클라우드의 전제 조건(A25, P0)으로 이동 |
| PRD 7장 시스템 구성, 8장 버전 | 기기–폰만 있는 그림. v1 정의 | 폰–클라우드–AS–API/MCP 구성 추가. v1은 오프라인으로 유지하고 클라우드는 v2로 정의 |
| PRD 10장 미결정 | C, D, H | 암호화 모델(2 vs 3·4), 당사자 판정 방식, AS 제품 선택, 서버 보관 기본값, 위치정보사업 등록·신고, OAuth 연동의 법적 성격 추가 |
| PRD 11장 위험 | 타인 대화 녹음 | 운영자 서버의 비당사자 내용 수신·제공(통비법 제16조), 연동사 토큰 탈취, 동의 피싱, 스토킹, 주변 음성 인젝션, 수사기관 요구, 인수 시 약속 붕괴 추가 |
| decisions.md #10 | 클라우드 STT 없음 | **유지**. 클라우드는 폰이 만든 텍스트만 받는다는 근거로 인용 |
| decisions.md #22 | Android 자동 백업 끔 | **유지**. Owl 클라우드는 OS 백업이 아니라 명시적 종류별 동기화라는 점을 적고, 둘을 구분 |
| decisions.md #32 | 네트워크 지오코딩 금지 | **유지**. 클라우드에는 사용자가 붙인 이름과 거친 위치만 |
| decisions.md #11, #53 | 음성 보관 정책, 앱 사용 기록 | 음성과 A18은 동기화 대상에서 빼거나 보관 전용으로만 둔다는 결정을 연결 |
| decisions.md #54 이후(신규) | 마지막이 #53 | 클라우드 암호화 모델, 동기화 범위 기본값, 당사자 판정, AS 선정과 RFC 9700/FAPI 프로필, scope 목록, 토큰 기본값, 클라이언트 등급과 허용 AI 목록, MCP 도구 표면과 한도, 동의 화면 = 법정 동의, 스토킹 대응(대기·알림·안전 점검), 킬스위치·IOC 절차, 보관과 투명성 보고, 개발자 약관 |
| roadmap.md, architecture.md | M0–M7 | M8 이후 클라우드 마일스톤과 구성도 추가. 펜던트 LED 알림을 채택하면 BLE 프로토콜 변경을 한 커밋으로 묶음 |

## 단계별 로드맵: 출시 전 필수와 그 이후

순서를 정하는 원칙은 하나다. **되돌리기 어려운 것부터 한다.** 법적 판단과 저장 범위는 데이터가 쌓인 뒤에는 바꾸기 어렵다. 반대로 FAPI 프로필이나 TEE는 나중에 더해도 된다. 기존 앱에는 그전에 처리해야 할 일이 있다. 현재 `OwlDatabase`는 평문 Room으로 열린다. 그래서 클라우드 작업보다 먼저 SQLCipher와 Keystore로 폰 저장소를 암호화해야 한다([CommonsWare](https://commonsware.com/Room/pages/chap-sqlcipher-003)).

| 단계 | 범위 | 다음 단계로 넘어가는 조건 |
|---|---|---|
| 0. 결정과 법률 | PRD·decisions 개정. 법인 설립, 부가통신 신고. 형사·개인정보·위치정보 법률 의견(통비법상 플랫폼 책임, 위치정보사업 등록 여부, OAuth 연동이 제3자 제공인지 정보주체 본인 이용인지) | 의견서가 "비당사자 폐기 + 동의 구조"로 위험을 관리할 수 있다고 판단 |
| 1. 폰 쪽 전제 | Room 암호화. 본인 화자 등록(민감정보 별도 동의)과 비당사자 폐기. 개인정보 마스킹. Owl 자체 한국어 데이터로 판정 정확도 측정 | 비당사자 구간 누출률 목표치 충족 |
| 2. 커넥터 없는 동기화(비공개 베타) | 패스키 계정, 종류별 동기화, 모델 2 암호화, RLS와 교차 사용자 CI 테스트, 비밀 관리, 접속기록 2년과 월간 점검, 72시간 유출 대응 절차, 90일 보관, 투명성 페이지, 외부 침투 시험 | 침투 시험의 높은 위험 항목 0건 |
| 3. MCP 폐쇄 베타 | 인가 서버(PRM, CIMD, PKCE, `iss`), 기본·민감 scope와 허용 AI에 한한 `owl.transcripts.read`. 동의 화면 = 제17조·제28조의8·위치정보법 동의. 연결된 앱 화면, 킬스위치, 안전 점검, 대기 규칙, 위치 제공 통보, 읽기 전용 도구와 허가별 예산 | 킬스위치 훈련(1시간 안에 클라이언트 전체 폐기와 통지) |
| 4. 일반 개발자 개방 | 개발자 포털, 심사 등급, Limited Use 약관, 제한 scope에 FAPI 2.0식 PAR·DPoP, RAR 기간 창, 폐기·삭제 웹훅과 확인, 연례 재확인, 버그 바운티와 `security.txt` | 첫 제한 scope 앱의 심사 완료 |
| 5. 규모에 따른 의무 | 민감정보 5만 명이면 CPO, 중요 개인정보처리자 기준 도달 시 ISMS-P. 운영자 열람을 없애는 모델 3·4 검토. 반기 투명성 보고 | – |

출시 전 필수 목록을 줄이면 다음과 같다. 법률 의견, 비당사자 폐기, 음성과 정밀 좌표 미업로드, 사용자별 키와 RLS, 교차 사용자 테스트, 비밀 스캔, 2년 접근 기록, 72시간 대응 절차, 침투 시험, 킬스위치, 연결 알림, 모두 끊기, 동의 화면의 법정 고지, 위치 제공 통보와 일시 중지다. 제3자 앱 개방 뒤로 미뤄도 되는 것은 FAPI 프로필, RAR, 개발자 보험 요건, 연례 평가, TEE다. 이 순서에는 반대 논리도 있다. MCP를 먼저 열어야 사용자 가치가 빨리 보인다는 주장이다. 그러나 Omi 사례가 그 비용을 보여준다. 보안 결함이 공개된 뒤 수정까지 103일이 걸렸고, 그동안 모든 사용자가 노출 상태였다.

## 결론

이번 조사로 바뀐 관점은 이렇다. Owl 클라우드의 보안 경계는 서버의 벽이 아니라 **발급된 허가의 집합**이다. 공개된 사고의 대부분은 정상적으로 발급된 토큰, 정상적으로 받은 동의, 정상적으로 열린 API에서 일어났다. 그래서 설계의 중심을 암호화 강도에서 허가의 수명, 깊이, 개수, 가시성으로 옮겨야 한다. 침해 피해, 수사기관 요구, 인수 후 약속 붕괴, 통비법 책임이라는 네 가지 서로 다른 위험은 같은 처방으로 모인다. **처음부터 서버에 두지 않는 것**이다. 비당사자 폐기와 음성·정밀 좌표 미업로드는 프라이버시 기능인 동시에, 앞의 네 위험을 한꺼번에 줄이는 가장 값싼 보안 통제다. 화자 구분은 앞선 조사에서 편의 기능에서 법적 전제로 올라갔다. 클라우드에서는 한 단계 더 올라가 플랫폼 전체가 기대는 토대가 된다.

남은 불확실성은 세 가지이고, 모두 코드를 짜기 전에 풀어야 한다. 첫째, 이용자가 직접 승인한 OAuth 연동이 법적으로 제3자 제공인지 정보주체 본인의 이용인지, 그리고 운영자가 비당사자 내용을 받는 행위가 통비법상 어떻게 평가되는지다. 둘째, 한국어 구어 환경에서 본인 발화 판정이 비당사자 구간을 얼마나 놓치는지다. 셋째, Claude와 ChatGPT 커넥터가 짧은 액세스 토큰과 리프레시 토큰 회전을 문제없이 견디는지, 그리고 DPoP를 지원하는지다. 첫째 답이 불리하게 나오면, 앞선 조사가 권한 "폰에서 고른 기간만 내보낸 스냅샷 + PC stdio 서버" 구조와 로컬 LLM 경로가 여전히 N8을 지키는 유일한 대안이다.
