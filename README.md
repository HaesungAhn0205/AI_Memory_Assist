
## 🚛 AI Remembers You

### 🛠 개발 배경

<table align="center" border="0">
  <tr>
    <td align="center">
      <img src="https://github.com/user-attachments/assets/9aaacc9e-24b9-4c94-b385-754cd48cf9ab" width="450" />
      <br>
      <b>〈약 복용을 까먹거나 과 복용하는 사례〉</b>
    </td>
  <td align="center">
    <img src="https://github.com/user-attachments/assets/dc6c195c-be25-43fd-8569-e698e03f2509" width="450" />
      <br>
      <b>〈창문을 제대로 닫지 않아 침입 범죄 발생 통계〉</b>
    </td>
  </tr>
</table>

중앙치매센터(증가하는 초기 치매 환자 통계), 보건복지부(치매 시장 확장 전망) 통계와 약 오용 문제 사례 등의 조사를 통해, 저희는 일상 데이터를 기반으로 한 개인화 서비스의 중요성을 실감했습니다.
사용자가 리마인더와 알람 등으로 수동으로 기록해야 했던 기존의 치매 대상 서비스들의 번거로움과 한계를 극복하고자, **LLM 기반 문맥 인지 기술과 Vector DB(또는 RAG 기술)** 를 결합한 **AI 일상 기억 및 루틴 파악 시스템: ARU**을 개발했습니다. 실시간 일상 맥락 분석을 통해 사용자의 핵심 행동 패턴을 파악하고 장기 기억(Long-term Memory)화합니다. 그리고 이를 기반으로 맞춤형 루틴 추천 및 피드백을 제공하여 일상적 건망증 또는 초기 치매로 인한 일상생활 저하을 보조합니다. 부가적으로 사용자의 패턴에 맞지 않게 위치한 물체를 제자리로 옮겨주는 기능 또한 제공합니다.

### 📝 한 줄 요약
[적어주세요]

---

## 📅 프로젝트 개요
- **프로젝트 명:** ARU (AI Remembers U)
- **수행 기간:** 2026.06.03 ~ 2026.06.15
- **주요 기능**
  - **1:** LLM 기반 챗봇으로 음성 및 텍스트로 대화하며, 물건들의 위치, 사용자의 행동 정보를 확인 가능
  - **2:** 홈캠과 멀티모델 LLM을 통해 시각 데이터를 텍스트로 저장하고, RAG시스템으로 알맞은 정보를 제공
  - **3:** 과거 데이터를 분석하여 자동으로 루틴을 추천하며, 밸소리와 진동을 포함한 알람 제공
  - **4:** 루틴의 목표 행동을 감지하면 알람을 울리지 않아 선택적 알람 제공
  - **5:** 사용자의 물건들을 로봇팔을 이용하여 지정된 위치로 정리함
  - **6:** 고령의 사용자에게 초점을 둔 UI/UX로 손쉬운 사용이 가능한 앱 제공

---

## 🎬 시연 영상
<table align="center">
  <tr>
    <td align="center"><b>ARU 애플리케이션 시연</b></td>
  </tr>
  <tr>
    <td>
      <a href="https://www.youtube.com/watch?v=s1lU2BHdtIo">
        <img src="https://img.youtube.com/vi/s1lU2BHdtIo/0.jpg" width="900px">
      </a>
    </td>
  </tr>
</table>

<hr>

<table align="center">
  <tr>
    <td align="center"><b>ARU 로봇팔 시연</b></td>
  </tr>
  <tr>
    <td>
      <a href="https://youtu.be/66-pFlFDmbc?si=yLgezzZP7GBSh6kA">
        <img src="https://img.youtube.com/vi/66-pflFDmbc/hqdefault.jpg" width="900px">
      </a>
    </td>
  </tr>
</table>

<hr>

---

## 🛠 기술 스택 & 아키텍처
<p align="center">
  <img src="./images/img_arch.png" width="90%" alt="Architecture" />
</p>

---

## 📂 디렉토리 구조

. <br>
├── 📂 **[backend/](./backend)** <br>
│&nbsp;&nbsp;&nbsp;└── 📄 amr_turtle_db_bakcup.sql <br>
├── 📂 **[images/](./images)** <br>
│&nbsp;&nbsp;&nbsp;└── 🖼 <br>
├── 📂 **[motion_control/](./motion_control)** <br>
│&nbsp;&nbsp;&nbsp;├── 📂 **[actuator/](./motion_control/actuator)** <br>
│&nbsp;&nbsp;&nbsp;│&nbsp;&nbsp;&nbsp;└── 📄 actuator.ino <br>
│&nbsp;&nbsp;&nbsp;└── 📂 **[conveyor_belt/](./motion_control/conveyor_belt)** <br>
│&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;└── 📄 conveyor_belt.ino <br>
├── 📂 **[qt_app/AFC_qt/](./qt_app/AFC_qt)** <br>
│&nbsp;&nbsp;&nbsp;├── 📁 .qtcreator/ <br>
│&nbsp;&nbsp;&nbsp;├── 📂 **[src/](./qt_app/AFC_qt/src)** <br>
│&nbsp;&nbsp;&nbsp;├── 📄 CMakeLists.txt <br>
│&nbsp;&nbsp;&nbsp;├── 📄 LICENSE <br>
│&nbsp;&nbsp;&nbsp;└── 📄 package.xml <br>
├── 📂 **[robot/](./robot)** <br>
│&nbsp;&nbsp;&nbsp;└── 📄 agv_move_pub.py <br>
└── 📄 [README.md](./README.md)

---

## 🔍 상세 기능 설명
**1. REST API SERVER**
<p align="left">
  <img src="./images/img_api.png" width="60%" alt="Architecture" />
</p>
<table>
  <tr>
    <td align="center"><b>API</b></td>
    <td align="center"><b>기능</b></td>
  </tr>
  <tr>
    <td>
      auth/login
    </td>
    <td>
      • 기본적인 로그인 기능
    </td>
  </tr>
  <tr>
    <td>
      event/<br/>edge/
    </td>
    <td>
      • 사물이 사라지거나, 특정 이벤트가 발생 관련 이벤트<br/>
      • 수동 데이터 삽입, 이미지 비교 분석 추론 후 데이터 삽입, 현재 탐지 객체 목록 리스트 반환 구현
    </td>
  </tr>
  <tr>
    <td>
      chat/query
    </td>
    <td>
      • 챗 봇 기능 API로서 사용자의 질문 내용을 분석<br/>
      • 과거, 현재, 논외 대화를 구분(intent)하는 알고리즘 진행<br/>
      • intent 값에 따라 실행 코드 로직 구성
    </td>
  </tr>
  <tr>
    <td>
      routine/
    </td>
    <td>
      • 사용자의 생활 루틴 관련 이벤트 API
    </td>
  </tr>
</table>

<br/>

**2. 녹화 영상 저장 기능**
<p align="center">
  <img src="./images/img_vod.png" width="90%" alt="cctv" />
</p>

* **1분 단위로 저장 됨**
* **3일 이상이 지난 영상 데이터는 자동 삭제**

**3. 시스템 프롬프트 롤 설정**
<table align="center">
  <tr>
    <td><img src="./images/img_prom1.png" width="300px" alt="prompt1"></td>
    <td><img src="./images/img_prom2.png" width="300px" alt="prompt1"></td>
    <td><img src="./images/img_prom3.png" width="300px" alt="prompt1"></td>
  </tr>
</table>

**4. 자동 루틴 파악 코드**
<p align="left">
  <img src="./images/img_routine.png" width="60%" alt="routine" />
</p>

* **일주일에 3회 이상 같은 이벤트 발생 시 자동 루틴으로 판단, 사용자에게 루틴 등록 여부 질문**
* **만약 사용자가 거절한 루틴의 경우 데이터를 삭제하지 않고, DB에 저장하여 같은 루틴 탐지 시 질문하지 않음**

**5. DB ERD**
<p align="left">
  <img src="./images/img_db.png" width="60%" alt="dberd" />
</p>

---

## ⚠️ 보완점 및 향후 과제
- **Pose 및 좌표 재보정**  
  LiDAR 센서를 활용해 양옆, 전후 거리를 측정하고 목표 좌표 도착 후 위치 및 각도 오차를 자동 보정하는 기능을 추가할 예정이다.

- **카메라 및 QR 코드 인식**  
  OpenCV 기반 QR 코드 인식을 통해 위치 검증 및 화물 정보를 확인하고, 인식 결과에 따라 원하는 좌표로 이동하는 로직으로 확장할 예정이다.

- **Grid Map 생성 자동화**  
  현재 하드코딩된 Grid Map 구조를 개선하여, TurtleBot을 수동 조작하면서 이동 가능한 좌표를 기록하고 파일로 저장하는 기능을 적용할 예정이다.

- **실시간 장애물 대응 및 동적 재탐색**  
  주행 중 예기치 않은 장애물이나 경로 차단 상황이 발생할 경우, 현재 위치 기준으로 경로를 다시 계산하는 기능을 강화할 예정이다.

- **다중 로봇 확장성 개선**  
  현재 2대 기준으로 검증한 충돌 회피 및 작업 스케줄링 구조를 3대 이상의 TurtleBot 환경에서도 안정적으로 동작하도록 확장할 예정이다.
---

## 💁‍♂️ 팀원

| 이름 | 역할 | 담당 파트 |
|----------|----------|----------|
| 박준서 | 팀장, BE | DB, API 서버 설계 및 구축, RAG & LLM 혼합 알고리즘, LLM 프롬프트 작성 |
| 이상현 | 부팀장, FE |  |
| 안해성 | Robotic Engineering |  |
| 김준기 | AI Develop |  |
