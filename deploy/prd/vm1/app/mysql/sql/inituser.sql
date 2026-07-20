-- 创建数据库
CREATE DATABASE IF NOT EXISTS `scaffold-ai-assistant_nacos_prd` DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci;
CREATE DATABASE IF NOT EXISTS `scaffold-ai-assistant_prd` DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci;
CREATE DATABASE IF NOT EXISTS `scaffold-ai-assistant_xxljob_prd` DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci;
CREATE DATABASE IF NOT EXISTS `scaffold-ai-assistant_skywalking_prd` DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;

-- 创建用户（不存在才创建，存在则更新密码）
CREATE USER IF NOT EXISTS 'zmbdpprd'@'%' IDENTIFIED BY 'Hf@173503494';
ALTER USER 'zmbdpprd'@'%' IDENTIFIED BY 'Hf@173503494';

-- 授权（不需要 CDC 的话删掉 replication 那行）
GRANT REPLICATION SLAVE, REPLICATION CLIENT ON *.* TO 'zmbdpprd'@'%';
GRANT ALL PRIVILEGES ON `scaffold-ai-assistant_nacos_prd`.* TO 'zmbdpprd'@'%';
GRANT ALL PRIVILEGES ON `scaffold-ai-assistant_prd`.* TO 'zmbdpprd'@'%';
GRANT ALL PRIVILEGES ON `scaffold-ai-assistant_xxljob_prd`.* TO 'zmbdpprd'@'%';
GRANT ALL PRIVILEGES ON `scaffold-ai-assistant_skywalking_prd`.* TO 'zmbdpprd'@'%';

FLUSH PRIVILEGES;