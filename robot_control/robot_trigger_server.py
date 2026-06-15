#RPi에서 신호를 받아 도커 컨테이너 내부에서 robot_start.py를 실행하는 트리거 서버
import socket
import json
import subprocess

def run_trigger_server():
    HOST = '0.0.0.0' # 컨테이너 내부로 들어오는 모든 주소 허용
    PORT = 9091      # 🌟 라파와 동일하게 9091번 포트로 수신 채널 일치
    
    # 실행할 자율주행 스크립트 절대 경로
    ROBOT_SCRIPT_PATH = "/root/ros2_ws/robot_start.py"
    
    with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as server_socket:
        server_socket.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        server_socket.bind((HOST, PORT))
        server_socket.listen()
        
        print(f"🧠 [로봇 가동 대기 서버] 도커 포트 포워딩 채널 {PORT}번에서 RPi 신호 대기 중...")
        
        while True:
            try:
                conn, addr = server_socket.accept()
                print(f"\n📡 [원격 신호 감지] RPi 접속 확인 (인입 IP: {addr[0]})")
                
                with conn:
                    data = conn.recv(1024)
                    if not data:
                        continue
                        
                    payload = json.loads(data.decode('utf-8'))
                    
                    if payload.get("command") == "ROBOT_RUN":
                        print("🔥 [트리거 확인] robot_start.py 자율주행 추론 엔진을 구동합니다!")
                        
                        # 컨테이너 내부에서 서브프로세스로 robot_start.py 출격!
                        subprocess.Popen(["python3", ROBOT_SCRIPT_PATH])
                        print("🚀 로봇 제어 스크립트 실행 명령 전달 완료.\n")
                        
            except Exception as e:
                print(f"⚠️ 에러 발생: {e}")

if __name__ == "__main__":
    run_trigger_server()