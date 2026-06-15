import datetime
import json
import math
import uuid
import httpx
import pymysql
import asyncio

# --- [기존 설정 레이어 동기화] ---
DB_CONFIG = {
    "host": "10.10.16.238",
    "user": "myuser",
    "password": "mypassword",
    "database": "test_db",
    "charset": "utf8mb4",
    "cursorclass": pymysql.cursors.DictCursor,
}
LM_STUDIO_URL = "http://10.10.16.24:1234/v1/chat/completions"

# 요일 매핑용 사전
DAY_MAP = {
    "Monday": "월",
    "Tuesday": "화",
    "Wednesday": "수",
    "Thursday": "목",
    "Friday": "금",
    "Saturday": "토",
    "Sunday": "일",
}


def cosine_similarity(v1, v2):
    """두 벡터 간의 코사인 유사도를 계산합니다."""
    dot_product = sum(a * b for a, b in zip(v1, v2))
    norm_a = math.sqrt(sum(a * a for a in v1))
    norm_b = math.sqrt(sum(b * b for b in v2))
    if norm_a == 0 or norm_b == 0:
        return 0.0
    return dot_product / (norm_a * norm_b)


async def ask_llm_for_routine_title(sentences):
    """비슷한 의미로 묶인 여러 문장들을 하나의 깔끔한 알람 문구로 요약합니다."""
    sample_text = "\n".join([f"- {s}" for s in sentences[:5]])

    prompt = f"""[역할]
당신은 치매 보조 시스템의 데이터 정제 엔진입니다. 유저의 반복적인 행동 로그들을 보고, 알람 서비스에 등록할 핵심 행동 명사형 문구(최대 12자)로 요약해 주세요.

[로그 예시 데이터]
{sample_text}

[출력 규칙]
1. 인사말, 설명, 부연설명, 따옴표 없이 오직 요약된 "핵심 행동 결과"만 딱 한 줄 출력하세요.
2. 문구 예시: '창문 닫기 확인', '혈압약 복용', '거실 전등 끄기', '안방 환기'

[최종 결과]:"""

    payload_data = {
        "model": "google/gemma-4-e2b",
        "messages": [{"role": "user", "content": prompt}],
        "temperature": 0.1,
    }

    async with httpx.AsyncClient() as client:
        try:
            response = await client.post(
                LM_STUDIO_URL,
                json=payload_data,
                headers={"Content-Type": "application/json"},
                timeout=30.0,
            )
            if response.status_code == 200:
                res_json = response.json()
                return res_json["choices"][0]["message"]["content"].strip()
        except Exception as e:
            print(f"⚠️ LLM 이름 정제 실패: {e}")
    return "반복 행동 확인"  # 폴백 문구


async def run_daily_routine_batch():
    print(f"⏳ [{datetime.datetime.now()}] 주간 데이터 기반 루틴 분석 배치 시작...")

    # 1. DB에서 지난 일주일간의 데이터 가져오기
    # HOUR() 함수를 사용해 시간대별(0~23) 분기를 쿼리단에서 지원
    query = """
        SELECT id, user_id, device_id, event_dt, event_ct, embedding,
               HOUR(event_dt) as event_hour,
               DAYNAME(event_dt) as day_name
        FROM event_tb
        WHERE event_dt >= DATE_SUB(CURDATE(), INTERVAL 7 DAY)
          AND embedding IS NOT NULL;
    """

    conn = pymysql.connect(**DB_CONFIG)
    try:
        with conn.cursor() as cursor:
            cursor.execute(query)
            rows = cursor.fetchall()
    finally:
        conn.close()

    if not rows:
        print("ℹ️ 최근 일주일간 분석할 이벤트 로그가 데이터베이스에 없습니다.")
        return

    # 2. Python 메모리단에서 구조화: { user_id: { hour: [rows...] } }
    user_hour_maps = {}
    for row in rows:
        u_id = str(row["user_id"])
        hour = row["event_hour"]

        if u_id not in user_hour_maps:
            user_hour_maps[u_id] = {h: [] for h in range(24)}
        user_hour_maps[u_id][hour].append(row)

    # 3. 유저별 & 시간대별 순회하며 벡터 유사도 클러스터링 진행
    SIMILARITY_THRESHOLD = 0.78  # 💡 코사인 유사도 기준값 (이 값이 넘으면 같은 종류의 행동으로 판정)

    for u_id, hours in user_hour_maps.items():
        for hour, items in hours.items():
            if len(items) < 3:
                continue  # 전체 개수가 3개 미만이면 루틴 성립 불가하므로 스킵

            # 유사도 기반 자체 클러스터링 루프
            clusters = []  # 구조: [{'embedding': [], 'rows': []}, ...]

            for item in items:
                try:
                    item_vector = json.loads(item["embedding"])
                except Exception:
                    continue  # 임베딩 포맷 에러 예외처리

                matched_cluster = None
                for cluster in clusters:
                    # 클러스터의 대표 벡터(첫 아이템)와 현재 아이템 비교
                    sim = cosine_similarity(item_vector, cluster["rep_vector"])
                    if sim >= SIMILARITY_THRESHOLD:
                        matched_cluster = cluster
                        break

                if matched_cluster:
                    matched_cluster["rows"].append(item)
                else:
                    clusters.append(
                        {"rep_vector": item_vector, "rows": [item]}
                    )

            # 4. 생성된 클러스터 검증 및 루틴 적재 준비
            for cluster in clusters:
                cluster_rows = cluster["rows"]

                # 요일 중복을 제거하여 '최소 다른 3일 이상' 발생했는지 검증 (유저 정책에 따라 선택 가능)
                unique_days = set(DAY_MAP[r["day_name"]] for r in cluster_rows)

                # 조건 만족: 일주일에 3회 이상 동일 시간대 발생
                if len(cluster_rows) >= 3:
                    # A. 평균 발생 시각 구하기 (초 단위 환산 후 평균 내어 TIME 포맷 변환)
                    total_seconds = 0
                    for r in cluster_rows:
                        dt = r["event_dt"]
                        total_seconds += (
                            dt.hour * 3600 + dt.minute * 60 + dt.second
                        )
                    avg_seconds = int(total_seconds / len(cluster_rows))
                    avg_time = str(datetime.timedelta(seconds=avg_seconds))

                    # B. 정렬된 요일 문자열 생성 (예: '월,화,수,목,금')
                    day_order = ["월", "화", "수", "목", "금", "토", "일"]
                    sorted_days = [d for d in day_order if d in unique_days]
                    alarm_days_str = ",".join(sorted_days)

                    # C. LLM을 통한 최종 알람 문구 정제
                    all_sentences = [r["event_ct"] for r in cluster_rows]
                    alarm_content = await ask_llm_for_routine_title(
                        all_sentences
                    )

                    # 대표 device_id 추출
                    dev_id = str(cluster_rows[0]["device_id"])

                    # D. DB 적재 진행 (status='PENDING')
                    print(
                        f"🎯 [루틴 발견] 유저: {u_id} | 시간대: {hour}시 | 요일: {alarm_days_str} | 내용: {alarm_content}"
                    )
                    await insert_routine(
                        u_id, dev_id, avg_time, alarm_days_str, alarm_content
                    )


async def insert_routine(user_id, device_id, alarm_time, alarm_days, content):
    """추출된 최종 루틴을 routine_tb에 PENDING 상태로 보관합니다."""
    conn = pymysql.connect(**DB_CONFIG)
    try:
        with conn.cursor() as cursor:
            # 테이블 컬럼 명세에 맞춰 안전하게 Python 내부에서 문자열 UUID 생성 후 바인딩
            routine_id = str(uuid.uuid4())
            sql = """
                INSERT INTO routine_tb (id, user_id, alarm_time, alarm_days, alarm_content, status)
                VALUES (%s, %s, %s, %s, %s, 'PENDING');
            """
            cursor.execute(
                sql, (routine_id, user_id, alarm_time, alarm_days, content)
            )
            conn.commit()
    except Exception as e:
        print(f"❌ 루틴 DB 적재 실패: {e}")
    finally:
        conn.close()


# 스크립트 단독 실행용 가드
if __name__ == "__main__":
    asyncio.run(run_daily_routine_batch())