import datetime
import json
import asyncio
from typing import Literal
from typing import List, Optional, Dict
from fastapi import FastAPI, HTTPException, Query, WebSocket, WebSocketDisconnect
from fastapi.responses import StreamingResponse
from fastapi.concurrency import run_in_threadpool
import httpx
import pymysql
from pydantic import BaseModel, Field
from sentence_transformers import SentenceTransformer

import base64
import io
from PIL import Image, ImageDraw

app = FastAPI(
    title="See Ur Memory API Server (WebSocket Connection)",
    description="초기 치매 및 건망증 보조를 위한 상황 인지 백엔드 서버",
    version="1.5.0",
)

# --- [설정 레이어] ---
DB_CONFIG = {
    "host": "10.10.16.238",  
    "user": "myuser",
    "password": "mypassword",
    "database": "test_db",
    "charset": "utf8mb4",
    "cursorclass": pymysql.cursors.DictCursor,
}

LM_STUDIO_URL = "http://10.10.16.24:1234/v1/chat/completions"
# EDGE_SERVER_URL = "http://10.10.16.238:8000/api/v1/capture"

OBJECT_MAPPING = {
    "wallet": ["지갑"],
    "cup": ["컵"],
    "phone": ["폰", "핸드폰", "스마트폰", "전화기", "휴대폰"],
    "key": ["열쇠", "키"],
    "pill_bottle": ["약병"],
    "remote": ["리모컨"],
    "window_closed": ["창문닫힘"],
    "window_open": ["창문열림"]
}

print("🧠 한국어 임베딩 모델 로드 중...")
embedding_model = SentenceTransformer("jhgan/ko-sroberta-multitask", device="cpu")

# --- [💡 웹소켓 커넥션 매니저 클래스] ---
class EdgeConnectionManager:
    def __init__(self):
        self.active_connections: Dict[str, WebSocket] = {}
        self.response_events: Dict[str, asyncio.Event] = {}
        self.edge_responses: Dict[str, dict] = {}

    async def connect(self, user_id: str, websocket: WebSocket):
        await websocket.accept()
        self.active_connections[user_id] = websocket
        print(f"🔌 [Edge Connected] 유저 [{user_id}]의 라즈베리파이가 웹소켓에 상시 연결되었습니다.")

    def disconnect(self, user_id: str):
        if user_id in self.active_connections:
            del self.active_connections[user_id]
            print(f"❌ [Edge Disconnected] 유저 [{user_id}]의 라즈베리파이 연결이 끊어졌습니다.")
        if user_id in self.response_events:
            del self.response_events[user_id]
        if user_id in self.edge_responses:
            del self.edge_responses[user_id]

    async def request_realtime_capture(self, user_id: str, timeout: float = 12.0) -> dict:
        if user_id not in self.active_connections:
            return {"result": "fail", "message": "라즈베리파이 엣지 보드가 오프라인 상태입니다. (웹소켓 미연결)"}

        websocket = self.active_connections[user_id]
        event = asyncio.Event()
        self.response_events[user_id] = event
        
        try:
            await websocket.send_json({"command": "CAPTURE_NOW"})
            await asyncio.wait_for(event.wait(), timeout=timeout)
            
            # 🔍 [디버깅 추가] event.wait() 완료 후 캐시된 응답 원본 확인
            raw_response = self.edge_responses.get(user_id, {"result": "fail", "message": "응답 파싱 오류"})
            # print(f"🐛 [Debug] request_realtime_capture 반환 데이터 (User: {user_id}): {raw_response}")
            
            return self.edge_responses.get(user_id, {"result": "fail", "message": "응답 파싱 오류"})
        except asyncio.TimeoutError:
            return {"result": "fail", "message": f"Edge 서버 응답 시간 초과 ({timeout}초)"}
        finally:
            if user_id in self.response_events:
                del self.response_events[user_id]

    def set_response(self, user_id: str, data: dict):
        if user_id in self.response_events:
            self.edge_responses[user_id] = data
            self.response_events[user_id].set()

manager = EdgeConnectionManager()

# --- [🔌 엣지 보드 연결 전용 웹소켓 엔드포인트 - 최상단 배치] ---
@app.websocket("/ws/v1/edge")
async def websocket_edge_endpoint(websocket: WebSocket):
    """라즈베리파이 엣지 보드가 메인 서버에 커넥션을 수립하는 직관적인 통로입니다."""
    user_id = websocket.query_params.get("user_id")
    if not user_id:
        print("⚠️ [웹소켓 거절] user_id 쿼리 파라미터가 없습니다.")
        await websocket.close(code=1008)
        return

    await manager.connect(user_id, websocket)
    try:
        while True:
            data = await websocket.receive_json()
            
            
            
            # 엣지가 보낸 데이터가 'CAPTURE_NOW'에 대한 정식 응답 포맷(예: captures 키가 포함된 경우)인지 확인
            if data.get("result") == "success" and "captures" in data:
                manager.edge_responses[user_id] = data
                
                # 💡 오직 제대로 된 캡처 데이터가 올 때만 대기 중인 request_realtime_capture를 깨웁니다.
                if user_id in manager.response_events:
                    manager.response_events[user_id].set()
            else:
                # 주기적 하트비트나 다른 로그 데이터는 이벤트 플래그(.set())를 건드리지 않고 
                # 다른 백그라운드 처리나 DB 정기 적재 로직으로만 흘려보냅니다.
                pass
            
            
            if data.get("type") == "CAPTURE_RESPONSE":
                manager.set_response(user_id, data.get("payload", {}))
    except WebSocketDisconnect:
        manager.disconnect(user_id)
    except Exception as e:
        print(f"❌ [웹소켓 루프 에러] 유저 [{user_id}]: {e}")
        manager.disconnect(user_id)


# ==================== [DB 공통 함수 레이어] ====================
def insert_routine_to_db(user_id: str, alarm_time: str, alarm_days: str, alarm_content: str, status: str, type: str) -> bool:
    """
    [공통 DB Insert 함수]
    - API 요청(수동)과 배치 프로세스(자동추론) 모두 이 함수를 거쳐 DB에 저장됩니다.
    - 배치 프로세스 중복 추천을 막기 위해, 동일 시간대/내용의 루틴이 이미 존재(APPROVED/REJECTED/PENDING)하면 차단합니다.
    """
    try:
        conn = pymysql.connect(**DB_CONFIG)
        with conn.cursor() as cursor:
            # 💡 [방어벽] 이미 사용자가 등록했거나, 거절했거나, 이미 대기 중인 동일 루틴군이 있는지 조회
            check_sql = """
                SELECT id, status FROM routine_tb 
                WHERE user_id = %s 
                  AND HOUR(alarm_time) = HOUR(%s) 
                  AND alarm_content = %s
            """
            cursor.execute(check_sql, (user_id, alarm_time, alarm_content))
            existing = cursor.fetchone()

            if existing:
                print(f"🚫 [인서트 차단] 이미 유저가 {existing['status']} 처리한 루틴이 존재하여 생략합니다.")
                conn.close()
                return False

            # 중복이 없는 경우에만 최종 적재
            insert_sql = """
                INSERT INTO routine_tb (id, user_id, alarm_time, alarm_days, alarm_content, status, type)
                VALUES (UUID(), %s, %s, %s, %s, %s, %s)
            """
            cursor.execute(insert_sql, (user_id, alarm_time, alarm_days, alarm_content, status, type))
            conn.commit()
            
        conn.close()
        return True
    except Exception as e:
        print(f"❌ [DB routine_tb 적재 에러]: {e}")
        return False
    
# ==== 이미지 bbox ====
def draw_bbox_on_image(base64_image: str, matched_objects: list) -> str:
    """
    Base64 이미지와 매칭된 객체 리스트를 받아 
    해당 객체들의 bbox 위치에 사각형을 그려 다시 Base64로 반환합니다.
    """
    try:
        # 1. Base64 -> PIL Image 변환
        img_data = base64.b64decode(base64_image)
        image = Image.open(io.BytesIO(img_data))
        draw = ImageDraw.Draw(image)
        
        for obj in matched_objects:
            bbox_str = obj.get("bbox", "")
            label = obj.get("label", "오브젝트")
            if not bbox_str:
                continue
                
            # '159 326 214 366' 문자열을 [159, 326, 214, 366] 숫자로 변환
            coords = list(map(int, bbox_str.split()))
            
            if len(coords) == 4:
                # ⚠️ 일반적인 YOLO 포맷인 [xmin, ymin, xmax, ymax] 기준으로 가정합니다.
                # 만약 클라이언트가 [x, y, w, h]로 보낸다면 coords[2]=x+w, coords[3]=y+h 로 가공해야 합니다.
                x1, y1, x2, y2 = coords
                
                # 선 두께 4, 빨간색(RGB: 255, 0, 0) 사각형 그리기
                draw.rectangle([x1, y1, x2, y2], outline=(255, 0, 0), width=4)
                
                # 사각형 좌상단에 텍스트 라벨 추가 (선택 사항)
                draw.text((x1 + 5, max(0, y1 - 15)), label, fill=(255, 0, 0))
                
        # 2. PIL Image -> Base64 문자열 변환
        buffered = io.BytesIO()
        # 원래 이미지 포맷을 유지하거나 JPEG로 압축 저장
        image.save(buffered, format="JPEG")
        encoded_img = base64.b64encode(buffered.getvalue()).decode("utf-8")
        return encoded_img

    except Exception as e:
        print(f"⚠️ [BBox 드로잉 실패]: {e}")
        return base64_image # 실패 시 원본 이미지 반환 (방어 코드)
    
# ==================== [스키마 레이어] ====================
class RoutineCreateRequest(BaseModel):
    """사용자가 앱/태블릿에서 직접 알람 루틴을 등록할 때 받는 데이터 양식"""
    alarm_time: str        # 예: "07:00:00"
    alarm_days: List[str]  # 예: ["월", "화", "금"]
    alarm_content: str     # 예: "갑상선약 먹기"
    type: str | None = None
    
    
class LoginRequest(BaseModel):
    """사용자 로그인 요청 양식"""
    user_name: str = Field(..., description="사용자 아이디 (이름)")
    user_pw: str = Field(..., description="사용자 비밀번호")

class LoginResponse(BaseModel):
    """사용자 로그인 성공 리턴 양식"""
    result: str
    message: str
    user_id: str
    user_name: str

class RoutineQueryResponse(BaseModel):
    """루틴 목록 개별 데이터 응답 양식"""
    id: str
    user_id: str
    alarm_time: str        # JSON 직렬화를 위해 HH:MM:SS 문자열로 변환하여 제공
    alarm_days: str        # 예: "월,수,금"
    alarm_content: str     # 예: "갑상선약 먹기"
    status: str            # 기존 코드의 테이블 구조에 존재하는 status 필드 연동 ('APPROVED', 'PENDING' 등)
    type: str | None = None

class ManualEventCreateRequest(BaseModel):
    """사용자가 임의로 event_tb에 로그를 채워넣기 위한 요청 양식"""
    user_id: str = Field(..., description="사용자의 고유 UUID")
    event_ct: str = Field(..., description="이벤트 텍스트 내용 (예: '할아버지가 거실에서 텔레비전을 시청하셨습니다')")
    device_id: Optional[str] = Field(None, description="디바이스 고유 UUID (선택 사항)")
    event_dt: Optional[str] = Field(None, description="이벤트 발생 일시 (예: '2026-06-11 10:15:00', 생략 시 현재 시간으로 세팅)")
    objects: Optional[List[Dict]] = Field(default_factory=list, description="감지된 사물 리스트 JSON 구조 (선택 사항)")

# --- [데이터 검증 레이어 (Pydantic)] ---
class DetectedObject(BaseModel):
    label: str       
    status: str      
    bbox: str        
    timing: str      


class EdgeEventRequest(BaseModel):
    device_id: str  # 엣지 장비 고유 UUID
    image_before: str  
    image_after: str   
    detected_objects: List[DetectedObject]  
    model_config = {
        "json_schema_extra": {
            "example": {
                "device_id": "8a7b6c5d-4e3f-2a1b-0c9d-8e7f6a5b4c3d",
                "image_before": "base64_string_before...",  
                "image_after": "base64_string_after...",
                "detected_objects": [
                    {
                        "label": "사람",
                        "status": "",
                        "bbox": "[100, 150, 300, 600]",
                        "timing": "before"
                    },
                    {
                        "label": "지갑",
                        "status": "",
                        "bbox": "[200, 250, 250, 280]",
                        "timing": "before"
                    }
                ]
            }
        }
    }

# --- [통합 루틴 수정 스키마 레이어] ---
class RoutineUpdateRequest(BaseModel):
    """루틴 데이터 통합 수정 요청 양식"""
    routine_id: str = Field(..., description="수정하고자 하는 루틴의 고유 UUID (routine_tb의 id)")
    
    # 변경을 원하는 필드만 보낼 수 있도록 모두 Optional 처리
    alarm_time: Optional[str] = Field(None, description="변경할 알람 시간 (예: '08:30:00')")
    alarm_days: Optional[List[str]] = Field(None, description="변경할 발생 요일 배열 (예: ['월', '화'])")
    alarm_content: Optional[str] = Field(None, description="변경할 루틴 요약 문구")
    status: Optional[Literal["PENDING", "APPROVED", "REJECTED"]] = Field(None, description="변경할 상태값")

class RoutineUpdateResponse(BaseModel):
    """루틴 데이터 수정 성공 응답 양식"""
    result: str
    message: str
    routine_id: str
    updated_fields: List[str]  # 실제로 어떤 필드들이 수정되었는지 반환

# --- [내부 핵심 로직 레이어] ---

def search_vector_timeline(user_id: str, user_question: str, limit: int = 3) -> str:
    """
    [Retrieval] 특정 사용자의 질문과 유사한 과거 로그를 넉넉하게 가져와 
    임계값 필터링을 거친 후, '가장 최신 사건' 위주로 타임라인을 구성합니다.
    """
    query_embedding = embedding_model.encode(user_question)
    query_vector_string = f"[{','.join(map(str, query_embedding))}]"

    try:
        conn = pymysql.connect(**DB_CONFIG)
        with conn.cursor() as cursor:
            # 💡 [수정] DB에서는 유사도 순으로 후보군을 넉넉하게 15개 가져옵니다.
            sql = """
                SELECT e.event_dt, e.event_ct, d.device_name,
                       VEC_DISTANCE_COSINE(e.embedding, VEC_FromText(%s)) AS distance
                FROM event_tb e
                LEFT JOIN device_tb d ON e.device_id = d.id
                WHERE e.user_id = %s
                ORDER BY distance ASC
                LIMIT 15
            """
            cursor.execute(sql, (query_vector_string, user_id))
            rows = cursor.fetchall()
        conn.close()

        if not rows:
            return ""

        # 임계값 필터링 (코사인 거리 0.6 이하만 인정)
        DISTANCE_THRESHOLD = 0.6
        filtered_rows = []

        for row in rows:
            try:
                if isinstance(row["distance"], bytes):
                    row["distance"] = float(row["distance"].decode("utf-8"))
                else:
                    row["distance"] = float(row["distance"])
            except Exception:
                row["distance"] = 999.0  

            if row["distance"] <= DISTANCE_THRESHOLD:
                filtered_rows.append(row)

        if not filtered_rows:
            print(f"🔍 [RAG 컨텍스트] 임계값 조건을 만족하는 과거 기록이 없습니다.")
            return ""

        # 💡 [핵심 수정] 의미 있는 데이터들 중 "가장 최근에 일어난 사건" 순으로 정렬 후 limit(3개)만큼 자릅니다.
        recent_rows = sorted(filtered_rows, key=lambda x: x["event_dt"], reverse=True)[:limit]
        
        # 💡 LLM 프롬프트에 주입할 때는 다시 시간 순서대로(과거 -> 현재) 배열해 줍니다.
        sorted_rows = sorted(recent_rows, key=lambda x: x["event_dt"])
        
        context_lines = []
        for row in sorted_rows:
            dt_str = row["event_dt"].strftime("%Y-%m-%d %H:%M:%S")
            device_name = row.get("device_name") or "알 수 없는 장소"
            content = row.get("event_ct") or "내용 없음"
            
            context_lines.append(f"[{dt_str}] [{device_name}] {content}")

        final_context = "\n".join(context_lines)
        print(f"🔍 [RAG 컨텍스트 조립 완료 - 최신 데이터 보정]:\n{final_context}")
        return final_context

    except Exception as e:
        print(f"⚠️ [search_vector_timeline 에러]: {e}")
        return ""    

def get_user_id_by_device(device_id: str) -> str:
    """[Helper] 디바이스 UUID를 기반으로 맵핑된 유저 UUID를 동적 조회합니다."""
    conn = pymysql.connect(**DB_CONFIG)
    try:
        with conn.cursor() as cursor:
            sql = "SELECT user_id FROM device_tb WHERE id = %s"
            cursor.execute(sql, (device_id,))
            row = cursor.fetchone()
            if not row:
                raise HTTPException(status_code=404, detail="등록되지 않은 디바이스 엔티티입니다.")
            return str(row["user_id"])
    finally:
        conn.close()
        
        
        
def get_user_devices_map_from_db(user_id: str) -> dict:
    """DB의 device_tb에서 특정 유저에게 등록된 디바이스 메타데이터를 가져와 
    {device_id: device_name} 딕셔너리로 반환합니다.
    """
    try:
        conn = pymysql.connect(**DB_CONFIG)
        with conn.cursor() as cursor:
            sql = "SELECT id, device_name FROM device_tb WHERE user_id = %s"
            cursor.execute(sql, (user_id,))
            rows = cursor.fetchall()
        conn.close()
        
        # 예: {"d9ad24c6-aea5...": "거실", "e4a211b4...": "개인방"}
        return {r["id"]: r["device_name"] for r in rows}
    except Exception as e:
        print(f"⚠️ [device_tb 조회 실패]: {e}")
        return {}
    

# 💡 DB에서 device_id를 기준으로 자연어 장소명(device_name)을 뽑아오는 헬퍼 함수
def get_device_name_from_db(dev_id: str) -> str:
    try:
        conn = pymysql.connect(**DB_CONFIG)
        with conn.cursor() as cursor:
            sql = "SELECT device_name FROM device_tb WHERE id = %s"
            cursor.execute(sql, (dev_id,))
            row = cursor.fetchone()
        conn.close()
        return row["device_name"] if row else "알 수 없는 구역"
    except Exception as e:
        print(f"⚠️ [device_tb 조회 실패]: {e}")
        return "알 수 없는 구역"


async def classify_user_intent(user_question: str) -> str:
    """[Intent] LLM을 이용해 실시간 / 과거 / 아웃스코프 의도를 분기합니다."""
    intent_prompt = f"""
사용자 질문: "{user_question}"
의도 분류 결과:"""

    payload = {
        "model": "google/gemma-4-e2b",
        "messages": [{"role": "user", "content": intent_prompt}],
        "temperature": 0.0
    }

    async with httpx.AsyncClient() as client:
        try:
            response = await client.post(LM_STUDIO_URL, json=payload, timeout=15.0)
            if response.status_code == 200:
                result = response.json()["choices"][0]["message"]["content"].strip().upper()
                
                # 💡 [보완] 결과 문자열에 포함된 키워드로 정확하게 분기
                if "REALTIME" in result:
                    return "REALTIME"
                elif "CHITCHAT" in result:
                    return "CHITCHAT"
                elif "PAST" in result:
                    return "PAST"
                    
            return "CHITCHAT"  # 에러나 모호한 경우 기본적으로 아웃스코프 처리
        except Exception as e:
            print(f"⚠️ 의도 판별 실패: {repr(e)}")
            return "CHITCHAT"
        

async def fetch_realtime_edge_data(address: str) -> dict:
    """[Edge Link] 특정 주소의 엣지 보드 인터페이스로 HTTP GET 요청을 보내 
    해당 장비에 물려있는 멀티캠 수집 페이로드를 가져옵니다.
    💡 (추후 확장 시) 웹소켓 구조로 업그레이드되면 이 함수를 websocket.send/receive 구조로 전환하면 끝납니다.
    """
    url = f"http://{address}/api/v1/capture"
    async with httpx.AsyncClient() as client:
        try:
            response = await client.get(url, timeout=15.0)
            if response.status_code == 200:
                return response.json()
            return {"result": "fail", "message": f"Edge 응답 에러 (코드: {response.status_code})"}
        except httpx.RequestError as e:
            return {"result": "fail", "message": f"Edge 서버 네트워크 연결 실패 ({address}): {e}"}


# --- [API 엔드포인트 정의] ---

@app.get("/")
async def root():
    return {"status": "running", "message": "See Ur Memory Server Core v1.3.0 is Alive!"}

@app.post("/api/v1/auth/login", response_model=LoginResponse)
async def login(payload: LoginRequest):
    """
    [사용자 인증/로그인 엔드포인트]
    - 입력받은 명세(user_name)를 기반으로 단일 유저 엔티티를 매핑한 후, 비밀번호를 대조하여 고유 식별 키값(UUID)을 반환합니다.
    """
    def verify_user_credentials():
        try:
            conn = pymysql.connect(**DB_CONFIG)
            with conn.cursor() as cursor:
                # 💡 안전한 조회를 위해 아이디 조건으로 매칭되는 레코드를 선조회
                sql = "SELECT id, user_name, user_pw FROM user_tb WHERE user_name = %s"
                cursor.execute(sql, (payload.user_name,))
                user_row = cursor.fetchone()
            conn.close()
            
            if not user_row:
                return None
            
            # 💡 [보완 가능 코드] 필요시 hashlib.sha512(payload.user_pw.encode() + salt).hexdigest() 검증 레이어로 교체 가능
            if user_row["user_pw"] == payload.user_pw:
                return user_row
            return None
        except Exception as e:
            print(f"❌ [user_tb 조회 실패]: {e}")
            return None

    # 블로킹 I/O 작업 분리 실행
    user_data = await run_in_threadpool(verify_user_credentials)
    
    if not user_data:
        raise HTTPException(
            status_code=401, 
            detail="인증에 실패했습니다. 아이디 또는 비밀번호를 다시 확인하세요."
        )
        
    return {
        "result": "success",
        "message": "로그인에 성공했습니다. 인증 토큰 식별자가 유효합니다.",
        "user_id": str(user_data["id"]),
        "user_name": user_data["user_name"]
    }


@app.post("/api/v1/edge/events")
async def receive_edge_events(payload: EdgeEventRequest):
    """
    [보안 특이 이벤트 발생 엔드포인트 - 하이브리드 콘텍스트 인젝션 엔진]
    1. Python 단에서 Object Detection 결과(before/after)를 비교 연산하여 명확한 팩트 트리거 생성
    2. 생성된 트리거 로그를 프롬프트 내부 데이터 세트로 직접 주입
    3. 추천된 JSON:API 규격(application/vnd.api+json)에 맞춰 멀티모달 LLM 호출
    4. 분석된 정밀 시각 보안 로그를 한국어 임베딩화하여 DB에 적재
    """
    current_time = datetime.datetime.now().strftime("%Y-%m-%d %H:%M:%S")
    
    # 1. 디바이스 소유권 사전 조회 (유저 식별)
    user_id = get_user_id_by_device(payload.device_id)

    # =================================================================
    # [1단계] Object Detection 결과 데이터 전/후 비교 및 프롬프트용 트리거 생성
    # =================================================================
    before_objs = [obj for obj in payload.detected_objects if obj.timing == "before"]
    after_objs = [obj for obj in payload.detected_objects if obj.timing == "after"]

    detection_events = []

    # A. 상태 변화 감지 (예: 창문 열림 -> 닫힘 예외 처리 포함)
    status_changes = set()
    for b_obj in before_objs:
        for a_obj in after_objs:
            if b_obj.label == a_obj.label and b_obj.status != a_obj.status:
                status_changes.add(f'- "{b_obj.label}" 상태 변경 발생 ({b_obj.status} -> {a_obj.status})')
    detection_events.extend(list(status_changes))

    # B. 수량 및 존재 여부 비교 분석 (생성 및 소멸)
    before_counts = {}
    for obj in before_objs:
        before_counts[obj.label] = before_counts.get(obj.label, 0) + 1

    after_counts = {}
    for obj in after_objs:
        after_counts[obj.label] = after_counts.get(obj.label, 0) + 1

    # 사라진 객체 체크 (과거엔 있었으나 현재 없어졌거나 수량이 줄어든 경우)
    for label, count in before_counts.items():
        curr_count = after_counts.get(label, 0)
        if curr_count == 0:
            detection_events.append(f'- "{label}" 사라짐 이벤트 발생')
        elif curr_count < count:
            detection_events.append(f'- "{label}" 일부 사라짐 이벤트 발생')

    # 새로 나타난 객체 체크 (과거엔 없었으나 현재 새로 탐지되었거나 수량이 늘어난 경우)
    for label, count in after_counts.items():
        prev_count = before_counts.get(label, 0)
        if prev_count == 0:
            detection_events.append(f'- "{label}" 새로 탐지됨(생성) 이벤트 발생')
        elif count > prev_count:
            detection_events.append(f'- 새로운 "{label}" 추가 탐지됨 이벤트 발생')

    # 연산된 결과 리스트를 문자열로 직렬화하여 프롬프트 콘텍스트로 매핑
    detection_events_str = "\n".join(detection_events) if detection_events else "- 특이 객체 변동 사항 없음"

    # =================================================================
    # [2단계] 이미지 상세 분석과 수치형 팩트를 결합한 프롬프트 디자인
    # =================================================================
    print(detection_events_str)

    # =================================================================
    # [3단계] LLM 추천 사양 (JSON:API 구조 및 미디어 타입 헤더) 적용
    # =================================================================
    payload_data = {
        "model": "google/gemma-4-e2b:2",     # ← LM Studio에 로드된 비전모델 이름과 일치해야
        "messages": [
            # {"role": "system", "content": prompt_content},
            {"role": "user", "content": [
                {"type": "text", "text": detection_events_str},
                {"type": "image_url", "image_url": {"url": f"data:image/jpeg;base64,{payload.image_before}"}},
                {"type": "image_url", "image_url": {"url": f"data:image/jpeg;base64,{payload.image_after}"}}
            ]}
        ],
        "temperature": 0.0
    }

    # 추천 명세에 따른 전용 JSON:API 규격 헤더 정의
    headers = {
        "Content-Type": "application/json",
        "Accept": "application/json"
    }

    # 외부 멀티모달 LLM 연동
    async with httpx.AsyncClient() as client:
        try:
            response = await client.post(LM_STUDIO_URL, json=payload_data, headers=headers, timeout=None)
            if response.status_code != 200:
                raise HTTPException(status_code=502, detail=f"LLM 멀티모달 해석 엔진 응답 실패. 상태코드: {response.status_code}")
            
            res_json = response.json()
            
            # JSON:API 규격 응답 포맷에 유연하게 대처하기 위한 데이터 추출 가드 로직
            llm_output = ""
            if "choices" in res_json:
                llm_output = res_json["choices"][0]["message"]["content"].strip()
            elif "data" in res_json and "attributes" in res_json["data"]:
                attrs = res_json["data"]["attributes"]
                if "choices" in attrs:
                    llm_output = attrs["choices"][0]["message"]["content"].strip()
                elif "message" in attrs:
                    llm_output = attrs["message"]["content"].strip()
                elif "content" in attrs:
                    llm_output = attrs["content"].strip()
            
            if not llm_output:
                llm_output = str(res_json) # 최후의 보루 방어용 백업 데이터 처리

        except httpx.RequestError as e:
            raise HTTPException(status_code=503, detail=f"해석 엔진 서버 연결 불가: {e}")

    # ... [앞단계 생략: 1~3단계 수행 및 LLM 응답 추출 완료] ...

    # LLM 출력을 줄바꿈 기준으로 정제하되, 최종 저장을 위해 하나로 다시 합칩니다.
    lines = [line.strip() for line in llm_output.split("\n") if line.strip()]
    if not lines:
         return {"result": "success", "message": "감지된 특이 보안 사건이 없습니다."}

    # 💡 [핵심 수정]: 쪼개진 문장들을 줄바꿈(\n) 문자로 결합하여 하나의 통합 텍스트 문자열로 만듭니다.
    combined_event_ct = "\n".join(lines)

    # =================================================================
    # [4단계] 분석 완료 후 DB 트랜잭션 적재 진행 (단일 행 저장 아키텍처)
    # =================================================================
    try:
        conn = pymysql.connect(**DB_CONFIG)
        with conn.cursor() as cursor:
            sql_insert = """
                INSERT INTO event_tb (id, user_id, device_id, event_dt, event_ct, embedding, objects) 
                VALUES (UUID(), %s, %s, %s, %s, VEC_FromText(%s), %s);
            """
            
            
            # 현재 시점(after)의 객체 목록 데이터 직렬화
            current_detected_only = [{"label": obj.label, "status": obj.status, "bbox": obj.bbox} for obj in payload.detected_objects if obj.timing == "after"]
            raw_objects_json = json.dumps(current_detected_only, ensure_ascii=False)

            # 💡 [핵심 수정]: for 문을 완전히 제거했습니다.
            # 통합된 전체 문장 블록에 대해 임베딩 인코딩을 딱 한 번만 수행하여 단일 벡터를 추출합니다.
            sentence_vector = embedding_model.encode(combined_event_ct).tolist()
            vector_string = json.dumps(sentence_vector)
            
            # 💡 단 한 번의 execute 호출로 하나의 행(Row)만 적재합니다.
            cursor.execute(
                sql_insert, 
                (
                    user_id, 
                    payload.device_id, 
                    current_time, 
                    combined_event_ct,  # 여러 문장이 줄바꿈으로 담긴 하나의 텍스트
                    vector_string,      # 이 텍스트 전체의 단일 임베딩 벡터
                    raw_objects_json
                )
            )
            
            # [4단계 DB 적재 레이어 내부 추가 예시]
            current_detected_only = [{"label": obj.label, "status": obj.status, "bbox": obj.bbox} for obj in payload.detected_objects if obj.timing == "after"]
            raw_objects_json = json.dumps(current_detected_only, ensure_ascii=False)

            # 중복되면 덮어쓰는(Upsert) 명확한 쿼리 디자인
            sql_upsert_state = """
                INSERT INTO device_state_tb (device_id, user_id, objects)
                VALUES (%s, %s, %s)
                ON DUPLICATE KEY UPDATE objects = VALUES(objects);
            """
            cursor.execute(sql_upsert_state, (payload.device_id, user_id, raw_objects_json))
            
            conn.commit()
        conn.close()
        
        return {
            "result": "success", 
            "message": "시각 컨텍스트 기반 보안 사건 로그가 단일 행으로 성공적으로 적재되었습니다.", 
            "analyzed_events": lines
        }
        
    except Exception as e:
        raise HTTPException(status_code=500, detail=f"상황 데이터베이스 인서트 중 내부 오류 발생: {e}")
    
    
    
@app.post("/api/v1/event/manual")
async def create_manual_event_log(payload: ManualEventCreateRequest):
    """
    [임의 이벤트 데이터 강제 적재 API]
    - 사용자가 프론트엔드나 테스트 도구(Swagger 등)에서 인자값을 전달하면 
      텍스트 내용을 기반으로 실시간 임베딩을 수행한 후 event_tb에 직접 INSERT 합니다.
    """
    # 1. 시간 예외 처리 (넘겨받은 값이 없으면 서버 현재 시간 사용)
    current_time = payload.event_dt or datetime.datetime.now().strftime("%Y-%m-%d %H:%M:%S")
    
    # 2. 본문(event_ct)에 기반한 한국어 임베딩 벡터 생성
    try:
        sentence_vector = embedding_model.encode(payload.event_ct).tolist()
        vector_string = json.dumps(sentence_vector)
    except Exception as e:
        print(f"❌ [수동 이벤트 임베딩 생성 실패]: {e}")
        raise HTTPException(
            status_code=500, 
            detail="텍스트 임베딩 벡터를 변환하는 중 내부 서버 오류가 발생했습니다."
        )

    # 3. objects 리스트를 DB에 넣기 위해 JSON 문자열로 직렬화
    raw_objects_json = json.dumps(payload.objects, ensure_ascii=False)

    # 4. DB 적재 트랜잭션 함수 정의
    def insert_manual_event_transaction():
        try:
            conn = pymysql.connect(**DB_CONFIG)
            with conn.cursor() as cursor:
                # 제안해주신 스키마 컬럼 구조에 1:1 매핑
                sql_insert = """
                    INSERT INTO event_tb (id, user_id, device_id, event_dt, event_ct, embedding, objects) 
                    VALUES (UUID(), %s, %s, %s, %s, VEC_FromText(%s), %s);
                """
                cursor.execute(
                    sql_insert, 
                    (
                        payload.user_id, 
                        payload.device_id,  # None 이면 SQL에서 NULL로 유연하게 처리됨
                        current_time, 
                        payload.event_ct, 
                        vector_string, 
                        raw_objects_json
                    )
                )
                conn.commit()
            conn.close()
            return "SUCCESS"
        except Exception as e:
            print(f"❌ [수동 이벤트 DB 인서트 에러]: {e}")
            return "ERROR"

    # 5. FastAPI 스레드 풀 격리 실행 (비동기 블로킹 I/O 가드)
    execution_result = await run_in_threadpool(insert_manual_event_transaction)
    
    if execution_result == "ERROR":
        raise HTTPException(
            status_code=500, 
            detail="데이터베이스에 데이터를 적재하는 과정에서 내부 오류가 발생했습니다. UUID 포맷이나 커넥션을 확인하세요."
        )
        
    return {
        "result": "success",
        "message": "임의의 상황 정보 로그가 데이터베이스에 성공적으로 강제 적재되었습니다.",
        "recorded_data": {
            "user_id": payload.user_id,
            "device_id": payload.device_id,
            "event_dt": current_time,
            "event_ct": payload.event_ct,
            "objects": payload.objects
        }
    }
    
    

@app.get("/api/v1/chat/query")
async def chat_query_stream(
    user_id: str = Query(
        default="b4c4f3e6-2d9c-4ec6-af36-08581a6b1339", 
        description="사용자의 고유 UUID 키값 (user_tb 참조)"
    ),
    user_question: str = Query(
        default="내 지갑 지금 어디에 있어?", 
        description="AI에게 물어볼 질문 (REALTIME / PAST / CHITCHAT 의도 자동 판별)"
    )
):
    """
    [OpenAI 호환 규격 RAG 스트리밍 라우터]
    의도를 판별하여 아웃스코프(CHITCHAT) 혹은 매칭 지식이 부족한 경우 고정 거절 문구를 즉시 반환하며,
    정상 시나리오에서는 과거 로그(RAG) 및 실시간 카메라 맵을 결합하여 지식을 기반으로 답변합니다.
    """

    def get_device_name_from_db(dev_id: str) -> str:
        try:
            conn = pymysql.connect(**DB_CONFIG)
            with conn.cursor() as cursor:
                sql = "SELECT device_name FROM device_tb WHERE id = %s"
                cursor.execute(sql, (dev_id,))
                row = cursor.fetchone()
            conn.close()
            return row["device_name"] if row else "알 수 없는 구역"
        except Exception as e:
            print(f"⚠️ [device_tb 조회 실패]: {e}")
            return "알 수 없는 구역"

    async def response_generator():
        # [1단계] 의도 분석 수행 (REALTIME / PAST / CHITCHAT)
        intent = await classify_user_intent(user_question)
        
        # 💡 [철저한 고정 답변 양식 정의] 구조화된 OpenAI 스트림 규격을 유지하기 위한 헬퍼 함수
        def generate_fallback_chunks(intent_type: str, context_str: str = ""):
            current_timestamp = int(datetime.datetime.now().timestamp())
            
            meta_chunk = {
                "id": "chatcmpl-seeurmemoryRAG",
                "object": "chat.completion.chunk",
                "created": current_timestamp,
                "model": "see-ur-memory-rag-v1",
                "choices": [{
                    "index": 0,
                    "delta": {
                        "role": "assistant",
                        "content": "",  
                        "intent": intent_type,
                        "retrieved_context": context_str,
                        "current_context": None,
                        "images_map": None  # 거절 시 이미지 미포함
                    },
                    "finish_reason": None
                }]
            }
            text_chunk = {
                "id": "chatcmpl-seeurmemoryRAG",
                "object": "chat.completion.chunk",
                "created": current_timestamp,
                "model": "see-ur-memory-rag-v1",
                "choices": [{
                    "index": 0,
                    "delta": {"content": "제공된 지식 내용으로는 답변할 수 없습니다."},
                    "finish_reason": "stop"
                }]
            }
            return f"data: {json.dumps(meta_chunk, ensure_ascii=False)}\n\ndata: {json.dumps(text_chunk, ensure_ascii=False)}\n\n"

        # 💡 [방어벽 A] 일상 대화(CHITCHAT)의 경우 LLM 연동 없이 즉시 거절 문구 반환 후 종료
        if intent == "CHITCHAT":
            print(f"🛑 [Early Return] 아웃스코프 질문 진입 차단 -> 고정 거절 답변 출력")
            yield generate_fallback_chunks(intent)
            return

        # [2단계] 임계값이 적용된 과거 벡터 타임라인 데이터 로드
        context_timeline = await run_in_threadpool(search_vector_timeline, user_id, user_question, 3)
        
        current_context_str = ""
        target_images_map = {}

        # [3단계] 실시간 탐색 의도인 경우 라즈베리파이 연동
        # [3단계] 실시간 탐색 의도인 경우 라즈베리파이 연동
        if intent == "REALTIME":
            edge_res = await manager.request_realtime_capture(user_id, timeout=12.0)
            
            if edge_res.get("result") == "success":
                captures = edge_res.get("captures", [])
                multi_camera_contexts = []
                
                for cap in captures:
                    edge_device_id = cap.get("device_id")
                    location = await run_in_threadpool(get_device_name_from_db, edge_device_id)
                    detected_objs = cap.get("detected_objects", [])
                    img_b64 = cap.get("image")
                    
                    # 💡 [수정 포인트] 이번 카메라에서 사용자가 찾는 물건들만 수집할 리스트
                    is_target_found_in_cam = False
                    matched_objs_in_this_cam = []
                    
                    for obj in detected_objs:
                        label_lower = obj['label'].lower()
                        is_current_obj_matched = False
                        
                        # Case A: 영문 레이블 자체가 질문에 포함되어 있거나
                        if label_lower in user_question.lower():
                            is_current_obj_matched = True
                            
                        # Case B: 한글 변환 매핑 리스트 중 하나가 질문에 포함되어 있는 경우
                        elif label_lower in OBJECT_MAPPING:
                            if any(kr_word in user_question for kr_word in OBJECT_MAPPING[label_lower]):
                                is_current_obj_matched = True
                        
                        # 매칭 성공 시 마킹 및 리스트 업
                        if is_current_obj_matched:
                            is_target_found_in_cam = True
                            matched_objs_in_this_cam.append(obj) # 👈 찾은 객체 정보만 누적
                    
                    # 💡 [수정 포인트] 찾고자 하는 객체가 검출된 경우에만 'BBox를 그린 이미지'를 이미지 맵에 포함!!
                    if img_b64 and is_target_found_in_cam:
                        # 이미지 처리는 CPU 연산이므로 안정을 위해 run_in_threadpool로 wrapping 처리 권장
                        annotated_img_b64 = await run_in_threadpool(
                            draw_bbox_on_image, img_b64, matched_objs_in_this_cam
                        )
                        
                        target_images_map[location] = annotated_img_b64
                        print(f"📸 [실시간 이미지 매칭 & BBox 처리 성공] '{location}' 화면에서 대상 객체 발견 -> 크롭/드로잉 완료 이미지 리턴")
                        
                    obj_strings = [f"{obj['label']}(상태:{obj['status']})" for obj in detected_objs]
                    multi_camera_contexts.append(f"[{location}]: 감지된 객체 리스트 -> {str(obj_strings)}")
                
                now_str = datetime.datetime.now().strftime("%Y-%m-%d %H:%M:%S")
                if multi_camera_contexts:
                    current_context_str = f"현재 시점 실시간 카메라 촬영 결과 ({now_str}):\n" + "\n".join(multi_camera_contexts)
                else:
                    current_context_str = f"현재 시점 실시간 카메라 촬영 성공했으나 활성화된 카메라 스트림이 없습니다. ({now_str})"
            else:
                current_context_str = f"현재 시점 실시간 카메라 촬영 실패: {edge_res.get('message', '원인 불명')}"

        # 💡 [방어벽 B 정밀 수정] 질문 의도에 상응하는 지식이 완전히 전무한지 체크
        # PAST: 과거 기록이 없음 -> 즉시 컷
        if intent == "PAST" and not context_timeline:
            print(f"🛑 [Early Return] 과거 기록 분석 결과 매칭 정보 전무 -> 고정 답변 출력")
            yield generate_fallback_chunks(intent, context_timeline)
            return

        # REALTIME: 과거 기록도 없고 + 현재 활성화된 실시간 카메라 피드 내에서도 대상 물건이 아예 검출 안 됨 (target_images_map이 비어있음) -> 즉시 컷
        if intent == "REALTIME" and not context_timeline and not target_images_map:
            print(f"🛑 [Early Return] 과거 기억도 없고 실시간 카메라에도 물건이 포착 안 됨 -> 고정 답변 출력")
            yield generate_fallback_chunks(intent, context_timeline)
            return

        # ================== 정상 RAG 파이프라인 진입 레이어 ==================
        # 실시간 탐색 상황이라도 카메라에 대상 물건이 안 잡혔다면 `images_map`은 빈 값 또는 None으로 전달됩니다.
        first_chunk = {
            "id": "chatcmpl-seeurmemoryRAG",
            "object": "chat.completion.chunk",
            "created": int(datetime.datetime.now().timestamp()),
            "model": "see-ur-memory-rag-v1",
            "choices": [{
                "index": 0,
                "delta": {
                    "role": "assistant",
                    "content": "",  
                    "intent": intent,
                    "retrieved_context": context_timeline,
                    "current_context": current_context_str if intent == "REALTIME" else None,
                    "images_map": target_images_map if (intent == "REALTIME" and target_images_map) else None # 👈 조건 만족시에만 맵 전달
                },
                "finish_reason": None
            }]
        }
        yield f"data: {json.dumps(first_chunk, ensure_ascii=False)}\n\n"

        # 시스템 가이드라인 프롬프트 세트 레이어

        hybrid_prompt = f"""
[홈캠 관찰, 기록 지식 내용 (CCTV 로그)]: 
{context_timeline if context_timeline else "보유 중인 매칭 정보가 없습니다."}

[현재 시점 실시간 감지 데이터 (Current Context)]:
{current_context_str if current_context_str else "실시간 데이터가 요구되지 않는 질문 레이어입니다."}

사용자 질문: {user_question}
조건에 맞춘 간결한 답변:"""

        payload = {
            "model": "google/gemma-4-e2b:3",
            "messages": [
                {"role": "user", "content": hybrid_prompt},
            ],
            "temperature": 0.0,
            "stream": True  
        }

        async with httpx.AsyncClient() as client:
            try:
                async with client.stream("POST", LM_STUDIO_URL, json=payload, timeout=None) as response:
                    if response.status_code != 200:
                        yield "data: {\"error\": \"LLM 추론 엔진 연동 실패\"}\n\n"
                        return

                    buffer = ""
                    async for chunk in response.aiter_bytes():
                        buffer += chunk.decode("utf-8")
                        while "\n" in buffer:
                            line, buffer = buffer.split("\n", 1)
                            line = line.strip()
                            if not line:
                                continue
                            if line.startswith("data: "):
                                yield f"{line}\n\n"
                                
            except httpx.RequestError as e:
                yield f"data: {json.dumps({'error': f'LLM 서버 통신 불가: {str(e)}'}, ensure_ascii=False)}\n\n"

    return StreamingResponse(response_generator(), media_type="text/event-stream")



@app.get("/api/v1/routine", response_model=List[RoutineQueryResponse])
async def get_user_routines(
    user_id: str = Query(..., description="조회하고자 하는 사용자의 고유 UUID 키값")
):
    """
    [특정 사용자 루틴 타임라인 조회 엔드포인트]
    - 특정 user_id 하위에 매핑된 알람 루틴 목록을 전체 조회하여 시간순(alarm_time ASC)으로 정렬 반환합니다.
    """
    def fetch_routines_from_db():
        try:
            conn = pymysql.connect(**DB_CONFIG)
            with conn.cursor() as cursor:
                # 💡 기존 소스코드의 insert_routine_to_db 메커니즘에 포함된 status 필드까지 누락 없이 통합 조회
                sql = """
                    SELECT id, user_id, alarm_time, alarm_days, alarm_content, status, type
                    FROM routine_tb 
                    WHERE user_id = %s
                    ORDER BY alarm_time ASC
                """
                cursor.execute(sql, (user_id,))
                rows = cursor.fetchall()
            conn.close()
            return rows
        except Exception as e:
            print(f"❌ [routine_tb 전체 조회 에러]: {e}")
            return []

    db_rows = await run_in_threadpool(fetch_routines_from_db)
    
    # 💡 [핵심 엔지니어링 디테일] 
    # PyMySQL의 DictCursor는 MySQL/MariaDB의 'TIME' 타입을 Python의 'datetime.timedelta' 객체로 리턴합니다.
    # 이를 그대로 내보내면 FastAPI 내부 json_encoder가 직렬화하지 못하고 크래시(TypeError)를 발생시키므로
    # 프론트엔드가 즉시 파싱할 수 있도록 깨끗한 'HH:MM:SS' 문자열 포맷팅 작업을 수행합니다.
    formatted_routines = []
    for row in db_rows:
        raw_time = row["alarm_time"]
        alarm_time_str = ""
        
        if isinstance(raw_time, datetime.timedelta):
            total_seconds = int(raw_time.total_seconds())
            hours = total_seconds // 3600
            minutes = (total_seconds % 3600) // 60
            seconds = total_seconds % 60
            alarm_time_str = f"{hours:02d}:{minutes:02d}:{seconds:02d}"
        elif isinstance(raw_time, datetime.time):
            alarm_time_str = raw_time.strftime("%H:%M:%S")
        else:
            alarm_time_str = str(raw_time)

        formatted_routines.append({
            "id": str(row["id"]),
            "user_id": str(row["user_id"]),
            "alarm_time": alarm_time_str,
            "alarm_days": row["alarm_days"],
            "alarm_content": row["alarm_content"],
            "status": row.get("status", "PENDING"), # 혹시 모를 누락에 대비한 디폴트 값 바인딩
            "type": row.get("type")
        })
        
    return formatted_routines
    
@app.post("/api/v1/routine")
async def create_manual_routine(
    payload: RoutineCreateRequest,
    user_id: str = Query(default="b4c4f3e6-2d9c-4ec6-af36-08581a6b1339", description="사용자 UUID")
):
    """
    [사용자 수동 루틴 알람 추가 API]
    - 사용자가 직접 등록 버튼을 눌러 추가하므로 상태값은 즉시 'APPROVED'로 저장됩니다.
    """
    # 리스트 포맷 ["월", "화"] -> 문자열 "월,화" 형태로 전환
    joined_days = ",".join(payload.alarm_days)
    
    # 공통 함수 호출 (상태값: APPROVED)
    success = insert_routine_to_db(
        user_id=user_id,
        alarm_time=payload.alarm_time,
        alarm_days=joined_days,
        alarm_content=payload.alarm_content,
        status="APPROVED",  # 👈 수동 추가이므로 즉시 승인 상태
        type=payload.type
    )
    
    if not success:
        raise HTTPException(status_code=400, detail="루틴을 등록할 수 없습니다. 이미 등록/거절된 루틴이거나 형식을 확인하세요.")
        
    return {"result": "success", "message": "사용자 지정 알람 루틴이 성공적으로 활성화되었습니다."}






# ==================== [서버 측 배치용 함수 예시] ====================
# 나중에 서버 백그라운드 배치 스크립트(또는 다른 파일)에서 호출할 때의 형태입니다.
def server_side_batch_trigger_example(inferred_user_id: str, inferred_time: str, inferred_days: str, inferred_text: str):
    """
    [서버 측 Batch Process 전용 삽입 함수 가이드]
    - 주간 로그 분석 후 발견된 유저 패턴을 이 함수를 통해 강제로 찔러 넣습니다.
    """
    print("⏳ [Batch] 주간 패턴 분석 알고리즘 조건 만족함. 루틴 후보 등록을 시작합니다...")
    
    # 공통 함수 호출 (상태값: PENDING)
    success = insert_routine_to_db(
        user_id=inferred_user_id,
        alarm_time=inferred_time,     # 예: "07:00:00"
        alarm_days=inferred_days,     # 예: "월,화,금"
        alarm_content=inferred_text,   # 예: "갑상선약 먹기"
        status="PENDING",   # 👈 추천 상태이므로 대기(PENDING)로 적재 -> 이후 푸시 알림 발송 연동
        type=""
    )
    
    if success:
        print(f"📱 [Batch Push 알림 발송 완료] 사용자 {inferred_user_id}에게 루틴 추가 수락 요청을 보냈습니다.")
    else:
        print("⏭️ [Batch] 이미 처리된 내역이 있어 알림 발송을 스킵합니다.")
        
        
@app.patch("/api/v1/routine", response_model=RoutineUpdateResponse)
async def update_routine_all_in_one(payload: RoutineUpdateRequest):
    """
    [루틴 데이터 통합 유도리 수정 엔드포인트]
    - 특정 routine_id에 대해 시간, 요일, 내용, 상태값 중 변경 사항이 있는 필드만 동적으로 파싱하여 단일 트랜잭션으로 수정합니다.
    """
    
    # 1. 변경할 필드가 아예 없는 경우 예외 처리
    update_data = {}
    if payload.alarm_time is not None:
        update_data["alarm_time"] = payload.alarm_time
    if payload.alarm_days is not None:
        # 기존 main.py 패턴에 맞춰 배열을 "월,수,금" 형태의 콤마 문자열로 변환하여 저장
        update_data["alarm_days"] = ",".join(payload.alarm_days)
    if payload.alarm_content is not None:
        update_data["alarm_content"] = payload.alarm_content
    if payload.status is not None:
        update_data["status"] = payload.status

    if not update_data:
        raise HTTPException(
            status_code=400, 
            detail="수정할 데이터 필드가 입력되지 않았습니다. 하나 이상의 필드를 포함하여 요청하세요."
        )

    def update_routine_transaction():
        try:
            conn = pymysql.connect(**DB_CONFIG)
            with conn.cursor() as cursor:
                # 2. 해당 루틴 레코드가 실제 존재하는지 선검증
                sql_check = "SELECT id FROM routine_tb WHERE id = %s"
                cursor.execute(sql_check, (payload.routine_id,))
                exists = cursor.fetchone()
                
                if not exists:
                    conn.close()
                    return "NOT_FOUND", []
                
                # 3. 동적 SQL SET 구문 생성 (인입된 데이터만 쿼리에 바인딩)
                set_clauses = []
                query_params = []
                for field_name, field_value in update_data.items():
                    set_clauses.append(f"{field_name} = %s")
                    query_params.append(field_value)
                
                # WHERE 절 조건 추가
                query_params.append(payload.routine_id)
                
                # 최종 동적 쿼리 조립
                sql_update = f"""
                    UPDATE routine_tb 
                    SET {', '.join(set_clauses)} 
                    WHERE id = %s
                """
                
                cursor.execute(sql_update, tuple(query_params))
                conn.commit()
                
            conn.close()
            return "SUCCESS", list(update_data.keys())
        except Exception as e:
            print(f"❌ [routine_tb 동적 업데이트 에러]: {e}")
            return "ERROR", []

    # 비동기 스레드 풀 안전 격리 실행
    execution_result, modified_fields = await run_in_threadpool(update_routine_transaction)
    
    if execution_result == "NOT_FOUND":
        raise HTTPException(
            status_code=404, 
            detail=f"해당 식별자({payload.routine_id})를 가진 루틴 데이터를 시스템 내에서 찾을 수 없습니다."
        )
    elif execution_result == "ERROR":
        raise HTTPException(
            status_code=500, 
            detail="데이터베이스 갱신 처리 중 서버 내부 오류가 발생했습니다."
        )
        
    return {
        "result": "success",
        "message": f"루틴 데이터가 성공적으로 수정되었습니다. (변경 항목: {', '.join(modified_fields)})",
        "routine_id": payload.routine_id,
        "updated_fields": modified_fields
    }
    
    
    
@app.get("/api/v1/edge/objects/latest")
async def get_latest_objects_by_user(user_id: str = Query(..., description="조회하고자 하는 사용자의 UUID")):
    """
    [사용자별 최신 사물 상태 현황 조회 API - 장비명(device_name) 기준 그룹화]
    입력된 user_id에 속한 모든 디바이스의 가장 최신 'after' 탐지 객체 목록을 장비명 기준으로 반환합니다.
    """
    def fetch_latest_states():
        try:
            conn = pymysql.connect(**DB_CONFIG)
            with conn.cursor() as cursor:
                # 💡 TRIM()을 추가하여 공백 불일치를 방지하고, 미매핑 시 구분할 수 있도록 쿼리를 보완했습니다.
                sql = """
                    SELECT 
                        st.device_id, 
                        COALESCE(
                            dt.device_name, 
                            CONCAT('(미등록 장비) ', st.device_id)
                        ) AS device_name, 
                        st.objects, 
                        st.updated_at 
                    FROM device_state_tb st
                    LEFT JOIN device_tb dt ON TRIM(st.device_id) = TRIM(dt.id)
                    WHERE st.user_id = %s
                """
                cursor.execute(sql, (user_id,))
                results = cursor.fetchall()
            conn.close()
            return results
        except Exception as e:
            print(f"❌ [device_state_tb & device_tb 조인 조회 중 에러]: {e}")
            return None

    # FastAPI 스레드 풀에서 안전하게 비동기 블로킹 I/O 처리
    db_rows = await run_in_threadpool(fetch_latest_states)
    
    if db_rows is None:
        raise HTTPException(
            status_code=500, 
            detail="최신 객체 상태 현황을 조회하는 중 데이터베이스 오류가 발생했습니다."
        )
        
    if not db_rows:
        return {
            "result": "success",
            "user_id": user_id,
            "message": "해당 유저로 등록된 최신 디바이스 사물 현황 데이터가 존재하지 않습니다.",
            "device_states": {}
        }

    # 결과 데이터 포맷팅
    device_states_map = {}
    for row in db_rows:
        dev_name = row["device_name"]  
        
        try:
            parsed_objects = json.loads(row["objects"])
        except Exception:
            parsed_objects = []
            
        device_states_map[dev_name] = {
            "device_id": row["device_id"], 
            "updated_at": row["updated_at"].strftime("%Y-%m-%d %H:%M:%S") if row["updated_at"] else None,
            "detected_objects": parsed_objects
        }

    return {
        "result": "success",
        "user_id": user_id,
        "device_states": device_states_map
    }
    
    


@app.get("/api/v1/routine/check")
async def check_routine_condition(
    user_id: str = Query(..., description="검증하고자 하는 사용자의 고유 UUID"),
    category: str = Query(..., description="구분 인자값 ('window' 또는 'medicine' / 한글 '창문', '약통' 모두 지원)")
):
    """
    [루틴 알람 실행 조건 판단 전용 API]
    - category가 'window'(창문)일 경우: 
      device_state_tb에서 실시간 탐지된 객체 중 label이 '창문'인 요소의 장소별 status(열림/닫힘 등) 목록을 반환합니다.
    - category가 'medicine'(약통)일 경우: 
      event_tb에서 현재 시점 기준 '최근 1시간 이내'에 '약을 먹었습니다'라는 문장이 기록되었는지 여부(True/False)를 반환합니다.
    """
    
    # 💡 유연성을 위해 한글과 영문 인자값을 모두 정상 표준 카테고리로 정규화합니다.
    normalized_category = ""
    if category in ["window", "창문"]:
        normalized_category = "window"
    elif category in ["medicine", "약통", "약"]:
        normalized_category = "medicine"
    else:
        raise HTTPException(
            status_code=400,
            detail="올바르지 않은 구분 인자값입니다. 'window'(창문) 또는 'medicine'(약통)을 사용하세요."
        )

    # ==========================================
    # [시나리오 1] 창문 상태값 확인 (device_state_tb)
    # ==========================================
    if normalized_category == "window":
        def fetch_window_states_transaction():
            try:
                conn = pymysql.connect(**DB_CONFIG)
                with conn.cursor() as cursor:
                    # 장비명(거실, 개인방 등)과 함께 현재 사물 현황 상태 정보를 함께 가져옵니다.
                    sql = """
                        SELECT 
                            COALESCE(dt.device_name, CONCAT('(미등록 장비) ', st.device_id)) AS device_name, 
                            st.objects 
                        FROM device_state_tb st
                        LEFT JOIN device_tb dt ON TRIM(st.device_id) = TRIM(dt.id)
                        WHERE st.user_id = %s
                    """
                    cursor.execute(sql, (user_id,))
                    results = cursor.fetchall()
                conn.close()
                return results
            except Exception as e:
                print(f"❌ [DB 창문 조건 조회 에러]: {e}")
                return None

        db_rows = await run_in_threadpool(fetch_window_states_transaction)
        
        if db_rows is None:
            raise HTTPException(status_code=500, detail="창문 상태를 조회하는 과정에서 데이터베이스 오류가 발생했습니다.")

        window_list = []
        for row in db_rows:
            try:
                parsed_objects = json.loads(row["objects"])
            except Exception:
                parsed_objects = []

            for obj in parsed_objects:
                # 💡 label이 '창문'인 데이터의 status를 확보합니다.
                if obj.get("label") == "창문":
                    window_list.append({
                        "device_name": row["device_name"],
                        "label": obj.get("label"),
                        "status": obj.get("status")  # 예: '닫힘', '열림' 등
                    })

        return {
            "result": "success",
            "category": "window",
            "user_id": user_id,
            "windows": window_list  # 장소별 창문 상태 리스트 반환
        }

    # ==========================================
    # [시나리오 2] 1시간 이내 약 복용 여부 확인 (event_tb)
    # ==========================================
    elif normalized_category == "medicine":
        def check_medicine_intake_transaction():
            try:
                conn = pymysql.connect(**DB_CONFIG)
                with conn.cursor() as cursor:
                    # 💡 현재 시간 기준 1시간 전(NOW() - INTERVAL 1 HOUR)부터 지금까지 
                    # event_ct가 정확히 '약을 먹었습니다'인 행이 존재하는지 카운트합니다.
                    sql = """
                        SELECT COUNT(*) AS cnt 
                        FROM event_tb 
                        WHERE user_id = %s 
                          AND event_ct LIKE '%%약을 먹었습니다%%'
                          AND event_dt >= NOW() - INTERVAL 1 HOUR
                    """
                    cursor.execute(sql, (user_id,))
                    row = cursor.fetchone()
                conn.close()
                
                # 기록이 1개 이상 존재하면 약을 이미 복용한 것으로 판단 (True)
                return row["cnt"] > 0 if row else False
            except Exception as e:
                print(f"❌ [DB 약 복용 여부 조회 에러]: {e}")
                return None

        has_taken = await run_in_threadpool(check_medicine_intake_transaction)
        
        if has_taken is None:
            raise HTTPException(status_code=500, detail="약 복용 이력을 조회하는 과정에서 데이터베이스 오류가 발생했습니다.")

        return {
            "result": "success",
            "category": "medicine",
            "user_id": user_id,
            "has_taken_medicine": has_taken  # 1시간 이내에 먹었으면 True, 안 먹었으면 False
        }
        
@app.post("/api/v1/routine/medicine")
async def record_manual_medicine_intake(
    user_id: str = Query(..., description="약을 복용한 사용자의 고유 UUID")
):
    """
    [수동 약 복용 기록 API - Vector 및 UUID 문법 에러 완벽 수정본]
    사용자가 직접 앱에서 약 복용을 체크했을 때 호출하며, 
    event_tb에 VEC_FromText 문법을 활용한 임베딩 벡터를 포함하여 로그를 강제 적재합니다.
    """
    current_time = datetime.datetime.now().strftime("%Y-%m-%d %H:%M:%S")
    event_content = "약을 먹었습니다"
    
    # 1. 고정 문장에 대한 한국어 임베딩 벡터 생성
    try:
        sentence_vector = embedding_model.encode(event_content).tolist()
        vector_string = json.dumps(sentence_vector)
    except Exception as e:
        print(f"❌ [수동 약 복용 임베딩 생성 실패]: {e}")
        raise HTTPException(
            status_code=500, 
            detail="텍스트 임베딩 벡터를 생성하는 중 서버 오류가 발생했습니다."
        )

    # 2. DB 적재 비즈니스 트랜잭션 정의
    def insert_event_transaction():
        try:
            conn = pymysql.connect(**DB_CONFIG)
            with conn.cursor() as cursor:
                # 💡 [핵심 수정]: embedding 컬럼 바인딩 위치에 VEC_FromText(%s) 구문을 적용했습니다.
                # 💡 device_id 컬럼의 UUID 에러 방지를 위해 빈 문자열 대신 None(NULL)을 바인딩합니다.
                sql_insert = """
                    INSERT INTO event_tb (id, user_id, device_id, event_dt, event_ct, embedding, objects) 
                    VALUES (UUID(), %s, %s, %s, %s, VEC_FromText(%s), %s);
                """
                cursor.execute(
                    sql_insert, 
                    (user_id, None, current_time, event_content, vector_string, "[]")
                )
                conn.commit()
            conn.close()
            return "SUCCESS"
        except pymysql.err.OperationalError as e:
            # 만약 DB 설정상 device_id가 NOT NULL(필수값)이라 NULL 입력이 차단될 경우의 Fallback 패치
            if e.args[0] == 1048 or "Column 'device_id' cannot be null" in str(e):
                try:
                    with conn.cursor() as cursor:
                        sql_fallback = """
                            INSERT INTO event_tb (id, user_id, device_id, event_dt, event_ct, embedding, objects) 
                            VALUES (UUID(), %s, %s, %s, %s, VEC_FromText(%s), %s);
                        """
                        cursor.execute(
                            sql_fallback,
                            (user_id, "00000000-0000-0000-0000-000000000000", current_time, event_content, vector_string, "[]")
                        )
                        conn.commit()
                    conn.close()
                    return "SUCCESS"
                except Exception as ex:
                    print(f"❌ [수동 약 복용 최종 적재 실패 - 예외 발생]: {ex}")
            print(f"❌ [수동 약 복용 DB 적재 에러]: {e}")
            return "ERROR"
        except Exception as e:
            print(f"❌ [수동 약 복용 DB 적재 에러]: {e}")
            return "ERROR"

    # 3. 비동기 스레드 풀에서 블로킹 I/O 안전 실행
    execution_result = await run_in_threadpool(insert_event_transaction)
    
    if execution_result == "ERROR":
        raise HTTPException(
            status_code=500, 
            detail="데이터베이스에 약 복용 이력을 저장하는 과정에서 벡터 또는 스키마 매핑 오류가 발생했습니다."
        )
        
    return {
        "result": "success",
        "message": "약 복용 기록이 성공적으로 추가되었습니다.",
        "recorded_data": {
            "user_id": user_id,
            "event_dt": current_time,
            "event_ct": event_content,
            "device_id": None,
            "objects": []
        }
    }