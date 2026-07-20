-- 创建数据库
CREATE DATABASE IF NOT EXISTS `scaffold-ai-assistant_nacos_test` DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci;
CREATE DATABASE IF NOT EXISTS `scaffold-ai-assistant_test` DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci;
CREATE DATABASE IF NOT EXISTS `scaffold-ai-assistant_xxljob_test` DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci;
CREATE DATABASE IF NOT EXISTS `scaffold-ai-assistant_skywalking_test` DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;

-- 创建用户（不存在才创建，存在则更新密码）
CREATE USER IF NOT EXISTS 'zmbdptest'@'%' IDENTIFIED BY 'Hf@173503494';
ALTER USER 'zmbdptest'@'%' IDENTIFIED BY 'Hf@173503494';

-- 授权（不需要 CDC 的话删掉 replication 那行）
GRANT REPLICATION SLAVE, REPLICATION CLIENT ON *.* TO 'zmbdptest'@'%';
GRANT ALL PRIVILEGES ON `scaffold-ai-assistant_nacos_test`.* TO 'zmbdptest'@'%';
GRANT ALL PRIVILEGES ON `scaffold-ai-assistant_test`.* TO 'zmbdptest'@'%';
GRANT ALL PRIVILEGES ON `scaffold-ai-assistant_xxljob_test`.* TO 'zmbdptest'@'%';
GRANT ALL PRIVILEGES ON `scaffold-ai-assistant_skywalking_test`.* TO 'zmbdptest'@'%';

FLUSH PRIVILEGES;