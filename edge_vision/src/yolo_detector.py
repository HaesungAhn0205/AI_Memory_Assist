"""2카메라(방 2곳) 통합 모니터링 + TCP 영상 송출 + 실시간 capture API - Pi 배포용
- 카메라마다 독립 상태(재고/창문/약복용) 관리
- YOLO(NCNN 416) 공유, MediaPipe는 카메라별 독립
- ffmpeg TCP 송출(8555/8556) → PC 영상서버
- /api/v1/capture (8000) → main.py가 "물건 지금 어디?" 물을 때 최신 프레임+객체 반환
실행:  python event_2cam.py           (headless 백그라운드)
       python event_2cam.py --debug   (로컬 영상창 표시, q로 종료)
"""
# ============================================================
# [전체 동작 개요]
#
# ▷ 스레드 구성 (모두 daemon)
#   run_api          : FastAPI(8000). main.py가 /api/v1/capture로 "지금 뭐 보여?" 물으면 최신 프레임+객체 반환
#   ws_client        : main.py와 WebSocket 상시 연결. CAPTURE_NOW 명령 오면 전 카메라 캡처를 응답
#   _event_worker    : 이벤트 '직렬' 전송 큐. 동시 POST 폭주를 막아 서버 502 방지
#   state_uploader   : 매초 각 방 현재 상태를 콘솔 출력(옵션: 서버 push)
#   batch_inventory_sender : 5초마다 재고(창문 제외) 변화를 묶어 1회 전송 → 요청 수↓
#   CameraStream     : 카메라별 캡처 스레드. 항상 '최신 프레임'만 유지 → 지연 최소화
#   FrameStreamer    : 카메라별 ffmpeg TCP 송출 스레드(가득차면 프레임 버림 = 저지연)
#   메인 루프        : 프레임 읽기 → RoomMonitor.process(검출+이벤트) → capture 갱신 → TCP 송출
#
# ▷ 검출 → 재고 추적 (RoomMonitor.process)
#   YOLO NCNN 공유모델로 검출 → 클래스별 CONF(CLASS_CONF) 필터 → present 딕셔너리로 디바운스
#   · 나타남 확정 : 같은 라벨이 '누적' MIN_DET(5)회 검출돼야 confirmed (헛검출/깜빡임에 강함)
#   · 사라짐 유예 : 미검출돼도 DISAPPEAR_THRESHOLD(5초)간 살려둠(검출 갭 보호)
#   · pastlist    : confirmed 객체만 모음 = capture/배치 비교의 기준
#
# ▷ 이벤트 3종 (전부 send_event → 직렬 큐 → main.py)
#   A. 배치 재고 : 5초마다 재고 라벨셋이 바뀌면 before/after 전송 (사라짐=빨강박스 / 나타남=초록박스)
#   B. 창문      : 상태머신(open/closed/none 각각 WINDOW_MIN_DET(15)회+WINDOW_THRESHOLD(5초) 확정).
#                  last_real_state로 'none(가림)'은 건너뛰고 진짜 '열림↔닫힘' 전환만 이벤트 발사
#   C. 복약      : 손이 약통/컵(TARGET_TOUCH) 접촉 → ACTION_TIME_LIMIT(30초) 내 머리 근접 시
#                  '복약행동'을 after에만 추가(서버 diff가 출현으로 인식). ACTION_COOLDOWN(180초) 재발 방지
#
# ▷ 부가 장치
#   last_window_obj : 확정된 창문(상태+bbox) 스냅샷 → capture(device_state 캐시)에 항상 포함
#                     (마지막 확정 유지 / 가림 시 유지 / 한 번도 확정 안 되면 생략)
#   DARK_THRESHOLD  : 평균 밝기가 너무 낮은 프레임은 검출/이벤트 스킵(상태 동결, 밝아지면 자동 재개)
#   CLASS_CONF      : 역광 창문 글레어를 가짜 폰/리모컨으로 오인하는 헛검출 억제용 클래스별 임계
#   agnostic_nms    : 같은 위치 겹친 박스를 클래스 무관 1개만 남김(창문 open+closed 동시검출 방지)
# ============================================================
import os
import cv2
import time
import subprocess
import threading
import queue
import base64
import json
import requests
import uvicorn
import argparse
from fastapi import FastAPI
from ultralytics import YOLO
from collections import Counter
import mediapipe as mp

try:
    import websocket   # websocket-client (pip install websocket-client)
    HAS_WS = True
except ImportError:
    HAS_WS = False
    print("[경고] websocket-client 미설치 → 실시간 WS 캡처 비활성 (검출/이벤트는 정상)")

parser = argparse.ArgumentParser()
parser.add_argument('--debug', action='store_true',
                    help='로컬 영상 창 표시(디버그). 없으면 headless 백그라운드')
args = parser.parse_args()
DEBUG = args.debug

# ==========================================
# 설정
# ==========================================
# 모델 폴더는 이 스크립트와 같은 위치에 두면 됨 (실행 위치 무관 — 스크립트 기준 상대경로)
MODEL_PATH = os.path.join(os.path.dirname(os.path.abspath(__file__)), "yolo_20_yolo11n_ncnn_model")  # NCNN 416 폴더
CAM_IDS = [0, 2]            # /dev/video0(Wed Camera 1), /dev/video3(Wed Camera 2) — USB 재연결 시 v4l2-ctl로 확인
CONF = 0.25
CLASS_CONF = {'phone': 0.3, 'remote': 0.4, 'window_open': 0.25, 'window_closed': 0.35}  # 클래스별 최소 신뢰도(없으면 CONF). 역광 창문→가짜 검출 억제용
IMGSZ = 416                 # NCNN export 크기와 반드시 일치
DARK_THRESHOLD = 15         # 평균 밝기 이하면 검출/이벤트 스킵 (어둠발 헛검출·빈 이벤트 방지)

WINDOW_CLASSES = {'window_open', 'window_closed'}
TARGET_TOUCH = ('pill_bottle')

MIN_DET = 5                # 나타남 확정에 필요한 누적 검출 횟수 (헛검출 필터, 깜빡임에 강함)
DISAPPEAR_THRESHOLD = 5.0  # 사라짐 유예(초) — 갭 동안 객체 살려둠(확정 보호 + 누적 유지)
WINDOW_THRESHOLD = 5.0      # 창문 상태변화 확정 최소 지속시간(초)
WINDOW_MIN_DET = 15         # 창문 상태변화 확정에 필요한 '연속' 누적 검출 횟수(가림/헛검출 필터)
ACTION_TIME_LIMIT = 30.0
HEAD_MARGIN = 40
ACTION_COOLDOWN = 180.0

# 영상 송출
STREAM_BASE_PORT = 8555
STREAM_W, STREAM_H, STREAM_FPS = 640, 480, 30

# capture API
API_PORT = 8000             # main.py EDGE_SERVER_URL = http://<Pi>:8000/api/v1/capture

# 이벤트 서버 전송 (main.py)
SEND_TO_SERVER = True
MAIN_SERVER_URL = "http://10.10.16.15:8000/api/v1/edge/events"
EVENT_COOLDOWN = 30.0   # 같은 (라벨+종류) 이벤트 재전송 쿨다운(초) — 창문/약복용용
BATCH_INTERVAL = 5.0    # 재고 변화를 묶어서 보내는 주기(초) — 5초마다 장면 before/after 비교
DEVICE_IDS = [
    "d9ad24c6-aea5-4af8-8466-35d8c6c36817",   # room0 (cam 0)
    "3716a4a8-41f7-4574-977c-dde7833a2fd1",   # room1 (cam 2)
]
LABEL_KO = {
    'cup': '컵', 'key': '열쇠', 'phone': '스마트폰', 'pill_bottle': '약통',
    'remote': '리모컨', 'wallet': '지갑',
    'window_open': '창문', 'window_closed': '창문',
}

# 매초 상태 업로드 (Plan B) — 현재 탐지 객체 리스트를 서버로 push
SEND_STATE = False                                   # ★ 켜려면 True
PRINT_STATE = True                                   # 콘솔에 상태 리스트 출력 (끄려면 False)
STATE_URL = "http://10.10.16.15:8000/api/v1/state"   # ★ 서버 엔드포인트 (확인 필요)
STATE_INTERVAL = 1.0                                 # 업로드 주기(초)
STATE_LOST_GRACE = 5.0                               # 사라진 객체를 리스트에 유지하는 유예(초)

# WebSocket 실시간 캡처 (main.py가 CAPTURE_NOW 보내면 모든 카메라 응답)
ENABLE_WS = True
USER_ID = "b4c4f3e6-2d9c-4ec6-af36-08581a6b1339"     # ★ 사용자 UUID (user_tb)
WS_URL = f"ws://10.10.16.15:8000/ws/v1/edge?user_id={USER_ID}"   # ★ 서버 주소

# ==========================================
# 모델 (공유)
# ==========================================
print("모델 로딩...")
model = YOLO(MODEL_PATH, task='detect')
mp_hands = mp.solutions.hands
mp_face = mp.solutions.face_detection


# ==========================================
# 실시간 capture 공유 저장소 (루프가 갱신 → API가 읽음)
# ==========================================
capture_lock = threading.Lock()
latest_capture = {}   # idx -> {'frame': clean_bgr, 'objects': [...+conf...], 'device_id': str}

api = FastAPI(title="YOLO Edge Server (event_2cam integrated)")


@api.get("/")
def root():
    return {"status": "running", "message": "YOLO Edge Server is Alive!"}


@api.get("/api/v1/capture")
def capture_current_scene(room: int = 0, label: str = None):
    """label 주면(예: ?room=0&label=지갑) 그 물건 bbox에 빨간 상자를 그려 반환."""
    with capture_lock:
        data = latest_capture.get(room)
    if not data:
        return {"result": "fail", "message": f"room {room} 프레임 아직 없음"}
    img = data['frame'].copy()
    if label:                                   # 특정 물건 → bbox 상자
        for o in data['objects']:
            if o['label'] == label:
                x1, y1, x2, y2 = map(int, o['bbox'].split())
                cv2.rectangle(img, (x1, y1), (x2, y2), (0, 0, 255), 3)
    ok, buf = cv2.imencode('.jpg', img)
    if not ok:
        return {"result": "fail", "message": "인코딩 실패"}
    return {
        "result": "success",
        "device_id": data['device_id'],
        "image": base64.b64encode(buf).decode('utf-8'),
        "detected_objects": data['objects'],
    }


def run_api():
    """uvicorn을 별도 스레드에서 (cv2 GUI는 메인 스레드 유지)."""
    cfg = uvicorn.Config(api, host="0.0.0.0", port=API_PORT, log_level="warning")
    uvicorn.Server(cfg).run()


def build_captures():
    """모든 카메라의 최신 {device_id, detected_objects(영문 라벨), image} 리스트."""
    caps = []
    with capture_lock:
        for idx in sorted(latest_capture.keys()):
            d = latest_capture[idx]
            ok, buf = cv2.imencode('.jpg', d['frame'])
            if not ok:
                continue
            caps.append({
                "device_id": d['device_id'],
                "detected_objects": d['objects'],
                "image": base64.b64encode(buf).decode('utf-8'),
            })
    return caps


def ws_client():
    """서버에 WebSocket 상시 연결. CAPTURE_NOW 받으면 모든 카메라 캡처를 응답. 끊기면 재연결."""
    if not (ENABLE_WS and HAS_WS):
        return

    def on_message(ws, message):
        try:
            data = json.loads(message)
        except Exception:
            return
        if data.get("command") == "CAPTURE_NOW":
            payload = {"result": "success", "captures": build_captures()}
            ws.send(json.dumps({"type": "CAPTURE_RESPONSE", "payload": payload}))
            print("[WS] CAPTURE_NOW → 캡처 응답 전송")

    while True:
        try:
            app_ws = websocket.WebSocketApp(
                WS_URL,
                on_open=lambda w: print("🔌 [WS] 서버 연결됨"),
                on_message=on_message,
                on_error=lambda w, e: print(f"[WS 에러] {e}"),
                on_close=lambda w, c, m: print("[WS] 연결 끊김 → 재연결 대기"),
            )
            app_ws.run_forever()
        except Exception as e:
            print(f"[WS] 예외: {e}")
        time.sleep(5)   # 재연결 대기


# ==========================================
# TCP 영상 송출 (스레드 + 큐, 가득차면 버림)
# ==========================================
class FrameStreamer:
    """프레임을 ffmpeg(libx264, zerolatency)로 TCP(mpegts) 송출.
    큐(maxsize=2)가 차면 오래된 프레임을 버리고 최신만 보냄 → 지연이 쌓이지 않음(저지연).
    ffmpeg는 별도 스레드에서 stdin으로 raw BGR을 받아 인코딩→송출(?listen으로 PC 접속 대기)."""
    def __init__(self, port):
        self.port = port
        self.q = queue.Queue(maxsize=2)
        self.proc = None
        threading.Thread(target=self._run, daemon=True).start()

    def _run(self):
        cmd = [
            'ffmpeg', '-y',
            '-f', 'rawvideo', '-pix_fmt', 'bgr24',
            '-s', f'{STREAM_W}x{STREAM_H}',
            '-use_wallclock_as_timestamps', '1',
            '-i', '-',
            '-c:v', 'libx264', '-preset', 'ultrafast', '-tune', 'zerolatency',
            '-g', '15', '-pix_fmt', 'yuv420p',
            '-flush_packets', '1', '-muxdelay', '0',
            '-f', 'mpegts', f'tcp://0.0.0.0:{self.port}?listen'
        ]
        self.proc = subprocess.Popen(cmd, stdin=subprocess.PIPE)
        while True:
            frame = self.q.get()
            if frame is None:
                break
            try:
                self.proc.stdin.write(frame.tobytes())
            except Exception:
                pass

    def send(self, frame):
        if self.q.full():
            try:
                self.q.get_nowait()
            except queue.Empty:
                pass
        try:
            self.q.put_nowait(frame)
        except queue.Full:
            pass

    def close(self):
        try:
            self.q.put_nowait(None)
        except Exception:
            pass
        if self.proc:
            try:
                self.proc.stdin.close()
                self.proc.terminate()
            except Exception:
                pass


# ==========================================
# 카메라 (스레드로 항상 최신 프레임만 유지)
# ==========================================
class CameraStream:
    """카메라를 별도 스레드로 계속 읽어 '최신 프레임 1장'만 보관.
    메인 루프가 read()하면 항상 가장 최근 프레임을 반환 → 버퍼에 쌓인 옛 프레임으로 인한 지연 제거.
    (V4L2 + MJPG + BUFFERSIZE 1로 저지연 캡처)"""
    def __init__(self, cam_id):
        self.cap = cv2.VideoCapture(cam_id, cv2.CAP_V4L2)
        self.cap.set(cv2.CAP_PROP_FOURCC, cv2.VideoWriter_fourcc(*'MJPG'))
        self.cap.set(cv2.CAP_PROP_FRAME_WIDTH, 640)
        self.cap.set(cv2.CAP_PROP_FRAME_HEIGHT, 480)
        self.cap.set(cv2.CAP_PROP_FPS, 30)
        self.cap.set(cv2.CAP_PROP_BUFFERSIZE, 1)
        self.ret, self.frame = self.cap.read()
        self.lock = threading.Lock()
        self.running = True
        threading.Thread(target=self._update, daemon=True).start()

    def _update(self):
        while self.running:
            ret, frame = self.cap.read()
            if ret:
                with self.lock:
                    self.ret, self.frame = ret, frame

    def read(self):
        with self.lock:
            if self.frame is None:
                return False, None
            return self.ret, self.frame.copy()

    def isOpened(self):
        return self.cap.isOpened()

    def release(self):
        self.running = False
        self.cap.release()


# ==========================================
# 이벤트 서버 전송 (before/after → main.py)
# ==========================================
def _encode(img):
    ok, buf = cv2.imencode('.jpg', img)
    return base64.b64encode(buf).decode() if ok else ""


# 이벤트 전송: 직렬 큐(워커 1개가 순차 전송 → 동시요청 0) + 쿨다운(같은 이벤트 재전송 금지)
event_q = queue.Queue()
_last_sent = {}   # (device_id, cooldown_key) -> 마지막 전송 시각


def _event_worker():
    """큐에서 하나씩 꺼내 '순차' 전송. 동시에 여러 요청이 서버를 때리지 않게 함(502 예방)."""
    while True:
        device_id, img_b, img_a, detected = event_q.get()
        b64_before = _encode(img_b)             # 인코딩도 워커에서 (메인 루프 부담↓)
        b64_after = _encode(img_a)
        print(f"[전송준비] before={len(b64_before)}B / after={len(b64_after)}B")  # 0이면 빈 이미지(문제)
        payload = {
            "device_id": device_id,
            "image_before": b64_before,
            "image_after": b64_after,
            "detected_objects": detected,
        }
        try:
            r = requests.post(MAIN_SERVER_URL, json=payload, timeout=30)
            if r.status_code == 200:
                print("[전송 성공 200]")
            else:
                print(f"[전송 응답 {r.status_code}] {r.text[:150]}")
        except Exception as e:
            print(f"[전송 실패] {e}")


def send_event(device_id, img_before, img_after, objs_before, objs_after, cooldown_key=None):
    detected = [{**o, "timing": "before"} for o in objs_before] + \
               [{**o, "timing": "after"} for o in objs_after]
    print(f"[이벤트 객체] {detected}")
    if not SEND_TO_SERVER or not device_id:
        return
    # 쿨다운: 같은 (device, key) 이벤트가 최근 EVENT_COOLDOWN초 내면 스킵
    if cooldown_key is not None:
        now = time.time()
        if now - _last_sent.get((device_id, cooldown_key), 0) < EVENT_COOLDOWN:
            print(f"[쿨다운 스킵] {cooldown_key}")
            return
        _last_sent[(device_id, cooldown_key)] = now
    event_q.put((device_id, img_before, img_after, detected))   # 직렬 큐 → 워커가 순차 전송


def batch_inventory_sender(monitors):
    """5초마다 재고(창문 제외) 변화를 비교 → 변화 있으면 before/after 장면 1회 전송.
    여러 물건 변화를 한 요청에 묶음 → 서버가 장면 diff로 한꺼번에 처리(요청 수↓)."""
    snaps = {}   # idx -> (frame, objs, labelset)
    while True:
        time.sleep(BATCH_INTERVAL)
        for i, m in enumerate(monitors):
            cur_frame = m.prev_clean
            cur_objs = [o for o in m.pastlist if not o['label'].startswith('창문')]   # 재고만
            cur_set = frozenset(o['label'] for o in cur_objs)
            prev = snaps.get(i)
            snaps[i] = (cur_frame, cur_objs, cur_set)
            if prev is None:
                continue
            prev_frame, prev_objs, prev_set = prev
            # 라벨셋이 바뀌면 전송 (after가 비어도 "다 사라짐"은 valid 이벤트라 보냄)
            if cur_set != prev_set and cur_frame is not None and prev_frame is not None:
                print(f"📦 [{m.name}] 재고 변화: {sorted(prev_set)} → {sorted(cur_set)}")
                ts = time.strftime("%Y%m%d_%H%M%S")
                # 변화 분류: 사라짐(MISSING) / 나타남(APPEAR)
                gone = prev_set - cur_set          # 사라진 객체 라벨
                appeared = cur_set - prev_set      # 나타난 객체 라벨
                # before: 사라진 객체의 '사라지기 전 마지막 bbox'를 빨간 박스 (텍스트 없음 — 한글 깨짐 방지)
                before_img = prev_frame.copy()
                for o in prev_objs:
                    if o['label'] in gone and o.get('bbox'):
                        try:
                            x1, y1, x2, y2 = map(int, o['bbox'].split())
                            cv2.rectangle(before_img, (x1, y1), (x2, y2), (0, 0, 255), 2)
                        except Exception:
                            pass
                # after: 나타난 객체의 위치를 초록 박스
                after_img = cur_frame.copy()
                for o in cur_objs:
                    if o['label'] in appeared and o.get('bbox'):
                        try:
                            x1, y1, x2, y2 = map(int, o['bbox'].split())
                            cv2.rectangle(after_img, (x1, y1), (x2, y2), (0, 255, 0), 2)
                        except Exception:
                            pass
                # 파일명을 MISSING(사라짐)/APPEAR(나타남)로 구분 저장.
                #   MISSING: before=빨강(사라진 위치) / after=깨끗(사라진 뒤)
                #   APPEAR : before=깨끗(나타나기 전) / after=초록(나타난 위치)
                #   → 사라짐+나타남이 한 사이클에 겹쳐도 색이 섞이지 않음(빨강은 MISSING, 초록은 APPEAR만)
                if gone:
                    cv2.imwrite(f"{m.name}_MISSING_{ts}_before.jpg", before_img)
                    cv2.imwrite(f"{m.name}_MISSING_{ts}_after.jpg", cur_frame)
                if appeared:
                    cv2.imwrite(f"{m.name}_APPEAR_{ts}_before.jpg", prev_frame)
                    cv2.imwrite(f"{m.name}_APPEAR_{ts}_after.jpg", after_img)
                # device_state 캐시에 창문 유지: before/after 양쪽에 동일 창문 주입(diff엔 안 잡힘, after로 캐시 반영)
                win_list = [m.last_window_obj] if m.last_window_obj is not None else []
                send_event(m.device_id, before_img, after_img,
                           prev_objs + win_list, cur_objs + win_list)   # cooldown 없음(5초 주기가 제한)


def state_uploader(monitors):
    """매초 각 방의 '현재 탐지 객체 리스트'(lostflag 포함)를 콘솔 출력 + 서버 push.
    PRINT_STATE → 콘솔 출력 / SEND_STATE → 서버 전송 (각각 독립 토글)."""
    while True:
        time.sleep(STATE_INTERVAL)
        for m in monitors:
            if PRINT_STATE:                         # ← 콘솔 출력 (끄려면 PRINT_STATE=False)
                items = ", ".join(f"{o['label']}({o['status']})" if o['status'] else o['label'] for o in m.last_state) or "없음"
                print(f"[STATE] {m.name.lower()}: [{items}]")
            if SEND_STATE and m.device_id:          # ← 서버 전송 (켜려면 SEND_STATE=True)
                payload = {"device_id": m.device_id, "room": m.name.lower(), "objects": m.last_state}
                try:
                    requests.post(STATE_URL, json=payload, timeout=3)
                except Exception as e:
                    print(f"[상태 전송 실패] {e}")


# ==========================================
# 방(카메라)별 상태 + 처리
# ==========================================
class RoomMonitor:
    """카메라(방) 1대의 독립 상태와 처리를 담당.
    process()가 매 프레임 호출돼: YOLO 검출 → 재고 추적(present/pastlist 확정) →
    창문 상태머신(시스템 C) → 복약 행동 인식(시스템 B, MediaPipe 손+얼굴) 을 수행한다.
    재고 '나타남/사라짐' 이벤트(시스템 A)는 여기서 직접 안 보내고 batch_inventory_sender가 5초마다 묶어 전송.
    카메라마다 별도 인스턴스라 방별 상태(present/창문/복약/MediaPipe)가 서로 독립."""
    def __init__(self, name, device_id=""):
        self.name = name
        self.device_id = device_id
        self.window_before_frame = None
        self.window_before_objs = []
        self.touch_frame = None
        self.touch_objs = []
        self.last_objs_cap = []          # capture용 (conf 포함) 최신 객체
        self.present = {}                # 상태추적: label -> {'obj':..,'lost':0/1,'lost_since':t}
        self.last_state = []             # 매초 업로드용 스냅샷 (lostflag 포함)
        self.pastlist = []               # capture 반환용: 5초 유예 적용된 과거리스트(객체 많은 쪽)
        self.prev_clean = None           # 직전 프레임(이벤트 before용)
        self.prev_objs = []              # 직전 프레임 객체
        self.window_state = None
        self.window_target_state = None
        self.window_state_timestamp = 0.0
        self.window_target_count = 0     # 후보 상태 '연속' 누적 카운트(B: 누적 확정)
        self.last_real_state = None      # 마지막 '열림/닫힘' 확정값(없음은 건너뜀) — 전환 비교 기준
        self.last_window_obj = None      # 마지막 확정 창문 객체{label,status,bbox} — capture(device_state)용
        self.action_state = "IDLE"
        self.touch_timestamp = 0.0
        self.last_action_time = 0.0
        self.prev_time = 0.0
        self.fps = 0.0
        self.frame_count = 0          # 누적 평균 FPS용
        self.start_time = None
        self.avg_fps = 0.0
        self.hands = mp_hands.Hands(max_num_hands=2, min_detection_confidence=0.4)
        self.face = mp_face.FaceDetection(min_detection_confidence=0.4)

    def process(self, frame, current_time):
        h, w, _ = frame.shape

        if self.prev_time:
            inst = 1.0 / max(current_time - self.prev_time, 1e-6)
            self.fps = inst if self.fps == 0 else 0.9 * self.fps + 0.1 * inst
        self.prev_time = current_time

        # 누적 평균 FPS (총 프레임 ÷ 총 경과시간) — 모드 비교용
        if self.start_time is None:
            self.start_time = current_time
        self.frame_count += 1
        _elapsed = current_time - self.start_time
        self.avg_fps = self.frame_count / _elapsed if _elapsed > 0 else 0.0

        # 너무 어두운 프레임 → 검출/이벤트 스킵 (상태 동결: 객체 사라짐/헛검출 방지). 밝아지면 자동 재개
        if frame.mean() < DARK_THRESHOLD:
            cv2.putText(frame, f"{self.name}  FPS:{self.fps:.1f} (어두움-스킵)", (10, 25),
                        cv2.FONT_HERSHEY_SIMPLEX, 0.7, (0, 0, 255), 2)
            return frame

        clean_frame = frame.copy()
        rgb = cv2.cvtColor(frame, cv2.COLOR_BGR2RGB)

        # ---------- YOLO 검출 ----------
        results = model(frame, conf=CONF, imgsz=IMGSZ, agnostic_nms=True, verbose=False)  # 겹친 박스는 클래스 무관 1개만(창문 open/closed 동시출력 방지)
        current_counts = Counter()
        target_touch_boxes = []
        objs_now = []        # 이벤트용 (label/status/bbox)
        objs_cap = []        # capture용 (+conf)
        for r in results:
            for box in r.boxes:
                cname = model.names[int(box.cls[0])]
                conf = float(box.conf[0])
                if conf < CLASS_CONF.get(cname, CONF):   # 클래스별 기준(phone만 높게, 나머지 0.25)
                    continue
                current_counts[cname] += 1
                x1, y1, x2, y2 = map(int, box.xyxy[0])
                if cname in TARGET_TOUCH:
                    target_touch_boxes.append((x1, y1, x2, y2))
                status = '열림' if cname == 'window_open' else ('닫힘' if cname == 'window_closed' else "")  # 창문만 열림/닫힘, 그 외 ""
                bbox_str = f"{x1} {y1} {x2} {y2}"
                ko = LABEL_KO.get(cname, cname)
                objs_now.append({"label": ko, "status": status, "bbox": bbox_str})         # 이벤트: 한글
                objs_cap.append({"label": cname, "status": status, "bbox": bbox_str,        # capture/WS: 영문(OBJECT_MAPPING 매칭)
                                 "conf": round(conf, 2)})
                cv2.rectangle(frame, (x1, y1), (x2, y2), (0, 255, 0), 2)
                cv2.putText(frame, f"{cname}", (x1, y1 - 8),    # conf 숫자 미표시
                            cv2.FONT_HERSHEY_SIMPLEX, 0.5, (0, 255, 0), 2)

        self.last_objs_cap = objs_cap    # capture가 읽어갈 최신 객체 저장

        # ----- pastlist(present) 상태 추적만 -----
        # 재고 나타남/사라짐 '이벤트'는 batch_inventory_sender가 5초마다 묶어서 전송(요청 수↓).
        # 여기선 디바운스된 현재 재고 상태(present)만 갱신한다. (창문 포함 — capture/배치 비교용)
        now_objs = {o['label']: o for o in objs_now}
        now_labels = set(now_objs.keys())

        # (A) 보이는 객체: present 등록/갱신 + 누적검출로 '확정'(헛검출 필터)
        for lbl, o in now_objs.items():
            if lbl not in self.present:
                self.present[lbl] = {'obj': o, 'lost': 0, 'lost_since': 0.0,
                                     'det_count': 1, 'confirmed': False}
            else:
                st = self.present[lbl]
                st['obj'] = o            # bbox 갱신(위치 변환 반영)
                st['lost'] = 0           # 재탐지 → 오탐 취소
                st['lost_since'] = 0.0
                st['det_count'] += 1     # 누적 검출 횟수(갭 있어도 5초 유예로 유지됨)
            st = self.present[lbl]
            if not st['confirmed'] and st['det_count'] >= MIN_DET:
                st['confirmed'] = True   # 누적 MIN_DET회 → 확정(이후 계속 유지)

        # (B) 안 보이는 객체: DISAPPEAR_THRESHOLD초 유예 후 present에서 제거
        for lbl in list(self.present.keys()):
            if lbl not in now_labels:
                st = self.present[lbl]
                if st['lost'] == 0:
                    st['lost'] = 1
                    st['lost_since'] = current_time
                elif (current_time - st['lost_since']) >= DISAPPEAR_THRESHOLD:
                    del self.present[lbl]

        self.last_state = [{**st['obj'], 'lost': st['lost']} for st in self.present.values() if st['confirmed']]
        self.pastlist = [st['obj'] for st in self.present.values() if st['confirmed']]   # 확정 객체만(헛검출 제외)
        self.prev_clean = clean_frame   # 최신 장면 (배치 before/after용)

        # ---------- MediaPipe 손/얼굴 (약복용 판정에만) ----------
        hand_centers = []
        head_box = None
        if target_touch_boxes or self.action_state != "IDLE":
            hr = self.hands.process(rgb)
            if hr.multi_hand_landmarks:
                for hl in hr.multi_hand_landmarks:
                    hcx, hcy = int(hl.landmark[9].x * w), int(hl.landmark[9].y * h)
                    hand_centers.append((hcx, hcy))
                    cv2.circle(frame, (hcx, hcy), 8, (255, 0, 255), -1)
            fr = self.face.process(rgb)
            if fr.detections:
                d = fr.detections[0].location_data.relative_bounding_box
                fx, fy = int(d.xmin * w), int(d.ymin * h)
                fw, fh = int(d.width * w), int(d.height * h)
                head_box = (fx, fy, fx + fw, fy + fh)
                cv2.rectangle(frame, (fx, fy), (fx + fw, fy + fh), (0, 0, 255), 2)

        # ---------- 시스템 A: 위 'pastlist vs nowlist 비교'로 통합됨 (개수기반 → 라벨기반) ----------

        # ---------- 시스템 C: 창문 열림/닫힘 ----------
        # 검출을 'open'/'closed'/'none'(미검출=가림/창문없음) 3상태로 정규화.
        if current_counts.get('window_open', 0) > 0:
            detected = 'open'
        elif current_counts.get('window_closed', 0) > 0:
            detected = 'closed'
        else:
            detected = 'none'
        # B(누적 확정): 세 상태(open/closed/none) 모두 동일하게, 연속 WINDOW_MIN_DET회 +
        #   WINDOW_THRESHOLD초 이상 지속돼야 '확정'. 중간에 다른 검출이 끼면 카운트 리셋
        #   → 사람이 지나가며 생기는 불안정한 가짜 검출은 확정 못 됨.
        # 이벤트는 '마지막 열림/닫힘'과 '새 열림/닫힘'이 다를 때만 발사.
        #   없음은 건너뛰어(bridge) 비교 → 사람이 창문 조작하느라 가려서 중간에 없음이 껴도
        #   열림→(없음)→닫힘 을 정상 전환으로 잡음. 단순 가림(열림→없음→열림)은 동일 상태라 무시.
        if detected == self.window_state:
            # 확정 상태 재확인 → before 갱신(열림/닫힘일 때만) + 후보 리셋
            if detected != 'none':
                self.window_before_frame = clean_frame.copy()
                self.window_before_objs = objs_now
            self.window_target_state = self.window_state
            self.window_target_count = 0
        elif detected == self.window_target_state:
            # 같은 후보 연속 → 누적
            self.window_target_count += 1
            if self.window_target_count >= WINDOW_MIN_DET and \
                    (current_time - self.window_state_timestamp) >= WINDOW_THRESHOLD:
                new = self.window_target_state
                self.window_state = new
                self.window_target_count = 0
                if new in ('open', 'closed'):
                    old_real = self.last_real_state
                    self.last_real_state = new
                    # 확정 창문 객체 스냅샷(상태+bbox) → capture에서 device_state에 항상 포함
                    win = next((o for o in objs_now if o['label'] == '창문'), None)
                    if win is not None:
                        self.last_window_obj = win
                    if old_real in ('open', 'closed') and old_real != new:
                        # 진짜 상태 전환(열림<->닫힘) — 없음 건너뛴 전환 포함
                        kor = '열림' if new == 'open' else '닫힘'
                        old_kor = '열림' if old_real == 'open' else '닫힘'
                        print(f"[{self.name}] 창문 {kor} (이전: {old_kor})")
                        ts = time.strftime("%Y%m%d_%H%M%S")
                        before_img = self.window_before_frame if self.window_before_frame is not None else clean_frame
                        cv2.imwrite(f"{self.name}_WINDOW_{new}_{ts}_before.jpg", before_img)
                        cv2.imwrite(f"{self.name}_WINDOW_{new}_{ts}_after.jpg", clean_frame.copy())
                        send_event(self.device_id, before_img, clean_frame.copy(),
                                   self.window_before_objs, objs_now, cooldown_key="창문")
                    else:
                        # 초기상태 or 동일상태 복귀(가림 후 같은 상태) → 이벤트 없음
                        print(f"[{self.name}] 창문 상태확정 {new} (이전 real: {old_real}) — 이벤트 없음")
                else:
                    # 없음 확정 → last_real_state 유지(건너뜀), 이벤트 없음
                    print(f"[{self.name}] 창문 없음 확정 — last_real 유지, 이벤트 없음")
        else:
            # 새 후보 등장 → 카운트 시작
            self.window_target_state = detected
            self.window_state_timestamp = current_time
            self.window_target_count = 1

        # ---------- 시스템 B: 약 복용 행동 인식 ----------
        if self.action_state == "COOLDOWN":
            if current_time - self.last_action_time >= ACTION_COOLDOWN:
                self.action_state = "IDLE"
        elif self.action_state == "IDLE":
            for (hcx, hcy) in hand_centers:
                for (bx1, by1, bx2, by2) in target_touch_boxes:
                    if bx1 <= hcx <= bx2 and by1 <= hcy <= by2:
                        self.action_state = "TOUCHED"
                        self.touch_timestamp = current_time
                        self.touch_frame = frame.copy()
                        self.touch_objs = objs_now
                        print(f"[{self.name}] 약/컵 잡음 - 30초 내 복용")
                        break
        elif self.action_state == "TOUCHED":
            if current_time - self.touch_timestamp > ACTION_TIME_LIMIT:
                self.action_state = "IDLE"
                print(f"[{self.name}] 타임아웃")
            elif head_box is not None:
                hx1, hy1, hx2, hy2 = head_box
                m = HEAD_MARGIN
                for (hcx, hcy) in hand_centers:
                    if (hx1 - m) <= hcx <= (hx2 + m) and (hy1 - m) <= hcy <= (hy2 + m):
                        print(f"[{self.name}] 복용 완료!")
                        ts = time.strftime("%Y%m%d_%H%M%S")
                        before_img = self.touch_frame if self.touch_frame is not None else frame
                        cv2.imwrite(f"{self.name}_ACTION_{ts}_before.jpg", before_img)
                        cv2.imwrite(f"{self.name}_ACTION_{ts}_after.jpg", frame)
                        # 복약행동을 'after에만' 추가 → 서버 diff가 '복약행동 출현'을 변화로 인식.
                        #   before/after 객체셋은 동일(touch_objs)하게 둬서 유일한 변화 = 복약행동.
                        base_objs = self.touch_objs or []
                        # device_state 캐시에 창문 유지: before/after 양쪽에 동일 창문 주입(diff엔 안 잡힘)
                        win_list = [self.last_window_obj] if self.last_window_obj is not None else []
                        before_objs = base_objs + win_list
                        after_objs = base_objs + win_list + [{"label": "복약행동", "status": "0", "bbox": ""}]
                        send_event(self.device_id, before_img, frame.copy(), before_objs, after_objs,
                                   cooldown_key="복약")
                        self.last_action_time = current_time
                        self.action_state = "COOLDOWN"
                        break

        cv2.putText(frame, f"{self.name}  FPS:{self.fps:.1f}", (10, 25),
                    cv2.FONT_HERSHEY_SIMPLEX, 0.7, (255, 255, 0), 2)
        return frame


def draw_stream_overlay(img, room_name):
    t = time.strftime("%Y-%m-%d %H:%M:%S")
    cv2.putText(img, room_name, (10, 25), cv2.FONT_HERSHEY_SIMPLEX, 0.7, (255, 255, 0), 2)
    cv2.putText(img, t, (10, 52), cv2.FONT_HERSHEY_SIMPLEX, 0.6, (255, 255, 0), 2)


# ==========================================
# 메인
# ==========================================
threading.Thread(target=run_api, daemon=True).start()   # capture API 서버 시작
threading.Thread(target=ws_client, daemon=True).start() # WebSocket 실시간 캡처
threading.Thread(target=_event_worker, daemon=True).start()  # 이벤트 직렬 전송 워커
print(f"capture API: http://0.0.0.0:{API_PORT}/api/v1/capture")

caps, monitors = [], []
streamers = {}
for idx, cid in enumerate(CAM_IDS):
    cap = CameraStream(cid)
    if not cap.isOpened():
        print(f"[에러] 카메라 {cid} 열기 실패")
    caps.append(cap)
    dev = DEVICE_IDS[idx] if idx < len(DEVICE_IDS) else ""
    monitors.append(RoomMonitor(f"ROOM{idx}", dev))

threading.Thread(target=state_uploader, args=(monitors,), daemon=True).start()   # 매초 상태 업로드
threading.Thread(target=batch_inventory_sender, args=(monitors,), daemon=True).start()  # 재고 5초 배치 전송
print("시작 " + ("(debug: q로 종료)" if DEBUG else "(headless: Ctrl+C로 종료)"))
last_fps_print = 0.0
try:
    while True:
        now = time.time()
        for i, cap in enumerate(caps):
            ret, frame = cap.read()
            if not ret:
                continue
            clean = frame.copy()
            out = monitors[i].process(frame, now)

            # capture용 최신 데이터 갱신 (깨끗한 프레임 + conf 객체 + device_id)
            # 창문은 pastlist의 휘발성 항목 대신, 상태머신이 확정한 last_window_obj를 항상 포함
            #   (마지막 확정 상태 유지 / 가림 시 유지 / 한 번도 확정 안 되면 생략)
            cap_objs = [o for o in monitors[i].pastlist if o['label'] != '창문']
            if monitors[i].last_window_obj is not None:
                cap_objs.append(monitors[i].last_window_obj)
            with capture_lock:
                latest_capture[i] = {
                    'frame': clean,
                    'objects': cap_objs,
                    'device_id': monitors[i].device_id,
                }

            # TCP 송출
            if i not in streamers:
                streamers[i] = FrameStreamer(STREAM_BASE_PORT + i)
            stream_frame = cv2.resize(clean, (STREAM_W, STREAM_H))
            draw_stream_overlay(stream_frame, f"Room {i}")
            streamers[i].send(stream_frame)

            if DEBUG:
                cv2.imshow(f"Room {i}", out)

        # FPS 콘솔 출력 (2초마다) — debug/headless 둘 다 찍혀서 비교 가능
        if now - last_fps_print >= 2.0:
            print("[FPS] " + " | ".join(f"Room{i} 현재{m.fps:.1f}/평균{m.avg_fps:.1f}" for i, m in enumerate(monitors)))
            last_fps_print = now

        if DEBUG:
            if cv2.waitKey(1) & 0xFF == ord('q'):
                break
finally:
    print("\n===== 최종 평균 FPS =====")
    for i, m in enumerate(monitors):
        print(f"  Room{i}: 평균 {m.avg_fps:.2f} FPS  ({m.frame_count} 프레임)")
    for s in streamers.values():
        s.close()
    for cap in caps:
        cap.release()
    if DEBUG:
        cv2.destroyAllWindows()
