import sys
import os
sys.path.append(os.path.expanduser("/root/ros2_ws/src/physical_ai_tools"))
import time
import torch
import cv2  
import numpy as np
import rclpy
from rclpy.node import Node
from sensor_msgs.msg import CompressedImage, JointState  
from trajectory_msgs.msg import JointTrajectory, JointTrajectoryPoint
import builtin_interfaces.msg

# FastAPI 서버 연동용 HTTP 라이브러리
import httpx 

from lerobot.policies.act.modeling_act import ACTPolicy

class RealRobotACTInferenceNode(Node):
    def __init__(self, model_dir):
        super().__init__('real_robot_act_inference_node')
        
        # FastAPI 메인 서버 API 엔드포인트 주소
        self.SERVER_REPORT_URL = "http://10.10.16.15:8000/api/v1/event/manual"
        
        # event_tb 적재용 고정 메타데이터 규격
        self.USER_ID = "b4c4f3e6-2d9c-4ec6-af36-08581a6b1339"
        self.DEVICE_ID = "3716a4a8-41f7-4574-977c-dde7833a2fd1"
        self.target_item = "key" 

        # 모델 가중치 로드
        self.get_logger().info(f"💾 단일 자율주행 추론 모델 로드 중: {model_dir}")
        self.policy = ACTPolicy.from_pretrained(model_dir)
        self.policy.eval() 
        self.get_logger().info("✅ 대시보드 인퍼런스 엔진 로드 완료!")

        # 실시간 데이터 구독 및 발행 채널 세팅
        self.cam1_sub = self.create_subscription(CompressedImage, '/camera1/image_raw/compressed', self.cam1_callback, 10)
        self.cam2_sub = self.create_subscription(CompressedImage, '/camera2/image_raw/compressed', self.cam2_callback, 10)
        self.joint_sub = self.create_subscription(JointState, '/joint_states', self.joint_state_callback, 10)
        self.cmd_pub = self.create_publisher(JointTrajectory, '/leader/joint_trajectory', 10)

        self.joint_names = ['gripper_joint_1', 'joint1', 'joint2', 'joint3', 'joint4', 'joint5']
        self.latest_img1 = None
        self.latest_img2 = None
        self.current_qpos_raw = [0.0, 0.0, 0.0, 0.0, 0.0, 0.0]

        # 🌟 [반복 출격-복귀 제어용 상태 기계 변수]
        self.home_qpos = None          # 최초 ㄷ자 대기 포즈의 관절값 자동 저장소
        self.has_moved_out = False     # 로봇이 정리를 시작하기 위해 ㄷ자 자세를 탈출했는가?
        self.is_reported = False       # 해당 에피소드(이번 정리스텝)에서 FastAPI 보고를 완료했는가?
        self.home_stay_start = None    # ㄷ자 모양으로 안전 복귀 후 머문 시간 측정용 타이머

        # 30 FPS 주기적인 자율 추론 타이머 기동 (1/30초)
        self.timer = self.create_timer(1.0 / 30.0, self.자율_추론_루프)

    def decode_ros_compressed_msg(self, msg):
        np_arr = np.frombuffer(msg.data, np.uint8)
        cv_img = cv2.imdecode(np_arr, cv2.IMREAD_COLOR)
        cv_img = cv2.cvtColor(cv_img, cv2.COLOR_BGR2RGB)
        cv_img = cv2.resize(cv_img, (640, 480))
        img_tensor = torch.from_numpy(cv_img).transpose(0, 2).transpose(1, 2).float() / 255.0
        return img_tensor

    def cam1_callback(self, msg):
        self.latest_img1 = self.decode_ros_compressed_msg(msg)

    def cam2_callback(self, msg):
        self.latest_img2 = self.decode_ros_compressed_msg(msg)

    def joint_state_callback(self, msg):
        if len(msg.position) >= 6:
            hardware_positions = msg.position
            # 하드웨어 드라이버 배열 순서를 모델 규격으로 정렬 [j1, j2, j3, j4, j5, gripper]
            self.current_qpos_raw = list(hardware_positions[1:6]) + [hardware_positions[0]]
            
            # 최초 기동 시 현재 로봇의 실제 ㄷ자 대기 포즈 관절 각도를 홈 기준으로 획득
            if self.home_qpos is None:
                if any(x != 0.0 for x in self.current_qpos_raw):
                    self.home_qpos = list(self.current_qpos_raw)
                    self.get_logger().info(f"🏠 [ㄷ자 홈 포지션 감지 성공] 기준 관절값: {[round(x, 3) for x in self.home_qpos]}")

    def post_event_to_fastapi(self):
        """ event_tb JSON 포맷으로 보고 데이터 전송 """
        item_ko_map = {
            "wallet": "지갑",
            "key": "차 키",
            "remote": "리모컨"
        }
        item_ko = item_ko_map.get(self.target_item, self.target_item)

        payload = {
            "user_id": self.USER_ID,
            "event_ct": f"로봇팔이 {item_ko}을 정리했습니다",
            "device_id": self.DEVICE_ID,
            "objects": [
                {
                    "label": self.target_item 
                }
            ]
        }
        
        self.get_logger().info(f"📤 [FastAPI event_tb 데이터 전송] -> {self.SERVER_REPORT_URL}")
        try:
            response = httpx.post(self.SERVER_REPORT_URL, json=payload, timeout=5.0)
            if response.status_code in [200, 201]:
                self.get_logger().info("✅ [서버 적재 성공] 데이터베이스 레코드가 정상 반영되었습니다.")
            else:
                self.get_logger().error(f"⚠️ [서버 응답 에러] 코드: {response.status_code} | 내용: {response.text}")
        except Exception as e:
            self.get_logger().error(f"❌ [REST API 통신 예외] 백엔드 서버 활성화 상태를 확인하세요: {e}")

    def 자율_추론_루프(self):
        if self.latest_img1 is None or self.latest_img2 is None:
            self.get_logger().warn("📷 양쪽 카메라 영상 토픽 수신 대기 중...", throttle_duration_sec=5.0)
            return

        start_time = time.time()

        device = next(self.policy.parameters()).device
        current_qpos_tensor = torch.tensor([self.current_qpos_raw], dtype=torch.float32).to(device)

        observation = {
            "observation.images.camera1": self.latest_img1.unsqueeze(0).to(device),
            "observation.images.camera2": self.latest_img2.unsqueeze(0).to(device),
            "observation.state": current_qpos_tensor
        }

        with torch.no_grad():
            predicted_chunk = self.policy.select_action(observation)
        
        current_action = predicted_chunk[0, :].cpu().tolist()
        robot_ordered_action = [current_action[5]] + current_action[:5]

        # 모터 제어 명령 방출
        traj_msg = JointTrajectory()
        traj_msg.joint_names = self.joint_names
        point = JointTrajectoryPoint()
        point.positions = robot_ordered_action
        point.time_from_start = builtin_interfaces.msg.Duration(sec=0, nanosec=33000000)
        traj_msg.points.append(point)
        self.cmd_pub.publish(traj_msg)

        compute_time = time.time() - start_time
        self.get_logger().info(f"🎯 [실시간 자율 구동중] 연산 속도: {compute_time*1000:.1f}ms", throttle_duration_sec=2.0)

        # =================================================================
        # 🌟 [출격-복귀 감지 상태 머신]
        # =================================================================
        if self.home_qpos is not None:
            # 그리퍼를 제외한 5개 주요 관절의 평균 절대 오차 계산
            current_arm_joints = np.array(self.current_qpos_raw[:5])
            home_arm_joints = np.array(self.home_qpos[:5])
            qpos_error = np.mean(np.abs(current_arm_joints - home_arm_joints))
            
            # 상태 1: 로봇이 차 키를 다시 잡으러 ㄷ자 대기 포즈를 깨고 이탈(출격)한 경우
            if not self.has_moved_out and qpos_error > 0.12:
                self.has_moved_out = True
                self.is_reported = False  # 🌟 핵심: 새 출격이 일어났으므로 다음 성공을 위해 리포트 플래그 리셋!
                self.get_logger().info("🚨 [상태 전환] 로봇팔이 차 키 정리를 위해 새로운 출격을 시작했습니다!")

            # 상태 2: 작업을 다 끝내고 다시 원래의 ㄷ자 포즈(홈) 반경 안으로 진입한 경우
            if self.has_moved_out and qpos_error < 0.08:
                if self.home_stay_start is None:
                    self.home_stay_start = time.time() # 복귀 타이머 가동
                
                # ㄷ자 홈에 완전히 복귀하여 0.2초 이상 안착했고, 아직 이번 턴의 보고를 안 했다면
                elif (time.time() - self.home_stay_start > 0.2) and (not self.is_reported):
                    self.is_reported = True    # 이번 턴 중복 전송 락
                    self.has_moved_out = False  # 다음 출격을 대기하기 위해 출격 플래그 원위치
                    
                    self.get_logger().info(f"🏁 [복귀 성공] ㄷ자 포즈 안착 완료 (5축 오차: {qpos_error:.4f})")
                    
                    # 🚀 차 키를 옮겨놓고 홈으로 귀환할 때마다 매번 새로 백엔드 데이터 적재 푸시!
                    self.post_event_to_fastapi()
                    self.get_logger().info("🔭 [상시 감시 체제 유지] 다음 차 키 유실 상황 대기 중...\n")
            else:
                # ㄷ자 범위를 벗어나 움직이는 활동 중에는 복귀 안착 타이머 초기화
                self.home_stay_start = None

def main(args=None):
    rclpy.init(args=args)
    
    model_dir = "/root/ros2_ws/src/physical_ai_tools/lerobot/outputs/train/omx_car_key_30fps/checkpoints/040000/pretrained_model"
    node = RealRobotACTInferenceNode(model_dir)
    
    try:
        rclpy.spin(node)
    except (KeyboardInterrupt, SystemExit):
        pass
    finally:
        node.destroy_node()
        rclpy.shutdown()

if __name__ == "__main__":
    main()