import cv2
import os
import time
import subprocess
import shutil
import numpy as np
from datetime import datetime
from fastapi import FastAPI
from fastapi.responses import FileResponse, JSONResponse, StreamingResponse
from fastapi.middleware.cors import CORSMiddleware
import uvicorn
import threading

# ================= 설정 부분 =================
# 카메라별 접속 주소 딕셔너리 설정 (포트 8555, 8556)
CAMERAS = {
    "cam1": "tcp://10.10.16.195:8555",
    "cam2": "tcp://10.10.16.195:8556"
}
BASE_DIR = "records"
FPS = 30.0
WIDTH = 640                             
HEIGHT = 480                            
SEGMENT_SECONDS = 60
RETENTION_DAYS = 3
API_PORT = 8001
# =============================================

app = FastAPI(title="Dual HomeCam VOD & Live API")

app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
)

# [상태 공유 변수] - 카메라별로 독립적인 상태를 저장할 딕셔너리
latest_frames = {"cam1": None, "cam2": None}
current_recording_files = {"cam1": None, "cam2": None}

def ensure_dir(path):
    if not os.path.exists(path):
        os.makedirs(path)

def cleanup_old_files(cam_name):
    """특정 카메라 폴더의 오래된 파일을 정리합니다."""
    cam_dir = os.path.join(BASE_DIR, cam_name)
    if not os.path.exists(cam_dir): return
    
    now = datetime.now()
    today_midnight = now.replace(hour=0, minute=0, second=0, microsecond=0)
    for filename in os.listdir(cam_dir):
        file_path = os.path.join(cam_dir, filename)
        if not os.path.isfile(file_path): continue
        try:
            date_str = filename.split('_')[0]
            file_date = datetime.strptime(date_str, "%Y-%m-%d")
            if (today_midnight - file_date).days >= RETENTION_DAYS:
                os.remove(file_path)
                print(f"[+] [{cam_name}] 자동 삭제됨: {filename}")
        except: continue

# ================= VOD API 엔드포인트 =================

@app.get("/api/videos/{cam_name}")
async def get_video_list(cam_name: str):
    """특정 카메라의 완성된 영상 목록 반환 (예: /api/videos/cam1)"""
    if cam_name not in CAMERAS:
        return JSONResponse(status_code=400, content={"status": "error", "message": "Invalid camera name"})

    global current_recording_files
    videos = []
    cam_dir = os.path.join(BASE_DIR, cam_name)
    
    if os.path.exists(cam_dir):
        for filename in os.listdir(cam_dir):
            if filename.endswith(".mp4") and filename != current_recording_files[cam_name]:
                file_size = os.path.getsize(os.path.join(cam_dir, filename))
                videos.append({"filename": filename, "size": file_size})
    
    videos.sort(key=lambda x: x["filename"], reverse=True)
    return JSONResponse(content={"status": "success", "data": videos})

@app.get("/api/video/{cam_name}/{filename}")
async def stream_video(cam_name: str, filename: str):
    """특정 카메라의 영상 스트리밍"""
    file_path = os.path.join(BASE_DIR, cam_name, filename)
    if os.path.exists(file_path):
        return FileResponse(file_path, media_type="video/mp4", filename=filename)
    return JSONResponse(status_code=404, content={"status": "error", "message": "File not found"})

# ================= LIVE API 엔드포인트 =================

def generate_live_frames(cam_name: str):
    """요청받은 카메라의 원본 프레임을 16:9 캔버스에 올려 스트리밍"""
    global latest_frames
    TARGET_RATIO = 16.0 / 9.0

    while True:
        frame = latest_frames.get(cam_name)
        if frame is not None:
            try:
                frame_copy = frame.copy()
                h, w = frame_copy.shape[:2]
                current_ratio = w / h

                if current_ratio < TARGET_RATIO:
                    new_w = int(h * TARGET_RATIO)
                    new_h = h
                elif current_ratio > TARGET_RATIO:
                    new_w = w
                    new_h = int(w / TARGET_RATIO)
                else:
                    new_w, new_h = w, h

                canvas = np.zeros((new_h, new_w, 3), dtype=np.uint8)
                x_offset = (new_w - w) // 2
                y_offset = (new_h - h) // 2
                canvas[y_offset:y_offset+h, x_offset:x_offset+w] = frame_copy

                ret, buffer = cv2.imencode('.jpg', canvas)
                if ret:
                    frame_bytes = buffer.tobytes()
                    yield (b'--frame\r\n'
                           b'Content-Type: image/jpeg\r\n\r\n' + frame_bytes + b'\r\n')
            except Exception:
                pass
                
        time.sleep(0.03)

@app.get("/api/live/{cam_name}", response_class=StreamingResponse)
async def get_live_stream(cam_name: str):
    """특정 카메라 실시간 스트리밍 (예: /api/live/cam1)"""
    if cam_name not in CAMERAS:
        return JSONResponse(status_code=400, content={"status": "error", "message": "Invalid camera name"})
        
    return StreamingResponse(
        content=generate_live_frames(cam_name),
        media_type="multipart/x-mixed-replace; boundary=frame"
    )

# ================= 영상 녹화 백그라운드 스레드 =================

def create_ffmpeg_process(output_filepath):
    command = [
        'ffmpeg', '-y', '-f', 'rawvideo', '-vcodec', 'rawvideo',
        '-s', f"{WIDTH}x{HEIGHT}", '-pix_fmt', 'bgr24', '-r', str(FPS),
        '-i', '-', '-c:v', 'libx264', '-preset', 'ultrafast', '-crf', '28',
        '-pix_fmt', 'yuv420p', output_filepath
    ]
    return subprocess.Popen(command, stdin=subprocess.PIPE, stderr=subprocess.DEVNULL)

def record_stream_task(cam_name: str, stream_url: str):
    """각 카메라별로 독립적으로 동작하는 녹화 루프"""
    global latest_frames, current_recording_files
    
    cam_dir = os.path.join(BASE_DIR, cam_name)
    ensure_dir(cam_dir)
    cleanup_old_files(cam_name)
    
    cap = cv2.VideoCapture(stream_url)
    if not cap.isOpened():
        print(f"[!] [{cam_name}] 스트림 연결 실패. 라즈베리파이를 확인하세요.")
        return

    current_process = None
    start_time = time.time()
    current_date_str = None

    print(f"[*] [{cam_name}] 백그라운드 스트림 녹화를 시작합니다.")
    
    while True:
        ret, frame = cap.read()
        if not ret:
            print(f"[!] [{cam_name}] 스트림 끊김. 5초 후 재연결 시도...")
            time.sleep(5)
            cap = cv2.VideoCapture(stream_url)
            continue

        latest_frames[cam_name] = frame.copy()

        now = datetime.now()
        today_str = now.strftime("%Y-%m-%d")
        elapsed_time = time.time() - start_time
        
        if current_process is None or elapsed_time >= SEGMENT_SECONDS:
            if current_date_str != today_str:
                cleanup_old_files(cam_name)
            
            if current_process:
                current_process.stdin.close()
                current_process.wait()
            
            file_name = now.strftime("%Y-%m-%d_%H-%M.mp4")
            current_recording_files[cam_name] = file_name  
            output_path = os.path.join(cam_dir, file_name)
            
            print(f"[*] [{cam_name}] 분할 저장 중: {file_name}")
            current_process = create_ffmpeg_process(output_path)
            start_time = time.time()
            current_date_str = today_str

        try:
            current_process.stdin.write(frame.tobytes())
        except:
            pass

# ================= 메인 실행 =================

if __name__ == "__main__":
    # 등록된 모든 카메라(cam1, cam2)에 대해 각각 독립적인 백그라운드 스레드 생성 및 실행
    for cam_name, url in CAMERAS.items():
        thread = threading.Thread(target=record_stream_task, args=(cam_name, url), daemon=True)
        thread.start()
    
    print(f"[*] 다중 채널 API 서버가 포트 {API_PORT}에서 시작되었습니다.")
    uvicorn.run(app, host="0.0.0.0", port=API_PORT)