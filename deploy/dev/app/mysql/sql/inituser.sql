-- 创建数据库
CREATE DATABASE IF NOT EXISTS `scaffold-ai-assistant_nacos_dev` DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci;
CREATE DATABASE IF NOT EXISTS `scaffold-ai-assistant_dev` DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci;
CREATE DATABASE IF NOT EXISTS `scaffold-ai-assistant_xxljob_dev` DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci;
CREATE DATABASE IF NOT EXISTS `scaffold-ai-assistant_skywalking_dev` DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;

-- 创建用户（不存在才创建，存在则更新密码）
CREATE USER IF NOT EXISTS 'zmbdpdev'@'%' IDENTIFIED BY 'Hf@173503494';
ALTER USER 'zmbdpdev'@'%' IDENTIFIED BY 'Hf@173503494';

-- 授权（不需要 CDC 的话删掉 replication 那行）
GRANT REPLICATION SLAVE, REPLICATION CLIENT ON *.* TO 'zmbdpdev'@'%';
GRANT ALL PRIVILEGES ON `scaffold-ai-assistant_nacos_dev`.* TO 'zmbdpdev'@'%';
GRANT ALL PRIVILEGES ON `scaffold-ai-assistant_dev`.* TO 'zmbdpdev'@'%';
GRANT ALL PRIVILEGES ON `scaffold-ai-assistant_xxljob_dev`.* TO 'zmbdpdev'@'%';
GRANT ALL PRIVILEGES ON `scaffold-ai-assistant_skywalking_dev`.* TO 'zmbdpdev'@'%';

FLUSH PRIVILEGES;