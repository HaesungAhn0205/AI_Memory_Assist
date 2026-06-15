-- --------------------------------------------------------
-- 호스트:                          10.10.16.238
-- 서버 버전:                        11.8.6-MariaDB-0+deb13u1 from Debian - -- Please help get to 10k stars at https://github.com/MariaDB/Server
-- 서버 OS:                        debian-linux-gnu
-- HeidiSQL 버전:                  12.15.0.7171
-- --------------------------------------------------------

/*!40101 SET @OLD_CHARACTER_SET_CLIENT=@@CHARACTER_SET_CLIENT */;
/*!40101 SET NAMES utf8 */;
/*!50503 SET NAMES utf8mb4 */;
/*!40103 SET @OLD_TIME_ZONE=@@TIME_ZONE */;
/*!40103 SET TIME_ZONE='+00:00' */;
/*!40014 SET @OLD_FOREIGN_KEY_CHECKS=@@FOREIGN_KEY_CHECKS, FOREIGN_KEY_CHECKS=0 */;
/*!40101 SET @OLD_SQL_MODE=@@SQL_MODE, SQL_MODE='NO_AUTO_VALUE_ON_ZERO' */;
/*!40111 SET @OLD_SQL_NOTES=@@SQL_NOTES, SQL_NOTES=0 */;


-- aru_db 데이터베이스 구조 내보내기
CREATE DATABASE IF NOT EXISTS `aru_db` /*!40100 DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_uca1400_ai_ci */;
USE `aru_db`;

-- 테이블 aru_db.device_state_tb 구조 내보내기
CREATE TABLE IF NOT EXISTS `device_state_tb` (
  `device_id` uuid NOT NULL,
  `user_id` uuid NOT NULL,
  `objects` longtext CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL CHECK (json_valid(`objects`)),
  `updated_at` timestamp NULL DEFAULT current_timestamp() ON UPDATE current_timestamp(),
  PRIMARY KEY (`device_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_uca1400_ai_ci;

-- 내보낼 데이터가 선택되어 있지 않습니다.

-- 테이블 aru_db.device_tb 구조 내보내기
CREATE TABLE IF NOT EXISTS `device_tb` (
  `id` uuid NOT NULL DEFAULT uuid_v4(),
  `user_id` uuid NOT NULL,
  `device_name` varchar(100) NOT NULL,
  `address` varchar(100) DEFAULT NULL,
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_uca1400_ai_ci;

-- 내보낼 데이터가 선택되어 있지 않습니다.

-- 테이블 aru_db.event_tb 구조 내보내기
CREATE TABLE IF NOT EXISTS `event_tb` (
  `id` uuid NOT NULL,
  `event_dt` timestamp NULL DEFAULT current_timestamp(),
  `event_ct` text DEFAULT NULL,
  `objects` longtext CHARACTER SET utf8mb4 COLLATE utf8mb4_bin DEFAULT NULL CHECK (json_valid(`objects`)),
  `user_id` uuid DEFAULT NULL,
  `device_id` uuid DEFAULT NULL,
  `embedding` vector(768) NOT NULL,
  PRIMARY KEY (`id`),
  VECTOR KEY `vec_idx` (`embedding`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_uca1400_ai_ci;

-- 내보낼 데이터가 선택되어 있지 않습니다.

-- 테이블 aru_db.routine_tb 구조 내보내기
CREATE TABLE IF NOT EXISTS `routine_tb` (
  `id` uuid NOT NULL DEFAULT uuid_v4(),
  `user_id` uuid NOT NULL,
  `alarm_time` time NOT NULL,
  `alarm_days` varchar(50) NOT NULL,
  `alarm_content` varchar(255) NOT NULL,
  `status` varchar(20) NOT NULL DEFAULT 'PENDING',
  `type` varchar(20) DEFAULT NULL COMMENT 'medicine, window',
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_uca1400_ai_ci;

-- 내보낼 데이터가 선택되어 있지 않습니다.

-- 테이블 aru_db.user_tb 구조 내보내기
CREATE TABLE IF NOT EXISTS `user_tb` (
  `id` uuid NOT NULL DEFAULT uuid_v4(),
  `user_name` varchar(100) NOT NULL,
  `user_pw` varchar(255) NOT NULL,
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_uca1400_ai_ci;

-- 내보낼 데이터가 선택되어 있지 않습니다.

/*!40103 SET TIME_ZONE=IFNULL(@OLD_TIME_ZONE, 'system') */;
/*!40101 SET SQL_MODE=IFNULL(@OLD_SQL_MODE, '') */;
/*!40014 SET FOREIGN_KEY_CHECKS=IFNULL(@OLD_FOREIGN_KEY_CHECKS, 1) */;
/*!40101 SET CHARACTER_SET_CLIENT=@OLD_CHARACTER_SET_CLIENT */;
/*!40111 SET SQL_NOTES=IFNULL(@OLD_SQL_NOTES, 1) */;
